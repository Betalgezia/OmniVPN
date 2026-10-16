package com.betalgezia.omnivpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.ui.AppearanceSettings
import com.betalgezia.omnivpn.ui.ThemeChoice
import com.betalgezia.omnivpn.ui.organic.AppTab
import com.betalgezia.omnivpn.ui.organic.OrganicBottomNav
import com.betalgezia.omnivpn.ui.organic.OrganicSnackbarHost
import com.betalgezia.omnivpn.ui.organic.rememberOrganicSnackbarState
import com.betalgezia.omnivpn.ui.rememberVpnActions
import com.betalgezia.omnivpn.ui.screens.AddMode
import com.betalgezia.omnivpn.ui.screens.AddServerSheet
import com.betalgezia.omnivpn.ui.screens.HomeScreen
import com.betalgezia.omnivpn.ui.screens.OnboardingScreen
import com.betalgezia.omnivpn.ui.screens.ServersScreen
import com.betalgezia.omnivpn.ui.screens.SettingsPage
import com.betalgezia.omnivpn.ui.screens.SettingsPageScreen
import com.betalgezia.omnivpn.ui.screens.SettingsScreen
import com.betalgezia.omnivpn.ui.screens.SwitchJournalSheet
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicTheme
import com.betalgezia.omnivpn.vpn.VpnState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The app's one window, in the Organic design (October 2026 handoff): three
 * tabs - Главная, Серверы, Настройки (with its pages) - over a bottom bar,
 * plus the «Добавить сервер» sheet. The Quick Settings tile
 * (OmniVpnTileService) hands its "turn on" back here via [EXTRA_TILE_START]
 * when it needs the VPN consent dialog, which only an Activity can show.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Set when the QS tile bounced its "turn on" here because VPN consent was
    // needed; the shell runs the tile's action (WARP or the fastest server,
    // per Settings) through the consent flow.
    private val tileStart = mutableStateOf(false)

    // A tab to show, asked for by the notification («Сменить сервер»).
    private val openTab = mutableStateOf<String?>(null)

    @Inject lateinit var appearance: AppearanceSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_TILE_START, false) == true) tileStart.value = true
        openTab.value = intent?.getStringExtra(EXTRA_OPEN_TAB)
        setContent {
            val choice by appearance.theme.collectAsStateWithLifecycle()
            val dark = choice?.let { it == ThemeChoice.DARK } ?: isSystemInDarkTheme()
            OrganicTheme(dark = dark) { OrganicShell(tileStart = tileStart, dark = dark) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TILE_START, false)) tileStart.value = true
        intent.getStringExtra(EXTRA_OPEN_TAB)?.let { openTab.value = it }
    }

    @Composable
    private fun OrganicShell(
        tileStart: MutableState<Boolean>,
        dark: Boolean,
        viewModel: MainViewModel = hiltViewModel()
    ) {
        val c = Organic.colors
        var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
        var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
        var addSheet by rememberSaveable { mutableStateOf(false) }
        var addMode by remember { mutableStateOf<AddMode?>(null) }
        var journalSheet by rememberSaveable { mutableStateOf(false) }
        val message by viewModel.message.collectAsStateWithLifecycle()
        val journal by viewModel.switchJournal.collectAsStateWithLifecycle()
        val onboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()
        val actions = rememberVpnActions(viewModel)
        val snackbar = rememberOrganicSnackbarState()
        // Any tap anywhere hides the snackbar at once; the tap itself still
        // goes on to whatever was under the finger.
        val dismissSnackbarOnTap = Modifier.pointerInput(snackbar) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                snackbar.dismiss()
            }
        }

        LaunchedEffect(tileStart.value) {
            if (tileStart.value) {
                tileStart.value = false
                tab = AppTab.HOME
                page = null
                val idle = viewModel.vpnState.value.let {
                    it == VpnState.DISCONNECTED || it == VpnState.ERROR || it == VpnState.REVOKED
                }
                if (idle) {
                    if (viewModel.quickTileStartsWarp.value) actions.startWarp() else actions.connectFastest()
                }
            }
        }

        LaunchedEffect(openTab.value) {
            if (openTab.value == TAB_SERVERS) {
                tab = AppTab.SERVERS
                page = null
            }
            openTab.value = null
        }

        // First run: the three-step intro instead of the tabs (null = still
        // checking whether this is really a first run, see showOnboarding).
        when (onboarding) {
            null -> {
                Box(Modifier.fillMaxSize().background(c.bg))
                return
            }
            true -> {
                Box(Modifier.fillMaxSize().then(dismissSnackbarOnTap)) {
                    OnboardingScreen(viewModel, actions)
                    OrganicSnackbarHost(
                        message = message,
                        onShown = viewModel::consumeMessage,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                        state = snackbar
                    )
                }
                return
            }
            false -> Unit
        }

        // System back on a settings page returns to the settings list (the
        // sheet, when open, handles back itself).
        BackHandler(enabled = page != null && !addSheet && !journalSheet) { page = null }

        val openAdd: (AddMode?) -> Unit = { mode ->
            addMode = mode
            addSheet = true
        }

        Box(Modifier.fillMaxSize().background(c.bg).then(dismissSnackbarOnTap)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Keyed so every tab and page opens scrolled to the top.
                    key(tab, page) {
                        TabColumn {
                            when (tab) {
                                AppTab.HOME -> HomeScreen(
                                    viewModel = viewModel,
                                    actions = actions,
                                    onOpenServers = { tab = AppTab.SERVERS },
                                    onOpenJournal = { journalSheet = true }
                                )
                                AppTab.SERVERS -> ServersScreen(
                                    viewModel = viewModel,
                                    actions = actions,
                                    onAddServer = { openAdd(null) },
                                    onOpenHome = { tab = AppTab.HOME }
                                )
                                AppTab.SETTINGS -> {
                                    val open = page
                                    if (open != null) {
                                        SettingsPageScreen(
                                            page = open,
                                            viewModel = viewModel,
                                            actions = actions,
                                            onBack = { page = null },
                                            onAddSubscription = { openAdd(AddMode.SUBSCRIPTION) }
                                        )
                                    } else {
                                        SettingsScreen(
                                            viewModel = viewModel,
                                            theme = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT,
                                            onThemeChange = appearance::setTheme,
                                            onOpenPage = { page = it },
                                            onAddServer = { openAdd(null) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OrganicSnackbarHost(
                        message = message,
                        onShown = viewModel::consumeMessage,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                        state = snackbar
                    )
                }
                OrganicBottomNav(
                    selected = tab,
                    onSelect = {
                        tab = it
                        page = null
                    }
                )
            }
            AddServerSheet(
                visible = addSheet,
                viewModel = viewModel,
                onDismiss = { addSheet = false },
                onAdded = {
                    addSheet = false
                    tab = AppTab.SERVERS
                    page = null
                },
                startMode = addMode
            )
            SwitchJournalSheet(visible = journalSheet, events = journal, onDismiss = { journalSheet = false })
        }
    }

    /** A tab's scrolling column under the status bar. */
    @Composable
    private fun TabColumn(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) { content() }
    }

    companion object {
        const val EXTRA_TILE_START = "com.betalgezia.omnivpn.TILE_START"
        const val EXTRA_OPEN_TAB = "com.betalgezia.omnivpn.OPEN_TAB"
        const val TAB_SERVERS = "servers"
    }
}
