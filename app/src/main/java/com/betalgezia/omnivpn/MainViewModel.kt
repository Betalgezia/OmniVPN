package com.betalgezia.omnivpn

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betalgezia.omnivpn.data.NodeImportService
import com.betalgezia.omnivpn.data.NodeRepository
import com.betalgezia.omnivpn.data.SubscriptionAutoUpdateScheduler
import com.betalgezia.omnivpn.data.SubscriptionRepository
import com.betalgezia.omnivpn.data.hasDomainWireguardEndpoint
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.ConnectionManager
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.TrafficStatus
import com.betalgezia.omnivpn.vpn.TunnelStats
import com.betalgezia.omnivpn.vpn.NodeHealthChecker
import com.betalgezia.omnivpn.vpn.TestMode
import com.betalgezia.omnivpn.vpn.VpnController
import com.betalgezia.omnivpn.vpn.VpnState
import com.betalgezia.omnivpn.vpn.AwgObfuscation
import com.betalgezia.omnivpn.vpn.TuneState
import com.betalgezia.omnivpn.vpn.WarpAccount
import com.betalgezia.omnivpn.vpn.WarpEndpointScanner
import com.betalgezia.omnivpn.vpn.WarpObfuscationStore
import com.betalgezia.omnivpn.vpn.WarpObfuscationTuner
import com.betalgezia.omnivpn.vpn.WarpSettings
import com.betalgezia.omnivpn.vpn.WarpStorage
import com.betalgezia.omnivpn.vpn.SubscriptionParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
    private val nodeImportService: NodeImportService,
    private val subscriptionRepository: SubscriptionRepository,
    private val vpnController: VpnController,
    private val connectionManager: ConnectionManager,
    private val nodeHealthChecker: NodeHealthChecker,
    private val warpEndpointScanner: WarpEndpointScanner,
    private val warpSettings: WarpSettings,
    private val warpObfuscationTuner: WarpObfuscationTuner,
    private val warpObfuscationStore: WarpObfuscationStore,
    private val autoUpdateScheduler: SubscriptionAutoUpdateScheduler,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    init {
        viewModelScope.launch {
            runCatching { nodeRepository.removeLegacyDemoNode() }
        }
        // Make the persisted auto-update choice take effect on launch:
        // (re)schedule when enabled (idempotent), cancel when off. References
        // only constructor params, so it's safe in this first init block.
        if (warpSettings.autoUpdateEnabled) {
            autoUpdateScheduler.schedule(warpSettings.autoUpdateIntervalMinutes)
        } else {
            autoUpdateScheduler.cancel()
        }
    }

    val nodes = nodeRepository.nodes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // User-chosen subscription order (persisted in WarpSettings). Declared
    // before `subscriptions` because that flow reads it during construction.
    private val _subscriptionOrder = MutableStateFlow(warpSettings.subscriptionOrder)

    // Everything downstream - main-screen groups, the Settings list,
    // refreshAll - sees subscriptions in the user's order, not the database's.
    val subscriptions = combine(subscriptionRepository.subscriptions, _subscriptionOrder) { subs, order ->
        orderSubscriptions(subs, order)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val vpnState: StateFlow<VpnState> = vpnController.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _nodeHealth = MutableStateFlow<Map<Long, NodeHealth>>(emptyMap())
    val nodeHealth: StateFlow<Map<Long, NodeHealth>> = _nodeHealth.asStateFlow()

    // Owned by ConnectionManager (app-wide), so a tunnel started from the QS
    // tile shows up here with its name too.
    val activeConnection: StateFlow<ActiveConnection> = connectionManager.activeConnection

    // Whether traffic actually flows through the tunnel (post-connect probe).
    val trafficStatus: StateFlow<TrafficStatus> = vpnController.traffic

    // Home screen counters (speed from the core's counters, latency from the
    // last passed check) and why the last start failed.
    val tunnelStats: StateFlow<TunnelStats> = vpnController.stats
    val lastError: StateFlow<String?> = vpnController.lastError

    private val _quickTileStartsWarp = MutableStateFlow(warpSettings.quickTileStartsWarp)
    val quickTileStartsWarp: StateFlow<Boolean> = _quickTileStartsWarp.asStateFlow()

    fun setQuickTileStartsWarp(startsWarp: Boolean) {
        warpSettings.quickTileStartsWarp = startsWarp
        _quickTileStartsWarp.value = startsWarp
    }

    // Auto-switch to another server of the same subscription when the current
    // one stops passing traffic (see ConnectionManager.onTunnelDead).
    private val _autoSwitchEnabled = MutableStateFlow(warpSettings.autoSwitchEnabled)
    val autoSwitchEnabled: StateFlow<Boolean> = _autoSwitchEnabled.asStateFlow()

    fun setAutoSwitchEnabled(enabled: Boolean) {
        warpSettings.autoSwitchEnabled = enabled
        _autoSwitchEnabled.value = enabled
    }

    // Leave a server whose real downloads stay below ~3 Mbit/s for a faster
    // one of the same subscription (see ConnectionManager.onSlow).
    private val _speedSwitchEnabled = MutableStateFlow(warpSettings.speedSwitchEnabled)
    val speedSwitchEnabled: StateFlow<Boolean> = _speedSwitchEnabled.asStateFlow()

    fun setSpeedSwitchEnabled(enabled: Boolean) {
        warpSettings.speedSwitchEnabled = enabled
        _speedSwitchEnabled.value = enabled
    }

    // True only while connectFastest() is probing - distinct from _busy (which
    // is also true during a manual "Check all"/WARP endpoint search) so the
    // hero button can show "Searching…" only for its own action.
    private val _searchingFastest = MutableStateFlow(false)
    val searchingFastest: StateFlow<Boolean> = _searchingFastest.asStateFlow()

    // True while startWarp() registers / starts WARP: the tunnel only reports
    // CONNECTING once the service starts, after the (seconds-long) Cloudflare
    // registration, so the home screen needs this to say "Подключаем WARP…".
    private val _warpStarting = MutableStateFlow(false)
    val warpStarting: StateFlow<Boolean> = _warpStarting.asStateFlow()

    // Same idea as _searchingFastest, for the small "full test" icon next to
    // the main screen's "Servers" label (see the September 2026 redesign) -
    // distinct from _busy so that icon's own spinner only shows for its own
    // action, not e.g. while a subscription is being added.
    private val _testingAllFull = MutableStateFlow(false)
    val testingAllFull: StateFlow<Boolean> = _testingAllFull.asStateFlow()

    // Server tests and the WARP endpoint search share this: both drive the one
    // sing-box engine, so they can never overlap anyway, and both can run long
    // enough that the user needs a way out.
    private var testJob: Job? = null

    // Persisted (WarpSettings.testMode). It used to reset to FULL on every
    // launch, so after a restart - including every reinstall - the per-server
    // "Тест" silently ran the slow full test even with "Быстрая" picked.
    private val _testMode = MutableStateFlow(warpSettings.testMode)
    val testMode: StateFlow<TestMode> = _testMode.asStateFlow()

    fun setTestMode(mode: TestMode) {
        warpSettings.testMode = mode
        _testMode.value = mode
    }

    // Persisted via WarpSettings - see its own comment for why this needs to
    // survive app restarts.
    private val _ipv6Enabled = MutableStateFlow(warpSettings.ipv6Enabled)
    val ipv6Enabled: StateFlow<Boolean> = _ipv6Enabled.asStateFlow()

    fun setIpv6Enabled(enabled: Boolean) {
        warpSettings.ipv6Enabled = enabled
        _ipv6Enabled.value = enabled
    }

    // ---- Nocturne v2 redesign additions ----

    // Subscription ids currently refreshing, for the per-group ⟳ spinner and
    // the "Обновить" (refresh-all) header icon. Refresh only does HTTP+parse,
    // never touches the sing-box engine, so it doesn't gate on _busy and the
    // rest of the UI stays usable while a refresh runs.
    private val _refreshingSubscriptions = MutableStateFlow<Set<Long>>(emptySet())
    val refreshingSubscriptions: StateFlow<Set<Long>> = _refreshingSubscriptions.asStateFlow()

    // Instant we entered CONNECTED (see the trailing init) - the session timer.
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    private val _autoUpdateEnabled = MutableStateFlow(warpSettings.autoUpdateEnabled)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()
    private val _autoUpdateIntervalMinutes = MutableStateFlow(warpSettings.autoUpdateIntervalMinutes)
    val autoUpdateIntervalMinutes: StateFlow<Int> = _autoUpdateIntervalMinutes.asStateFlow()

    fun setAutoUpdateEnabled(enabled: Boolean) {
        warpSettings.autoUpdateEnabled = enabled
        _autoUpdateEnabled.value = enabled
        if (enabled) autoUpdateScheduler.schedule(_autoUpdateIntervalMinutes.value)
        else autoUpdateScheduler.cancel()
    }

    fun setAutoUpdateInterval(minutes: Int) {
        warpSettings.autoUpdateIntervalMinutes = minutes
        _autoUpdateIntervalMinutes.value = minutes
        if (_autoUpdateEnabled.value) autoUpdateScheduler.schedule(minutes)
    }

    // Obfuscation autotuner state + the current profile summary shown in the
    // Settings sheet's WARP section.
    private val _tuneState = MutableStateFlow<TuneState>(TuneState.Idle)
    val tuneState: StateFlow<TuneState> = _tuneState.asStateFlow()
    private val _currentObfuscation = MutableStateFlow(warpObfuscationStore.currentOrDefault())
    val currentObfuscation: StateFlow<AwgObfuscation> = _currentObfuscation.asStateFlow()
    private val _obfuscationIsCustom = MutableStateFlow(warpObfuscationStore.load() != null)
    val obfuscationIsCustom: StateFlow<Boolean> = _obfuscationIsCustom.asStateFlow()
    private var tuneJob: Job? = null

    private fun refreshObfuscationState() {
        _currentObfuscation.value = warpObfuscationStore.currentOrDefault()
        _obfuscationIsCustom.value = warpObfuscationStore.load() != null
    }

    /**
     * Manual obfuscation autotune (see WarpObfuscationTuner). Requires the VPN
     * to be disconnected for the same single-engine reason as every other
     * probe (canTestNow), and shares _busy so the hero/tests can't run at once.
     */
    fun startTune() {
        if (!canTestNow()) {
            _message.value = "Disconnect the VPN before tuning obfuscation"
            return
        }
        val account = WarpStorage(appContext).get()
        if (account == null) {
            _message.value = "Register WARP first (tap Cloudflare WARP), then tune"
            return
        }
        tuneJob?.cancel()
        // Placeholder so the sheet switches to the "running" UI immediately;
        // the first real emit replaces it with an accurate step/total.
        _tuneState.value = TuneState.Running(0, 15, emptyList(), 0)
        tuneJob = viewModelScope.launch {
            _busy.value = true
            try {
                warpObfuscationTuner.tune(account, warpSettings.ipv6Enabled).collect { state ->
                    _tuneState.value = state
                }
            } catch (cancelled: CancellationException) {
                _tuneState.value = TuneState.Idle
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "startTune: failed", t)
                _tuneState.value = TuneState.Idle
                _message.value = t.message ?: "Tuning failed"
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancelTune() {
        tuneJob?.cancel()
        tuneJob = null
        _tuneState.value = TuneState.Idle
        _message.value = "Tuning stopped - parameters unchanged"
    }

    fun applyTune() {
        val found = _tuneState.value as? TuneState.Found ?: return
        viewModelScope.launch {
            warpObfuscationTuner.apply(found.best)
            refreshObfuscationState()
            _tuneState.value = TuneState.Idle
            _message.value = "Obfuscation applied for this network"
        }
    }

    fun dismissTune() { _tuneState.value = TuneState.Idle }

    fun resetObfuscation() {
        warpObfuscationTuner.reset()
        refreshObfuscationState()
        _tuneState.value = TuneState.Idle
        _message.value = "Reverted to default obfuscation"
    }

    fun refreshAll() {
        val subs = subscriptions.value
        if (subs.isEmpty()) return
        viewModelScope.launch {
            _refreshingSubscriptions.update { current -> current + subs.map { it.id } }
            var failures = 0
            for (subscription in subs) {
                runCatching { subscriptionRepository.refresh(subscription) }
                    .onFailure {
                        failures++
                        android.util.Log.e(TAG, "refreshAll: failed id=${subscription.id}", it)
                    }
                _refreshingSubscriptions.update { current -> current - subscription.id }
            }
            _message.value = when {
                failures == 0 -> "Подписки обновлены"
                failures == subs.size -> "Не удалось обновить подписки"
                else -> "Подписки обновлены, кроме $failures из ${subs.size}"
            }
        }
    }

    // ---- Subscription groups: collapse, order, per-group tests ----

    private val _collapsedSubscriptions = MutableStateFlow(warpSettings.collapsedSubscriptionIds)
    val collapsedSubscriptions: StateFlow<Set<Long>> = _collapsedSubscriptions.asStateFlow()

    fun toggleSubscriptionCollapsed(subscription: Subscription) {
        val current = _collapsedSubscriptions.value
        val next = if (subscription.id in current) current - subscription.id else current + subscription.id
        warpSettings.collapsedSubscriptionIds = next
        _collapsedSubscriptions.value = next
    }

    /** Moves [subscription] one place up ([delta] = -1) or down (+1). */
    fun moveSubscription(subscription: Subscription, delta: Int) {
        val ids = subscriptions.value.map { it.id }.toMutableList()
        val from = ids.indexOf(subscription.id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.removeAt(from)
        ids.add(to, subscription.id)
        warpSettings.subscriptionOrder = ids
        _subscriptionOrder.value = ids
    }

    private fun orderSubscriptions(subs: List<Subscription>, order: List<Long>): List<Subscription> {
        if (order.isEmpty()) return subs
        val rank = order.withIndex().associate { (index, id) -> id to index }
        // sortedBy is stable: subscriptions not in the saved order (added
        // since) keep their database order, after the ordered ones.
        return subs.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    // Which subscription - and in which mode - its own group quick/full button
    // is testing right now, so only that one button shows a spinner.
    private val _subscriptionTest = MutableStateFlow<Pair<Long, TestMode>?>(null)
    val subscriptionTest: StateFlow<Pair<Long, TestMode>?> = _subscriptionTest.asStateFlow()

    /**
     * The quick / full buttons on each main-screen subscription group: tests
     * just that subscription's servers in the given mode, whatever the Settings
     * toggle says (that toggle stays the default for the per-server "Тест").
     */
    fun testSubscription(subscription: Subscription, mode: TestMode) {
        val targets = nodes.value.filter { it.sourceId == subscription.id }
        runNodeCheck(targets, mode, subscriptionTag = subscription.id to mode)
    }

    // Session-timer source: stamped in a trailing init (after the backing
    // field above is declared, so no forward reference).
    init {
        viewModelScope.launch {
            vpnController.state.collect { state ->
                _connectedSince.value = if (state == VpnState.CONNECTED) {
                    _connectedSince.value ?: System.currentTimeMillis()
                } else {
                    null
                }
            }
        }
        // Auto-switch progress / "WARP is down" from ConnectionManager.
        viewModelScope.launch {
            connectionManager.notices.collect { _message.value = it }
        }
    }

    fun consumeMessage() { _message.value = null }
    fun showMessage(message: String) { _message.value = message }
    fun prepareVpn(): Intent? = vpnController.prepareIntent()

    /**
     * Connects to [node] - or, if a tunnel is already up, switches the running
     * tunnel over to it (the service hot-reloads the new config, see
     * OmniVpnService.reloadVpn), with no manual disconnect in between.
     */
    fun connect(node: Node) {
        val switching = vpnState.value == VpnState.CONNECTED
        vpnController.clearError()
        viewModelScope.launch {
            _busy.value = true
            connectionManager.connectNode(node).onFailure {
                android.util.Log.e(TAG, "connect: failed", it)
                _message.value = it.message ?: "Не удалось запустить VPN"
            }.onSuccess {
                if (switching) _message.value = "Переключаемся на «${node.name}»"
            }
            _busy.value = false
        }
    }

    fun stop() {
        vpnController.clearError()
        connectionManager.stop()
    }

    // True while the «Добавить сервер» sheet is loading a subscription / importing.
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /**
     * «Добавить сервер» → Подписка. [onResult] gets null on success (the
     * snackbar says what was added) or the error to show in the sheet.
     */
    fun addSubscription(url: String, onResult: (String?) -> Unit) = runAdd("subscription", onResult) {
        val value = url.trim()
        // Blank name: add() falls back to the URL's own host as the name (see
        // its doc), so subscriptions don't all end up called "Subscription".
        val id = subscriptionRepository.add("", value)
        val parsed = subscriptionRepository.refresh(Subscription(id = id, name = "", url = value, enabled = true))
        withNotes("Подписка добавлена · ${parsed.nodes.size} серв.", parsed.skipped, hasDomainWireguardEndpoint(parsed.nodes))
    }

    /** «Добавить сервер» → Конфигурация / Файл: links, JSON or YAML; [fileName] names a picked file. */
    fun importConfig(text: String, fileName: String? = null, onResult: (String?) -> Unit) = runAdd("config", onResult) {
        val result = nodeImportService.importText(text.trim())
        val base = if (fileName != null) {
            "«$fileName» импортирован · ${result.nodes.size} серв."
        } else {
            "Конфигурация импортирована · ${result.nodes.size} серв."
        }
        withNotes(base, 0, result.domainWireguardEndpoint)
    }

    private fun runAdd(what: String, onResult: (String?) -> Unit, block: suspend () -> String) {
        if (_adding.value) return
        viewModelScope.launch {
            _adding.value = true
            runCatching { block() }
                .onSuccess {
                    _message.value = it
                    onResult(null)
                }
                .onFailure {
                    // Logged explicitly: the repositories never log, so without this
                    // a failed add left nothing in logcat.
                    android.util.Log.e(TAG, "add $what: failed", it)
                    onResult(addErrorText(it))
                }
            _adding.value = false
        }
    }

    /** One-shot import from the legacy settings sheet: errors go to the snackbar. */
    fun import(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val toSnackbar: (String?) -> Unit = { error -> if (error != null) _message.value = error }
        if (value.startsWith("https://", ignoreCase = true)) addSubscription(value, toSnackbar)
        else importConfig(value, onResult = toSnackbar)
    }

    fun refresh(subscription: Subscription) {
        viewModelScope.launch {
            // Per-subscription spinner rather than the global _busy: a refresh
            // is only HTTP+parse (no sing-box engine), so the hero/tests stay
            // usable and just this row's ⟳ spins (see refreshingSubscriptions).
            _refreshingSubscriptions.update { it + subscription.id }
            runCatching { subscriptionRepository.refresh(subscription) }
                .onSuccess { parsed ->
                    _message.value = withNotes(
                        "«${subscription.name}» обновлена · ${parsed.nodes.size} серв.",
                        parsed.skipped,
                        hasDomainWireguardEndpoint(parsed.nodes)
                    )
                }
                .onFailure {
                    android.util.Log.e(TAG, "refresh: failed for subscription id=${subscription.id}", it)
                    _message.value = "Не удалось обновить «${subscription.name}»: ${addErrorText(it)}"
                }
            _refreshingSubscriptions.update { it - subscription.id }
        }
    }

    fun delete(subscription: Subscription) {
        viewModelScope.launch {
            runCatching { subscriptionRepository.delete(subscription) }.onFailure {
                android.util.Log.e(TAG, "delete: failed for subscription id=${subscription.id}", it)
                _message.value = it.message
            }
        }
    }

    fun renameSubscription(subscription: Subscription, name: String) {
        viewModelScope.launch {
            runCatching { subscriptionRepository.rename(subscription, name) }.onFailure {
                android.util.Log.e(TAG, "renameSubscription: failed for id=${subscription.id}", it)
                _message.value = "Не удалось переименовать подписку"
            }
        }
    }

    fun deleteNode(node: Node) {
        viewModelScope.launch {
            runCatching { nodeRepository.delete(node) }
                .onSuccess {
                    _nodeHealth.update { it - node.id }
                    _message.value = "«${node.name}» удалён"
                }
                .onFailure {
                    android.util.Log.e(TAG, "deleteNode: failed for node id=${node.id}", it)
                    _message.value = "Не удалось удалить «${node.name}»"
                }
        }
    }

    fun checkNode(node: Node) = checkNodes(listOf(node))

    fun checkNodes(targets: List<Node>) = runNodeCheck(targets, _testMode.value, subscriptionTag = null)

    /**
     * «Проверить все» on the Servers tab: every server (subscriptions and
     * imported) in the Settings test mode. Tagged [ALL_SERVERS] in
     * [subscriptionTest] so that button can say «Проверка…».
     */
    fun testAll() = runNodeCheck(nodes.value, _testMode.value, subscriptionTag = ALL_SERVERS to _testMode.value)

    /**
     * Shared by the per-server "Тест" (Settings test mode) and the per-group
     * quick/full buttons (explicit mode). [subscriptionTag] marks which group
     * button started it, for that button's spinner.
     */
    private fun runNodeCheck(targets: List<Node>, mode: TestMode, subscriptionTag: Pair<Long, TestMode>?) {
        // Testing runs a real (if TUN-less) sing-box session on the same engine the
        // actual VPN connection uses - see NodeHealthChecker. They can't run at once.
        if (!canTestNow()) {
            _message.value = TEST_LOCKED_MESSAGE
            return
        }
        val testable = targets.filter { it.id != 0L && it.protocol != Protocol.WARP }
        if (testable.isEmpty()) return
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _subscriptionTest.value = subscriptionTag
            _nodeHealth.update { current -> current + testable.associate { it.id to NodeHealth.Checking } }
            try {
                nodeHealthChecker.checkAll(testable, mode = mode) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                }
            } catch (cancelled: CancellationException) {
                _message.value = "Проверка остановлена"
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "checkNodes: failed", t)
                _message.value = t.message ?: "Проверка не удалась"
            } finally {
                // Cancelling leaves nodes mid-probe: drop their "Testing…" label
                // instead of stranding it, and clear busy here rather than after
                // the call, which a cancellation would skip straight past and
                // leave the whole UI disabled.
                _nodeHealth.update { current -> current.filterValues { it != NodeHealth.Checking } }
                // Only clear the group spinner if it's still ours - a newer
                // run may already have tagged a different button.
                if (_subscriptionTest.value == subscriptionTag) _subscriptionTest.value = null
                _busy.value = false
            }
        }
    }

    /** Stops an in-flight server test or WARP endpoint search. */
    fun cancelTests() {
        testJob?.cancel()
        testJob = null
    }

    fun checkAllNodes() = checkNodes(nodes.value)

    fun checkSubscriptionNodes(subscription: Subscription) =
        checkNodes(nodes.value.filter { it.sourceId == subscription.id })

    /**
     * Tests every server that came from a subscription - never a manually
     * pasted/imported config, never WARP, see the September 2026 redesign -
     * then connects to whichever one answers fastest: the hero "Connect"
     * button's one-tap promise. Always probes in TestMode.QUICK regardless of
     * the _testMode toggle: that toggle is for the deliberate, thorough
     * per-node "Test" tap, while this action's whole point is to be a fast
     * default. Shares testJob with checkNodes()/findWarpEndpoint() since all
     * three drive the same single sing-box probe engine (see
     * NodeHealthChecker) and can never run at once; canTestNow() applies here
     * for the same reason.
     */
    fun connectFastest() {
        if (!canTestNow()) {
            _message.value = "Отключите VPN, чтобы искать самый быстрый сервер"
            return
        }
        val candidates = nodes.value.filter { it.id != 0L && it.sourceId != null && it.protocol != Protocol.WARP }
        if (candidates.isEmpty()) {
            _message.value = "Нет серверов из подписок — добавьте подписку"
            return
        }
        vpnController.clearError()
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _searchingFastest.value = true
            _nodeHealth.update { current -> current + candidates.associate { it.id to NodeHealth.Checking } }
            try {
                // Same search the QS tile uses (ConnectionManager.findFastest);
                // this one also feeds every result into the server list.
                val found = connectionManager.findFastest(candidates) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                }
                if (found == null) {
                    _message.value = "Ни один сервер не ответил"
                } else {
                    val (winner, bestLatencyMs) = found
                    connectionManager.connectNode(winner).onFailure {
                        android.util.Log.e(TAG, "connectFastest: connect failed", it)
                        _message.value = it.message ?: "Не удалось запустить VPN"
                    }.onSuccess {
                        _message.value = "Подключено к «${winner.name}» · $bestLatencyMs мс"
                    }
                }
            } catch (cancelled: CancellationException) {
                _message.value = "Поиск отменён"
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "connectFastest: failed", t)
                _message.value = t.message ?: "Поиск не удался"
            } finally {
                _nodeHealth.update { current -> current.filterValues { it != NodeHealth.Checking } }
                _searchingFastest.value = false
                _busy.value = false
            }
        }
    }

    /**
     * Tests every subscription server in TestMode.FULL regardless of the
     * _testMode toggle - the small icon next to the main screen's "Servers"
     * label (see the September 2026 redesign). FULL is hardcoded here for
     * the mirror-image reason connectFastest() hardcodes QUICK: that button's
     * whole point is a fast default, this one's whole point is a thorough
     * check without having to first go into Settings and flip the toggle.
     * Shares testJob/canTestNow() with the other test actions for the same
     * single-engine reason documented on connectFastest().
     */
    fun testAllSubscriptionsFull() {
        if (!canTestNow()) {
            _message.value = TEST_LOCKED_MESSAGE
            return
        }
        val candidates = nodes.value.filter { it.id != 0L && it.sourceId != null && it.protocol != Protocol.WARP }
        if (candidates.isEmpty()) {
            _message.value = "No subscription servers yet - add a subscription first"
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _testingAllFull.value = true
            _nodeHealth.update { current -> current + candidates.associate { it.id to NodeHealth.Checking } }
            try {
                nodeHealthChecker.checkAll(candidates, mode = TestMode.FULL) { node, health ->
                    _nodeHealth.update { it + (node.id to health) }
                }
            } catch (cancelled: CancellationException) {
                _message.value = "Проверка остановлена"
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "testAllSubscriptionsFull: failed", t)
                _message.value = t.message ?: "Проверка не удалась"
            } finally {
                _nodeHealth.update { current -> current.filterValues { it != NodeHealth.Checking } }
                _testingAllFull.value = false
                _busy.value = false
            }
        }
    }

    /**
     * Tries WARP's anycast endpoints until one actually carries traffic and saves
     * it - the endpoint that works is network-dependent and changes, and typing
     * candidates into the override field by hand was the only way to find one.
     */
    fun findWarpEndpoint() {
        // Same single-engine constraint as the server test - see NodeHealthChecker.
        if (!canTestNow()) {
            _message.value = "Disconnect the VPN before searching for a WARP endpoint"
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _busy.value = true
            _message.value = "Trying WARP endpoints…"
            try {
                warpEndpointScanner.findWorkingEndpoint()
                    .onSuccess {
                        _message.value = "WARP endpoint ${it.endpoint} works (${it.latencyMs} ms) and is now saved"
                    }
                    .onFailure {
                        android.util.Log.e(TAG, "findWarpEndpoint: failed", it)
                        _message.value = it.message ?: "No working WARP endpoint found"
                    }
            } catch (cancelled: CancellationException) {
                _message.value = "Endpoint search cancelled"
                throw cancelled
            } finally {
                _busy.value = false
            }
        }
    }

    private fun canTestNow(): Boolean = when (vpnState.value) {
        VpnState.DISCONNECTED, VpnState.ERROR, VpnState.REVOKED -> true
        else -> false
    }

    fun startWarp(endpointOverride: String? = null, forceNew: Boolean = false) {
        vpnController.clearError()
        viewModelScope.launch {
            _busy.value = true
            _warpStarting.value = true
            // The override was easy to lose track of - no visible confirmation
            // of which endpoint actually got used, so it was unclear from the
            // UI alone whether a typed-in override took effect (see the
            // September 2026 endpoint-rotation debugging session). Echo it
            // back in the result message instead of just "WARP registered".
            val normalizedEndpoint = endpointOverride?.trim()?.takeIf { it.isNotEmpty() }
            // forceNew discards the cached account and registers a fresh one
            // with Cloudflare - see VpnController.startWarp. Surfaced from the
            // UI as a "Reset WARP account" action for the case where the
            // saved account/keys stop working server-side; normal "Get WARP"
            // clicks always reuse the cached account instead of gambling on a
            // new one each time.
            connectionManager.connectWarp(endpoint = normalizedEndpoint, forceNew = forceNew).onFailure {
                android.util.Log.e(TAG, "startWarp: failed (forceNew=$forceNew)", it)
                _message.value = it.message ?: "Не удалось подключить WARP"
            }
                .onSuccess {
                    // A plain one-tap WARP needs no message - the hero and the
                    // WARP button already say it. Echo only the non-default parts.
                    when {
                        forceNew -> "Новый аккаунт WARP зарегистрирован · endpoint ${normalizedEndpoint ?: WarpAccount.DEFAULT_ENDPOINT}"
                        normalizedEndpoint != null -> "WARP · endpoint $normalizedEndpoint"
                        else -> null
                    }?.let { _message.value = it }
                }
            _warpStarting.value = false
            _busy.value = false
        }
    }

    private companion object {
        private const val TAG = "MainViewModel"

        private const val TEST_LOCKED_MESSAGE = "Отключите VPN, чтобы проверить серверы"

        /**
         * [base] plus two warnings when they apply: a list longer than
         * SubscriptionParser.MAX_NODES_PER_LIST keeps only its first nodes
         * (say so, or the cut list looks like the whole subscription), and an
         * AmneziaWG/WireGuard peer given by domain name rather than IP - see
         * ConfigParser.isLiteralIpHost and WarpAccount.DEFAULT_ENDPOINT for
         * the confirmed sing-box-lx bug that warns about.
         */
        fun withNotes(base: String, skipped: Int, domainEndpoint: Boolean): String = buildString {
            append(base)
            if (skipped > 0) {
                append("\nВ списке больше ${SubscriptionParser.MAX_NODES_PER_LIST} серверов: остальные $skipped не загружены.")
            }
            if (domainEndpoint) {
                append(
                    "\nАдрес сервера AmneziaWG — доменное имя, а не IP. Из-за известной ошибки sing-box " +
                        "рукопожатие в некоторых сетях не проходит; если не подключается, укажите IP."
                )
            }
        }
    }
}

/** [MainViewModel.subscriptionTest] tag of «Проверить все» (no real subscription has id -1). */
const val ALL_SERVERS = -1L
