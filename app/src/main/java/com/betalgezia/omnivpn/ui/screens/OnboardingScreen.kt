package com.betalgezia.omnivpn.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.ui.VpnActions
import com.betalgezia.omnivpn.ui.organic.ButtonKind
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.OrganicTextField
import com.betalgezia.omnivpn.ui.organic.Tag
import com.betalgezia.omnivpn.ui.organic.TagTone
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicType

/**
 * First run (handoff §5): three steps without navigation - what the app is,
 * a subscription link (skippable), and the one Android VPN permission. Ends
 * on Главная; when the user chose WARP in step 1 it connects right away.
 */
@Composable
fun OnboardingScreen(viewModel: MainViewModel, actions: VpnActions) {
    val c = Organic.colors
    var step by rememberSaveable { mutableIntStateOf(0) }
    var startWarp by rememberSaveable { mutableStateOf(false) }

    val finish: () -> Unit = {
        viewModel.finishOnboarding()
        if (startWarp) viewModel.startWarp()
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) finish()
        else viewModel.showMessage("Без разрешения VPN не запустится")
    }
    // The notification permission first (Android 13+, so the VPN shows in the
    // shade), then the VPN one.
    val askPermission: () -> Unit = {
        actions.withNotificationPermission {
            val intent = viewModel.prepareVpn()
            if (intent == null) finish() else consent.launch(intent)
        }
    }

    BackHandler(enabled = step > 0) { step -= 1 }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        // Decor: two soft circles in the top right.
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 70.dp, y = 30.dp)
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(c.sage200)
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-60).dp, y = 170.dp)
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(c.accent200)
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp)
        ) {
            StepDots(step)
            when (step) {
                0 -> WelcomeStep(
                    onAddSubscription = {
                        startWarp = false
                        step = 1
                    },
                    onStartWithWarp = {
                        startWarp = true
                        step = 2
                    }
                )
                1 -> SubscriptionStep(viewModel, onDone = { step = 2 })
                else -> PermissionStep(startWarp, onAllow = askPermission, onLater = viewModel::finishOnboarding)
            }
        }
    }
}

/** Three dots: the current one 28 wide, the others 8; width animates over 300 ms. */
@Composable
private fun StepDots(step: Int) {
    val c = Organic.colors
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val width by animateDpAsState(if (index == step) 28.dp else 8.dp, tween(300), label = "dot$index")
            Box(Modifier.size(width, 8.dp).clip(CircleShape).background(c.accent))
        }
    }
}

private val StepTitle = OrganicType.heroTitle

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.WelcomeStep(onAddSubscription: () -> Unit, onStartWithWarp: () -> Unit) {
    val c = Organic.colors
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom)
    ) {
        Text("Один клиент для всех ваших серверов", style = OrganicType.onboardingTitle, color = c.text)
        Text(
            "Подписки и отдельные конфигурации в одном списке. OmniVPN сам найдёт самый быстрый сервер " +
                "и переключится, если он перестанет работать.",
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.5.sp),
            color = c.neutral700,
            modifier = Modifier.widthIn(max = 310.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("VLESS", TagTone.Accent)
            Tag("Trojan", TagTone.Sage)
            Tag("Hysteria2", TagTone.Neutral)
            Tag("AmneziaWG", TagTone.Outline)
            Tag("TUIC", TagTone.Neutral)
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OrganicButton("Добавить подписку", onClick = onAddSubscription, modifier = Modifier.fillMaxWidth(), large = true)
            OrganicButton(
                "Начать с Cloudflare WARP",
                onClick = onStartWithWarp,
                modifier = Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary,
                icon = OrganicIcons.Cloud,
                large = true
            )
        }
    }
}

@Composable
private fun ColumnScope.SubscriptionStep(viewModel: MainViewModel, onDone: () -> Unit) {
    val c = Organic.colors
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.weight(1f).fillMaxWidth().padding(top = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Вставьте ссылку на подписку", style = StepTitle, color = c.text)
        Text(
            "Её выдаёт ваш провайдер VPN или владелец сервера. Конфигурацию или файл можно добавить позже " +
                "на вкладке «Серверы».",
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.5.sp),
            color = c.neutral700
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrganicTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                },
                modifier = Modifier.weight(1f),
                placeholder = "https://…",
                minHeight = 50.dp,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                enabled = !adding
            )
            OrganicButton(
                "Вставить",
                onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        url = it
                        error = null
                    }
                },
                kind = ButtonKind.Secondary,
                enabled = !adding
            )
        }
        error?.let { Text(it, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = c.accent800) }
        Spacer(Modifier.weight(1f))
        OrganicButton(
            if (adding) "Загружаем подписку…" else "Продолжить",
            onClick = {
                val problem = subscriptionUrlError(url)
                if (problem != null) {
                    error = problem
                } else {
                    viewModel.addSubscription(url) { failure -> if (failure == null) onDone() else error = failure }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !adding,
            large = true
        )
        OrganicButton(
            "Пропустить",
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            kind = ButtonKind.Ghost,
            enabled = !adding
        )
    }
}

@Composable
private fun ColumnScope.PermissionStep(startWarp: Boolean, onAllow: () -> Unit, onLater: () -> Unit) {
    val c = Organic.colors
    Column(Modifier.weight(1f).fillMaxWidth().padding(top = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.sage), contentAlignment = Alignment.Center) {
            Icon(OrganicIcons.ShieldCheck, contentDescription = null, modifier = Modifier.size(30.dp), tint = c.bg)
        }
        Text("Одно разрешение от Android", style = StepTitle, color = c.text)
        Text(
            "Система спросит, можно ли OmniVPN создать VPN-подключение. Это нужно один раз. " +
                "Трафик идёт только на выбранный вами сервер.",
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.5.sp),
            color = c.neutral700
        )
        Spacer(Modifier.weight(1f))
        OrganicButton(
            if (startWarp) "Разрешить и включить WARP" else "Разрешить",
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth(),
            large = true
        )
        // Not in the handoff: without it, someone who won't grant the
        // permission right now could never get into the app.
        OrganicButton("Позже", onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally), kind = ButtonKind.Ghost)
    }
}
