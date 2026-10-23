package com.betalgezia.omnivpn.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.betalgezia.omnivpn.data.NodeRepository
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.ConnectionManager
import com.betalgezia.omnivpn.vpn.VpnEventBus
import com.betalgezia.omnivpn.vpn.VpnState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the in-app update is. Everything but [Idle] carries the release and the APK for this phone. */
sealed interface UpdateState {
    /** Nothing newer is known. */
    data object Idle : UpdateState

    data class Available(val release: ReleaseInfo, val asset: ReleaseAsset) : UpdateState

    /** [totalBytes] is -1 when neither GitHub nor the download said how big it is. */
    data class Downloading(val release: ReleaseInfo, val asset: ReleaseAsset, val doneBytes: Long, val totalBytes: Long) : UpdateState

    /** Downloaded; Android must first allow OmniVPN to install apps (once). */
    data class NeedsPermission(val release: ReleaseInfo, val asset: ReleaseAsset) : UpdateState

    /** Handed to the system installer: its confirmation is up, or the install runs. */
    data class Installing(val release: ReleaseInfo, val asset: ReleaseAsset) : UpdateState

    /** [canInstall]: the file is already here, so the retry skips the download. */
    data class Failed(val release: ReleaseInfo, val asset: ReleaseAsset, val message: String, val canInstall: Boolean) : UpdateState
}

/** The release an update state is about (null for [UpdateState.Idle]). */
val UpdateState.release: ReleaseInfo?
    get() = when (this) {
        UpdateState.Idle -> null
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.NeedsPermission -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
    }

private val UpdateState.asset: ReleaseAsset?
    get() = when (this) {
        UpdateState.Idle -> null
        is UpdateState.Available -> asset
        is UpdateState.Downloading -> asset
        is UpdateState.NeedsPermission -> asset
        is UpdateState.Installing -> asset
        is UpdateState.Failed -> asset
    }

/** A failure whose message is already fit for the user. */
private class UpdateFailure(message: String) : Exception(message)

/**
 * Updates OmniVPN from its GitHub releases: looks for a newer release (on
 * launch, at most every [UPDATE_CHECK_INTERVAL_MS], and on «Проверить
 * обновления»), downloads the APK for this phone, checks it and hands it to
 * Android's installer, which asks the user to confirm. Android only installs
 * it over this app if it is signed with the same key - a swapped file simply
 * doesn't install.
 *
 * The install restarts the app, which takes the tunnel down; what was
 * connected is saved first and PackageReplacedReceiver reconnects it.
 *
 * Test builds («OmniVPN Preview», local "dev.N") never check on their own; a
 * manual check there treats any release as newer, so the card and the
 * download can be tried, but the file is refused (it's the other app).
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: UpdateStore,
    private val connectionManager: ConnectionManager,
    private val eventBus: VpnEventBus,
    private val nodeRepository: NodeRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()

    /** The installed version, «1.3.0» (or «dev.N» for a local build). */
    val currentVersion: String = packageInfo?.versionName.orEmpty()
    private val currentVersionCode: Long = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L

    val isTestBuild: Boolean = context.packageName.endsWith(".preview") || parseVersion(currentVersion) == null

    // A test build has no real version to compare: any release counts as newer.
    private val comparedVersion = if (isTestBuild) "0" else currentVersion

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** True while a check runs (the «Проверить обновления» spinner). */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _skippedVersion = MutableStateFlow(store.skippedVersion)
    val skippedVersion: StateFlow<String?> = _skippedVersion.asStateFlow()

    /** The installer's confirmation screen, for the activity to show (see MainActivity). */
    private val _confirmation = MutableStateFlow<Intent?>(null)
    val confirmation: StateFlow<Intent?> = _confirmation.asStateFlow()

    /** One-off texts for the snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var downloadJob: Job? = null

    init {
        // Whatever was downloaded before is either installed by now or stale.
        runCatching { updateDir().deleteRecursively() }
        // Keep the reconnect target in step while the installer is up: the
        // user may switch servers or disconnect before confirming.
        scope.launch {
            combine(eventBus.state, connectionManager.activeConnection) { _, _ -> Unit }.collect {
                if (_state.value is UpdateState.Installing) store.saveReconnect(reconnectTarget())
            }
        }
    }

    private fun initialState(): UpdateState {
        if (isTestBuild) return UpdateState.Idle
        return store.cachedRelease?.let(::offerFor) ?: UpdateState.Idle
    }

    private fun offerFor(release: ReleaseInfo): UpdateState.Available? {
        if (!isNewerVersion(release.version, comparedVersion)) return null
        val asset = pickAsset(release, Build.SUPPORTED_ABIS.toList()) ?: return null
        return UpdateState.Available(release, asset)
    }

    // ---- Checking ----

    /** On launch: a quiet check when one is due. */
    fun checkOnLaunch() {
        if (isTestBuild || _checking.value) return
        if (!updateCheckDue(store.lastCheckMs, System.currentTimeMillis())) return
        scope.launch { check() }
    }

    /** «Проверить обновления»: always asks GitHub, and says what it found. */
    fun checkNow() {
        if (_checking.value) return
        scope.launch {
            check()
                .onSuccess { release ->
                    when {
                        release == null -> _messages.tryEmit("На GitHub пока нет ни одного релиза")
                        offerFor(release) == null -> _messages.tryEmit("У вас последняя версия")
                        // A newer one: the card itself says so.
                    }
                }
                .onFailure { _messages.tryEmit("Не удалось проверить обновления: ${reasonOf(it)}") }
        }
    }

    private suspend fun check(): Result<ReleaseInfo?> {
        _checking.value = true
        try {
            val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            store.lastCheckMs = System.currentTimeMillis()
            result
                .onSuccess { release ->
                    if (!isTestBuild) store.cachedRelease = release
                    applyRelease(release)
                }
                .onFailure { android.util.Log.w(TAG, "check failed: ${it.message}") }
            return result
        } finally {
            _checking.value = false
        }
    }

    private fun applyRelease(release: ReleaseInfo?) {
        val current = _state.value
        // Never interrupt a download or an install with a fresh check.
        if (current is UpdateState.Downloading || current is UpdateState.NeedsPermission || current is UpdateState.Installing) return
        if (current is UpdateState.Failed && current.release.version == release?.version) return
        _state.value = release?.let(::offerFor) ?: UpdateState.Idle
    }

    /** The API first; when it refuses (rate limit), the redirect of the releases page. */
    private fun fetchLatest(): ReleaseInfo? {
        val api = open(LATEST_RELEASE_API).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            when (val code = api.responseCode) {
                200 -> return parseReleaseJson(readText(api)) ?: throw UpdateFailure("GitHub прислал непонятный ответ")
                404 -> return null
                403, 429 -> android.util.Log.i(TAG, "API refused ($code), trying the releases page")
                else -> throw UpdateFailure("GitHub ответил $code")
            }
        } finally {
            api.disconnect()
        }
        val page = open(LATEST_RELEASE_PAGE).apply { instanceFollowRedirects = false }
        try {
            val code = page.responseCode
            if (code !in 300..399) throw UpdateFailure("GitHub ответил $code")
            return tagFromReleaseUrl(page.getHeaderField("Location"))?.let(::releaseFromTag)
        } finally {
            page.disconnect()
        }
    }

    // ---- Downloading and installing ----

    /** «Обновить» / «Повторить» / «Установить» on the card. */
    fun startUpdate() {
        when (val s = _state.value) {
            is UpdateState.Available -> download(s.release, s.asset)
            is UpdateState.Failed ->
                if (s.canInstall && apkFile(s.asset).exists()) install(s.release, s.asset) else download(s.release, s.asset)
            is UpdateState.NeedsPermission -> install(s.release, s.asset)
            else -> Unit
        }
    }

    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateState.Available(s.release, s.asset)
    }

    /** «Пропустить эту версию»: the home card stays away until the next release. */
    fun skip() {
        val version = _state.value.release?.version ?: return
        store.skippedVersion = version
        _skippedVersion.value = version
    }

    private fun download(release: ReleaseInfo, asset: ReleaseAsset) {
        downloadJob?.cancel()
        _state.value = UpdateState.Downloading(release, asset, 0L, asset.sizeBytes)
        downloadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = fetchApk(release, asset)
                    verify(file)?.let { problem ->
                        file.delete()
                        throw UpdateFailure(problem)
                    }
                    file
                }
            }
            result
                .onSuccess { install(release, asset) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    android.util.Log.w(TAG, "download failed", it)
                    val text = if (it is UpdateFailure) reasonOf(it) else "Не удалось скачать: ${reasonOf(it)}"
                    _state.value = UpdateState.Failed(release, asset, text, canInstall = false)
                }
        }
    }

    private suspend fun fetchApk(release: ReleaseInfo, asset: ReleaseAsset): File {
        val dir = updateDir()
        dir.deleteRecursively()
        if (!dir.mkdirs() && !dir.isDirectory) throw UpdateFailure("Не удалось сохранить файл обновления")
        val target = apkFile(asset)
        val part = File(dir, target.name + ".part")
        val connection = open(asset.url).apply { readTimeout = 60_000 }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateFailure("GitHub ответил $code")
            // Redirects (to GitHub's file storage) are followed only https → https.
            if (connection.url.protocol != "https") throw UpdateFailure("Небезопасная ссылка на файл")
            val declared = connection.contentLengthLong
            val total = if (declared > 0) declared else asset.sizeBytes
            if (total > MAX_APK_BYTES) throw UpdateFailure("Файл обновления слишком большой")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastShown = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (done > MAX_APK_BYTES) throw UpdateFailure("Файл обновления слишком большой")
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastShown >= 150 && _state.value is UpdateState.Downloading) {
                            lastShown = now
                            _state.value = UpdateState.Downloading(release, asset, done, total)
                        }
                    }
                }
            }
            if (declared > 0 && done != declared) throw UpdateFailure("Загрузка оборвалась — попробуйте ещё раз")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (asset.sha256 != null && hash != asset.sha256) {
                throw UpdateFailure("Файл скачался с ошибкой — попробуйте ещё раз")
            }
            if (!part.renameTo(target)) throw UpdateFailure("Не удалось сохранить файл обновления")
            return target
        } finally {
            connection.disconnect()
            if (part.exists()) part.delete()
        }
    }

    /** What is wrong with the downloaded file, or null when it is this app, not older. */
    private fun verify(file: File): String? {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: return "Файл обновления повреждён — попробуйте ещё раз"
        if (info.packageName != context.packageName) {
            return if (isTestBuild) {
                "Это тестовая сборка: файл с GitHub — обновление обычной OmniVPN. Тестовую сборку обновляют через компьютер."
            } else {
                "Файл обновления не от OmniVPN"
            }
        }
        if (PackageInfoCompat.getLongVersionCode(info) < currentVersionCode) {
            return "Файл на GitHub старее установленной версии"
        }
        return null
    }

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system page «Установка неизвестных приложений» for OmniVPN. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Back in the app: the permission may have just been granted, and a process
     * that stayed in memory for a day gets the launch check it missed.
     */
    fun onAppResumed() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && canInstallPackages()) {
            install(s.release, s.asset)
            return
        }
        if (isTestBuild || _checking.value) return
        if (s is UpdateState.Downloading || s is UpdateState.NeedsPermission || s is UpdateState.Installing) return
        if (!updateCheckDue(store.lastCheckMs, System.currentTimeMillis())) return
        scope.launch { check() }
    }

    private fun install(release: ReleaseInfo, asset: ReleaseAsset) {
        val file = apkFile(asset)
        if (!file.exists()) {
            download(release, asset)
            return
        }
        if (!canInstallPackages()) {
            _state.value = UpdateState.NeedsPermission(release, asset)
            return
        }
        _state.value = UpdateState.Installing(release, asset)
        store.installingVersion = release.version
        store.saveReconnect(reconnectTarget())
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { commitSession(file) } }.onFailure {
                android.util.Log.e(TAG, "install: session failed", it)
                forgetInstall()
                _state.value = UpdateState.Failed(release, asset, "Не удалось начать установку", canInstall = true)
            }
        }
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("OmniVPN.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_STATUS)
                // The installer fills in the status extras, so it must be mutable.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw t
        }
    }

    /** From UpdateInstallReceiver: what the system installer did with the session. */
    fun onInstallStatus(status: Int, confirm: Intent?) {
        val s = _state.value
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm != null) _confirmation.value = confirm
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually never seen: the update restarts the app first.
                _state.value = UpdateState.Idle
            }
            else -> {
                forgetInstall()
                val release = s.release ?: return
                val asset = s.asset ?: return
                val (text, canInstall) = installFailure(status)
                _state.value = UpdateState.Failed(release, asset, text, canInstall)
            }
        }
    }

    /** The activity showed (or failed to show) the confirmation. */
    fun onConfirmationShown(failed: Boolean) {
        _confirmation.value = null
        if (failed) {
            val s = _state.value as? UpdateState.Installing ?: return
            forgetInstall()
            _state.value = UpdateState.Failed(s.release, s.asset, "Не удалось открыть установщик Android", canInstall = true)
        }
    }

    private fun installFailure(status: Int): Pair<String, Boolean> = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена" to true
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android не разрешил установку" to true
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места для установки" to true
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Обновление не встаёт поверх этой версии: другая подпись" to false
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Обновление не подходит для этого телефона" to false
        PackageInstaller.STATUS_FAILURE_INVALID -> "Файл обновления повреждён" to false
        else -> "Не удалось установить обновление" to true
    }

    private fun forgetInstall() {
        store.installingVersion = null
        store.saveReconnect(null)
    }

    /** «OmniVPN обновлён до версии 1.3.0» once, on the first start after an update. */
    fun takeUpdatedNotice(): String? {
        val version = store.installingVersion ?: return null
        store.installingVersion = null
        return if (version == currentVersion) "OmniVPN обновлён до версии $version" else null
    }

    // ---- After the update ----

    private fun reconnectTarget(): ReconnectTarget? {
        val up = eventBus.state.value == VpnState.CONNECTED || eventBus.state.value == VpnState.CONNECTING
        if (!up) return null
        return when (val active = connectionManager.activeConnection.value) {
            is ActiveConnection.Server -> active.node.id.takeIf { it != 0L }?.let { ReconnectTarget.Server(it) }
            ActiveConnection.Warp -> ReconnectTarget.Warp
            ActiveConnection.None -> null
        }
    }

    /** From PackageReplacedReceiver: bring back what was connected before the update. */
    suspend fun reconnectAfterUpdate() {
        val target = store.takeReconnect() ?: return
        if (VpnService.prepare(context) != null) return
        val state = eventBus.state.value
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) return
        android.util.Log.i(TAG, "reconnectAfterUpdate: $target")
        val result = when (target) {
            ReconnectTarget.Warp -> connectionManager.connectWarp()
            is ReconnectTarget.Server -> {
                val node = runCatching { nodeRepository.nodes.first() }.getOrDefault(emptyList())
                    .firstOrNull { it.id == target.nodeId } ?: return
                connectionManager.connectNode(node)
            }
        }
        result.onFailure { android.util.Log.w(TAG, "reconnectAfterUpdate failed", it) }
    }

    // ---- Plumbing ----

    private fun updateDir() = File(context.cacheDir, "update")

    private fun apkFile(asset: ReleaseAsset) =
        File(updateDir(), asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        useCaches = false
        setRequestProperty("User-Agent", "OmniVPN/$currentVersion (Android)")
    }

    private fun readText(connection: HttpURLConnection): String {
        val out = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_JSON_BYTES) throw UpdateFailure("Ответ GitHub слишком большой")
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun reasonOf(t: Throwable): String = when (t) {
        is UpdateFailure -> t.message.orEmpty()
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "нет связи с GitHub"
        is java.net.SocketTimeoutException -> "GitHub не ответил вовремя"
        is javax.net.ssl.SSLException -> "не удалось установить защищённое соединение"
        is IOException -> "ошибка сети"
        else -> "непредвиденная ошибка"
    }

    private companion object {
        const val TAG = "AppUpdater"
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val MAX_JSON_BYTES = 1024 * 1024
    }
}
