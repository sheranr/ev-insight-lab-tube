/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import com.google.android.material.color.DynamicColors
import com.kododake.aabrowser.AppConstants.REQUEST_CODE_POST_NOTIFICATIONS
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.ev.DriveSafeMonitor
import com.kododake.aabrowser.ev.EvConfig
import com.kododake.aabrowser.ev.UpdateManager
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.main.BrowserManagers
import com.kododake.aabrowser.main.FreeDroidWarnHelper
import com.kododake.aabrowser.main.MainActivityBackPressHandler
import com.kododake.aabrowser.main.MainActivityCallbackFactory
import com.kododake.aabrowser.main.MenuFabController
import com.kododake.aabrowser.main.WebViewWarmupHelper
import com.kododake.aabrowser.model.QuickActionButtonMode
import com.kododake.aabrowser.tabs.BrowserTab
import com.kododake.aabrowser.ui.MainActivitySetup
import com.kododake.aabrowser.ui.controllers.NavigationButtonUpdater
import com.kododake.aabrowser.ui.controllers.ProgressIndicatorController

class MainActivity : AppCompatActivity(), MainActivityCallbackFactory.CallbackHost {

    private lateinit var binding: ActivityMainBinding

    private val isDebugBuild: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val managers: BrowserManagers by lazy {
        BrowserManagers(
            activity = this,
            binding = binding,
            host = this,
            isDebugBuild = isDebugBuild,
            onUrlChanged = ::onUrlChanged,
            onTitleChanged = ::onTitleChanged,
            onProgressChanged = ::updateProgress,
            onNavigationButtonsUpdateNeeded = ::updateNavigationButtons
        )
    }

    private val menuFabController: MenuFabController by lazy {
        MenuFabController(
            context = this,
            binding = binding,
            menuHelper = managers.uiManager.menuHelper,
            isShowingStartPage = { managers.startPageManager.isShowingStartPage },
            isInFullscreen = { managers.uiManager.isInFullscreen() }
        )
    }

    private val progressController: ProgressIndicatorController by lazy {
        ProgressIndicatorController(binding.progressComposeView)
    }

    var webView: WebView? = null
        private set

    override var currentUrl: String = ""
        private set

    override var currentPageTitle: String = ""
        private set

    private var shouldForceSessionRestore: Boolean = false

    var latestReleaseUrl: String = "https://github.com/sheranr/ev-insight-lab-tube/releases"
        private set

    private val pickStartPageBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        managers.startPageManager.handleStartPageBackgroundPicked(uri)
        onRebuildSettingsContent()
    }

    override fun attachBaseContext(newBase: Context?) {
        val scaled = newBase?.let { BrowserPreferences.createScaledContext(it) }
        super.attachBaseContext(scaled ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WebViewWarmupHelper.warmup(this)
        DynamicColors.applyToActivityIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(BrowserPreferences.getThemeMode(this).nightMode)
        super.onCreate(savedInstanceState)

        shouldForceSessionRestore = (savedInstanceState != null)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        managers.umamiTracker.trackEvent("app_open")
        setupUi()
        setupBackPressHandling()
        setupEvNavigationBar()

        updateManager = UpdateManager(this)
        driveSafe = DriveSafeMonitor(this) { applyEvPictureHidden() }
        showStartupNotice()
    }

    // Modified 2026-11-03: drive-safe lock, self-updates and sharing.
    private lateinit var updateManager: UpdateManager
    private lateinit var driveSafe: DriveSafeMonitor

    private val evPrefs by lazy { getSharedPreferences("ev_display", MODE_PRIVATE) }

    private fun showStartupNotice() {
        if (driveSafe.isCarScreen()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(getString(R.string.ev_car_notice))
                .setPositiveButton(R.string.ev_car_notice_ok, null)
                .setNegativeButton(R.string.ev_close) { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }
        val seenVersion = evPrefs.getInt("welcome_seen_code", 0)
        if (seenVersion < BuildConfig.VERSION_CODE) {
            evPrefs.edit().putInt("welcome_seen_code", BuildConfig.VERSION_CODE).apply()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ev_welcome_title, BuildConfig.VERSION_NAME))
                .setMessage(getString(R.string.ev_welcome_message))
                .setPositiveButton(R.string.ev_welcome_allow) { _, _ ->
                    if (!driveSafe.hasLocationPermission()) driveSafe.requestLocationPermission()
                    updateManager.checkOnLaunch()
                }
                .setCancelable(false)
                .show()
        } else {
            updateManager.checkOnLaunch()
        }
    }

    // Modified 2026-10-02: persistent navigation for the car and phone layouts.
    private fun setupEvNavigationBar() {
        binding.evDisplaySettingsButton.setOnClickListener { showEvMenu() }
        binding.evAudioCurtain.setOnClickListener {
            if (::driveSafe.isInitialized && driveSafe.state == DriveSafeMonitor.State.NEEDS_PERMISSION) {
                driveSafe.requestLocationPermission()
            }
        }
        binding.evBackButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.evHomeButton.setOnClickListener {
            managers.uiManager.exitFullscreen()
            managers.overlayCoordinator.hideAll()
            managers.navigationManager.loadUrlFromIntent(EvConfig.YOUTUBE_HOME)
        }
        binding.evReloadButton.setOnClickListener {
            managers.uiManager.exitFullscreen()
            managers.overlayCoordinator.hideAll()
            if (managers.startPageManager.isShowingStartPage) {
                managers.navigationManager.loadUrlFromIntent(EvConfig.YOUTUBE_HOME)
            } else {
                managers.tabManager.activeTab?.webView?.reload()
            }
        }
        applyEvPictureHidden()
    }

    private fun showEvMenu() {
        val hidden = evPrefs.getBoolean("picture_hidden", false)
        val items = arrayOf(
            getString(if (hidden) R.string.ev_menu_show_picture else R.string.ev_menu_hide_picture),
            getString(R.string.ev_menu_check_updates),
            getString(R.string.ev_menu_share),
            getString(R.string.ev_menu_about)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        evPrefs.edit().putBoolean("picture_hidden", !hidden).apply()
                        applyEvPictureHidden()
                    }
                    1 -> updateManager.checkForUpdates(manual = true)
                    2 -> shareDownloadLink()
                    3 -> androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME)
                        .setMessage(getString(R.string.ev_about_message, EvConfig.SITE_URL, EvConfig.SOURCE_URL))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.ev_close, null)
            .show()
    }

    private fun shareDownloadLink() {
        val text = getString(R.string.ev_share_text, EvConfig.SITE_URL)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.ev_menu_share))) }
    }

    private fun applyEvPictureHidden() {
        val manual = evPrefs.getBoolean("picture_hidden", false)
        val state = if (::driveSafe.isInitialized) driveSafe.state else DriveSafeMonitor.State.OFF
        val reason = when (state) {
            DriveSafeMonitor.State.MOVING -> getString(R.string.ev_curtain_moving)
            DriveSafeMonitor.State.NEEDS_PERMISSION -> getString(R.string.ev_curtain_permission)
            DriveSafeMonitor.State.LOCATION_OFF -> getString(R.string.ev_curtain_location_off)
            else -> null
        }
        val locked = reason != null
        binding.evAudioCurtain.text = reason ?: getString(R.string.ev_curtain_manual)
        binding.evAudioCurtain.visibility = if (manual || locked) View.VISIBLE else View.GONE
        val controls = if (manual || locked) View.GONE else View.VISIBLE
        binding.evBackButton.visibility = controls
        binding.evHomeButton.visibility = controls
        binding.evReloadButton.visibility = controls
        // While the car is moving, keep the screen free of buttons.
        binding.evDisplaySettingsButton.visibility = if (locked) View.GONE else View.VISIBLE
        if (locked) managers.overlayCoordinator.hideAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        managers.navigationManager.extractBrowsableUrl(intent)?.let {
            managers.navigationManager.loadUrlFromIntent(it)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        managers.themeManager.applyMenuHeaderColors()
        managers.bookmarkManager.refreshBookmarks()
        managers.tabManager.refreshTabs()
        managers.startPageManager.refreshStartPage()
        syncUserAgentProfile()
        managers.uiManager.applyQuickActionButtonPreferences()
        driveSafe.start()
        applyEvPictureHidden()
        updateManager.onResume()
    }

    override fun onPause() {
        driveSafe.stop()
        managers.uiManager.exitFullscreen()
        webView?.onPause()
        managers.tabManager.persistTabSession()
        super.onPause()
    }

    override fun onDestroy() {
        menuFabController.cleanup()
        managers.uiManager.exitFullscreen()
        managers.startPageManager.onDestroy()
        managers.tabManager.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == EvConfig.REQUEST_CODE_DRIVE_SAFE_LOCATION) {
            driveSafe.start()
            applyEvPictureHidden()
            return
        }
        managers.permissionManager.handleRequestPermissionsResult(requestCode, grantResults) { granted ->
            val speechTab = managers.tabManager.browserTabs.firstOrNull { it.id == managers.permissionManager.pendingSpeechBridgeTabId }
            speechTab?.speechBridge?.onPermissionResult(granted)
        }
    }

    private fun setupUi() {
        val setup = MainActivitySetup(
            activity = this,
            binding = binding,
            browserManagers = managers,
            actions = MainActivitySetup.Actions(
                getWebView = { webView },
                getCurrentUrl = { currentUrl },
                getLatestReleaseUrl = { latestReleaseUrl },
                updateNavigationButtons = ::updateNavigationButtons,
                handleQuickActionButtonPressed = ::onQuickActionButtonPressed,
                showStartPage = ::showStartPage,
                onDesktopModeChanged = { isChecked ->
                    managers.tabManager.updateDesktopMode(isChecked, BrowserPreferences.getUserAgentProfile(this))
                }
            )
        )
        setup.initializeUi(
            intentUrl = managers.navigationManager.extractBrowsableUrl(intent),
            shouldForceSessionRestore = shouldForceSessionRestore
        )

        updateNavigationButtons()
        managers.tabManager.refreshTabs()
        managers.startPageManager.refreshStartPage()
        menuFabController.showMenuButtonTemporarily()
        managers.bookmarkManager.refreshBookmarks()
        managers.uiManager.applyQuickActionButtonPreferences()
    }

    private fun setupBackPressHandling() {
        MainActivityBackPressHandler.register(
            activity = this,
            overlayCoordinator = managers.overlayCoordinator,
            uiManager = managers.uiManager,
            tabManager = managers.tabManager,
            startPageManager = managers.startPageManager,
            getCurrentUrl = { currentUrl },
            onHideStartPage = ::hideStartPage,
            onNavigationButtonsUpdateNeeded = ::updateNavigationButtons
        )
    }

    private fun showStartPage() {
        managers.startPageManager.showStartPage()
        webView?.visibility = View.INVISIBLE
    }

    private fun hideStartPage() {
        managers.startPageManager.hideStartPage(currentPageTitle, currentUrl)
        webView?.visibility = View.VISIBLE
    }

    private fun updateNavigationButtons() {
        NavigationButtonUpdater.update(
            binding = binding,
            isShowingStartPage = managers.startPageManager.isShowingStartPage,
            currentUrl = currentUrl,
            webView = webView,
            menuHelper = managers.uiManager.menuHelper
        )
    }

    private fun updateProgress(p: Int) = progressController.updateProgress(p)

    override fun onShowMenuButtonTemporarily() = menuFabController.showMenuButtonTemporarily()

    override fun onHomePagePreferenceChanged() {
        managers.bookmarkManager.refreshBookmarks()
        managers.startPageManager.refreshStartPage()
        onRebuildSettingsContent()
        val url = BrowserPreferences.getHomePageUrl(this)
        if (!url.isNullOrBlank() && managers.startPageManager.isShowingStartPage) {
            managers.navigationManager.loadUrlFromIntent(url)
        } else {
            updateNavigationButtons()
        }
    }

    override fun onRebuildSettingsContent() {
        if (::binding.isInitialized && binding.settingsComposeView.isVisible) {
            managers.overlayManager.showSettingsView()
        }
    }

    override fun onQuickActionButtonPressed() {
        val mode = BrowserPreferences.getQuickActionButtonMode(this)
        managers.uiManager.showMenuOverlay(focusAddressBar = (mode != QuickActionButtonMode.MENU))
    }

    private fun syncUserAgentProfile() {
        managers.tabManager.updateUserAgentProfile(
            BrowserPreferences.getUserAgentProfile(this),
            BrowserPreferences.shouldUseDesktopMode(this)
        )
    }
    override fun onUrlChanged(url: String) { currentUrl = url }
    override fun onTitleChanged(title: String) {
        currentPageTitle = title
        managers.bookmarkManager.updateBookmarkTitleIfBetter(currentUrl, title)
    }
    override fun onTabChanged(tab: BrowserTab) {
        webView = tab.webView
        currentUrl = tab.currentUrl
        currentPageTitle = tab.currentTitle
        managers.bookmarkManager.updateBookmarkTitleIfBetter(tab.currentUrl, tab.currentTitle)
        managers.uiManager.menuHelper.updatePage(tab.currentUrl, tab.currentTitle)
    }
    override fun onShowStartPage() { showStartPage() }
    override fun onHideStartPage() { hideStartPage() }
    override fun onUpdateNavigationButtons() { updateNavigationButtons() }
    override fun onPickBackgroundRequested() { pickStartPageBackgroundLauncher.launch(arrayOf("image/*")) }
    override fun onVersionInfoReceived(latestUrl: String, tagName: String) { latestReleaseUrl = latestUrl }
    override fun onRecreateRequested() { recreate() }
}
