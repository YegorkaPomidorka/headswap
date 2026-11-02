package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.yegorkapomidorka.headswap.R

enum class SettingsPage { MAIN, NAVIGATOR, APPS }

/** Настройки лаунчера на экране ГУ (шестерёнка): всё крупными целями T и без скролла. */
@Composable
fun LauncherSettingsScreen() {
    when (LauncherModel.settingsPage) {
        SettingsPage.MAIN -> SettingsMain()
        SettingsPage.NAVIGATOR -> NavigatorChoice()
        SettingsPage.APPS -> AppsEditor()
    }
}

@Composable
private fun SettingsMain() {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Column(
        Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.m), end = m.dp(s.m), bottom = m.dp(s.m)),
        verticalArrangement = Arrangement.spacedBy(m.dp(s.g)),
    ) {
        Row(Modifier.fillMaxWidth().height(m.dp(s.t)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Txt(stringResource(R.string.l_settings), s.f1, color = Pal.SEC2, weight = FontWeight.SemiBold)
        }
        SettingRow(stringResource(R.string.l_set_theme)) {
            for (t in ThemeChoice.entries) ThemeChip(t)
        }
        SettingRow(stringResource(R.string.l_set_navigator)) {
            val pkg = model.navigatorPkg
            PillButton(
                pkg?.let { p -> model.catalog.firstOrNull { it.packageName == p }?.label } ?: stringResource(R.string.l_nav_choose),
                { model.settingsPage = SettingsPage.NAVIGATOR }, filled = false,
            )
        }
        SettingRow(stringResource(R.string.l_set_hand)) {
            Chip(stringResource(R.string.l_left), !model.rightHand) { model.chooseHand(false) }
            Chip(stringResource(R.string.l_right), model.rightHand) { model.chooseHand(true) }
        }
        SettingRow(stringResource(R.string.l_set_apps)) {
            PillButton("${model.visibleApps.size}", { model.settingsPage = SettingsPage.APPS }, filled = false)
        }
    }
}

@Composable
private fun SettingRow(title: String, content: @Composable () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(
        Modifier.fillMaxWidth().height(m.dp(s.t)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g)),
    ) {
        Box(Modifier.weight(1f)) { Txt(title, s.f1, color = Pal.TEXT) }
        Ltr { content() }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onTap: () -> Unit) {
    PillButton(text, onTap, filled = selected)
}

@Composable
private fun ThemeChip(theme: ThemeChoice) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = when (theme) {
        ThemeChoice.GOLD -> stringResource(R.string.l_theme_gold)
        ThemeChoice.GOLD_DEEP -> stringResource(R.string.l_theme_gold_deep)
        ThemeChoice.GOLD_LIGHT -> stringResource(R.string.l_theme_gold_light)
        ThemeChoice.DARK -> stringResource(R.string.l_theme_dark)
    }
    val selected = LauncherModel.theme == theme
    Pressable(
        Modifier.height(m.dp(s.t)).clip(RoundedCornerShape(m.dp(s.t / 2)))
            .background(if (selected) Pal.SURFACE_HI else Pal.SURFACE)
            .then(if (selected) Modifier.border(m.dp(s.b4), theme.accent, RoundedCornerShape(m.dp(s.t / 2))) else Modifier),
        description = label, onTap = { LauncherModel.chooseTheme(theme) },
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Row(Modifier.padding(horizontal = m.dp((s.t * 0.3f).toInt())).height(m.dp(s.t)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
            Box(Modifier.size(m.dp(s.ic)).clip(CircleShape)
                .background(if (theme.glow) theme.accent else Color(0xFF1A232B))
                .border(m.dp(s.b4 / 2 + 1), theme.accent, CircleShape))
            Txt(label, s.f2, color = Pal.TEXT, weight = FontWeight.Medium)
        }
    }
}

/** Выбор навигатора: установленные из известных, страницами; «По умолчанию» сбрасывает выбор. */
@Composable
private fun NavigatorChoice() {
    val model = LauncherModel
    var page by remember { mutableIntStateOf(0) }
    val items = model.installedNavigators.mapNotNull { pkg ->
        model.catalog.firstOrNull { it.packageName == pkg }?.let { AppRef(it.packageName, it.className) }
    }
    PagedApps(
        items, page, onPage = { page = it },
        leading = {
            PillButton(stringResource(R.string.l_back), { model.settingsPage = SettingsPage.MAIN }, icon = Ico.CHEV_L, filled = false)
            PillButton(stringResource(R.string.l_set_default), { model.setNavigator(null); model.settingsPage = SettingsPage.MAIN },
                filled = model.savedNavigator == null)
        },
    ) { ref, iconPx ->
        AppTile(ref, labelled = true, iconPx = iconPx, selected = model.navigatorPkg == ref.pkg,
            onTap = { model.setNavigator(ref.pkg); model.settingsPage = SettingsPage.MAIN })
    }
}

/** Избранные приложения нижнего ряда с удалением (дубль долгого тапа по ним на главном). */
@Composable
private fun AppsEditor() {
    val model = LauncherModel
    val m = LocalMetrics.current
    var page by remember { mutableIntStateOf(0) }
    val items = model.visibleApps
    Box(Modifier.fillMaxSize()) {
        PagedApps(
            items, page, onPage = { page = it },
            leading = {
                PillButton(stringResource(R.string.l_back), { model.settingsPage = SettingsPage.MAIN }, icon = Ico.CHEV_L, filled = false)
                Txt(stringResource(R.string.l_set_apps), m.spec.f1, color = Pal.SEC2, weight = FontWeight.SemiBold)
            },
        ) { ref, iconPx ->
            AppTile(ref, labelled = true, iconPx = iconPx, onTap = { model.confirmRemove = ref })
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt(stringResource(R.string.l_set_apps_empty), m.spec.f1, color = Pal.SEC)
            }
        }
    }
}
