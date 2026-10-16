package com.betalgezia.omnivpn.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Light or dark Organic theme (Settings → Приложение → Тема). */
enum class ThemeChoice { LIGHT, DARK }

/**
 * The user's theme choice. Until they pick one the app follows the system
 * setting (null here), so a phone in dark mode doesn't open blinding white.
 */
@Singleton
class AppearanceSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(read())
    val theme: StateFlow<ThemeChoice?> = _theme.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        prefs.edit().putString(KEY_THEME, choice.name).apply()
        _theme.value = choice
    }

    private fun read(): ThemeChoice? =
        prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
