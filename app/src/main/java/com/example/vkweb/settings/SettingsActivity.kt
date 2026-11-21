package com.example.vkweb.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.example.vkweb.R
import com.example.vkweb.ui.AppTheme
import com.google.android.material.appbar.MaterialToolbar

/** Экран настроек. Тема применяется сразу: этот экран можно пересоздать без потерь. */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    private var outcome = SettingsOutcome()
    private lateinit var toolbar: MaterialToolbar

    /** Раздел настроек (3.94) — отдельным экраном тем же [SettingsFragment] с ключом раздела. */
    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key) }
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.settings_container, fragment)
            .addToBackStack(pref.key)
            .commit()
        return true
    }

    /** Заголовок — название открытого раздела; у списка разделов — «Настройки». */
    fun setScreenTitle(title: CharSequence?) {
        toolbar.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.settings_title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this, AppSettings(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        outcome = SettingsOutcome.fromBundle(savedInstanceState?.getBundle(STATE_OUTCOME))
        publishOutcome()

        toolbar = findViewById(R.id.settings_toolbar)
        // «Назад» на панели — как системный: из раздела к списку разделов, со списка — закрыть настройки.
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        limitContentWidth(findViewById(R.id.settings_container))
        com.example.vkweb.ui.SystemBars.edgeToEdge(this)
        com.example.vkweb.ui.SystemBars.toolbar(toolbar)
        com.example.vkweb.ui.SystemBars.bottom(findViewById(R.id.settings_container))

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(STATE_OUTCOME, outcome.toBundle())
    }

    fun requestClearCache() {
        outcome = outcome.copy(clearCache = true)
        publishOutcome()
    }

    fun reportStorageCleared() {
        outcome = outcome.copy(storageCleared = true)
        publishOutcome()
    }

    fun reportSignedOut() {
        outcome = outcome.copy(signedOut = true)
        publishOutcome()
    }

    private fun publishOutcome() = setResult(Activity.RESULT_OK, outcome.toIntent())

    /** На широких экранах список настроек не растягивается шире [MAX_CONTENT_WIDTH_DP]. */
    private fun limitContentWidth(container: View) {
        val widthDp = resources.configuration.screenWidthDp
        if (widthDp <= MAX_CONTENT_WIDTH_DP) return
        val sidePx = ((widthDp - MAX_CONTENT_WIDTH_DP) / 2 * resources.displayMetrics.density).toInt()
        container.setPadding(sidePx, 0, sidePx, 0)
    }

    private companion object {
        const val MAX_CONTENT_WIDTH_DP = 720
        const val STATE_OUTCOME = "outcome"
    }
}
