package com.videoblocker.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * Device-level blocking engine. Layers:
 *  1. Blocked apps list.
 *  2. Allowed-apps-only mode: anything not approved is blocked.
 *  3. Video-player detection inside ANY app (ExoPlayer/Media3, VideoView, player views).
 *  4. Audio-system detection: any playback tagged as "movie" anywhere on the device
 *     is paused and blocked.
 *  5. Blocked sites in browsers.
 *  6. Tamper protection for the settings screens of this app.
 */
class BlockerService : AccessibilityService() {

    private var lastBlock = 0L
    private val lastScan = HashMap<String, Long>()
    private var essentials: Set<String> = emptySet()
    private var essentialsAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private var audioCallback: Any? = null

    companion object {
        /** Address-bar view IDs of common browsers. */
        private val BROWSER_URL_IDS = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text"
            ),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            ),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
            "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
            "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
            "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
            "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
            "com.mi.globalbrowser" to listOf("com.mi.globalbrowser:id/url")
        )

        /** System screens that can disable/uninstall the blocker. */
        private val SYSTEM_PKGS = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.android.vending"
        )

        /** Always allowed and never scanned (phone must stay usable). */
        private val BASE_ESSENTIALS = setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.server.telecom",
            "com.android.incallui", "com.samsung.android.incallui",
            "com.android.emergency", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )

        /** View IDs used by the standard ExoPlayer / Media3 player in every app. */
        private val PLAYER_ID_SUFFIXES = listOf(
            ":id/exo_content_frame", ":id/exo_shutter", ":id/exo_subtitles",
            ":id/exo_controller", ":id/exo_controller_placeholder",
            ":id/exo_progress", ":id/exo_play_pause", ":id/exo_play", ":id/exo_pause",
            ":id/exo_artwork", ":id/exo_overlay"
        )

        /** Strict mode: common names apps give their own video views. */
        private val STRICT_ID_WORDS = listOf(
            "video_view", "videoview", "video_player", "videoplayer", "player_view",
            "playerview", "video_surface", "video_container", "video_frame",
            "videocontainer", "video_texture", "texture_view", "surface_view", "media_player"
        )

        private const val MAX_NODES = 900
    }

    // ================= lifecycle =================

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerAudioWatcher()
        DeviceOwner.apply(this)
    }

    override fun onDestroy() {
        unregisterAudioWatcher()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ================= events =================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !Prefs.isEnabled(this)) return
        val pkg = event.packageName?.toString()?.lowercase() ?: return
        if (pkg == packageName) return
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        // 1. Blocked apps
        if (stateChange && pkg in Prefs.blockedApps(this)) {
            block(pause = true)
            return
        }

        // 6. Tamper protection (checked before essentials, since Settings is essential)
        if (Prefs.isTamperProtect(this) && !Prefs.inGrace(this) && pkg in SYSTEM_PKGS) {
            val root = rootInActiveWindow
            if (root != null && mentionsThisApp(root)) {
                block(pause = false)
                return
            }
        }

        if (pkg in essentials()) return

        // 2. Allowed-apps-only mode
        if (stateChange && Prefs.isAllowlistMode(this) &&
            pkg !in Prefs.allowedApps(this)
        ) {
            block(pause = true)
            return
        }

        // 5. Browsers: check the address bar
        BROWSER_URL_IDS[pkg]?.let { ids ->
            if (urlIsBlocked(ids)) {
                block(pause = true)
                return
            }
        }

        // 3. Video players inside any app
        if (Prefs.isDetectVideo(this) && shouldScan(pkg)) {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString()?.lowercase() == pkg && containsVideoPlayer(root)) {
                block(pause = true)
            }
        }
    }

    // ================= detectors =================

    private fun urlIsBlocked(ids: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val sites = Prefs.blockedSites(this)
        for (id in ids) {
            for (node in root.findAccessibilityNodeInfosByViewId(id)) {
                val url = node.text?.toString()?.lowercase() ?: continue
                if (sites.any { url.contains(it) }) return true
            }
        }
        return false
    }

    /** Throttle tree scans per app so the phone stays fast. */
    private fun shouldScan(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastScan[pkg] ?: 0L) < 700) return false
        lastScan[pkg] = now
        return true
    }

    private fun containsVideoPlayer(root: AccessibilityNodeInfo): Boolean {
        val strict = Prefs.isStrict(this)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val n = stack.removeLast()
            seen++
            val cls = n.className?.toString() ?: ""
            if (cls == "android.widget.VideoView") return true
            val id = n.viewIdResourceName?.lowercase()
            if (id != null) {
                if (PLAYER_ID_SUFFIXES.any { id.endsWith(it) }) return true
                if (strict) {
                    val name = id.substringAfter(":id/")
                    if (STRICT_ID_WORDS.any { name.contains(it) }) return true
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    private fun mentionsThisApp(root: AccessibilityNodeInfo): Boolean =
        root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isNotEmpty()

    /** Launcher(s), keyboards and core phone apps. Refreshed every minute. */
    private fun essentials(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (essentials.isNotEmpty() && now - essentialsAt < 60_000) return essentials
        val s = HashSet(BASE_ESSENTIALS)
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0)
                .forEach { s += it.activityInfo.packageName.lowercase() }
        }
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.forEach { s += it.packageName.lowercase() }
        }
        s -= Prefs.blockedApps(this)
        s += packageName
        essentials = s
        essentialsAt = now
        return s
    }

    // ================= audio-level detection =================

    private fun registerAudioWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                checkPlayback(configs)
            }
        }
        am.registerAudioPlaybackCallback(cb, main)
        audioCallback = cb
        checkPlayback(am.activePlaybackConfigurations)
    }

    private fun unregisterAudioWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val cb = audioCallback as? AudioManager.AudioPlaybackCallback ?: return
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioPlaybackCallback(cb)
        audioCallback = null
    }

    private fun checkPlayback(configs: List<AudioPlaybackConfiguration>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!Prefs.isEnabled(this) || !Prefs.isDetectVideo(this)) return
        val moviePlaying = configs.any {
            it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MOVIE
        }
        if (moviePlaying) block(pause = true)
    }

    // ================= action =================

    private fun pauseMedia() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }

    private fun block(pause: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < 1200) return
        lastBlock = now
        if (pause) pauseMedia()
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
