package com.videoblocker.app

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Stores settings and the hashed password (the password itself is never saved). */
object Prefs {

    val DEFAULT_APPS = listOf(
        "com.google.android.youtube",          // YouTube
        "com.google.android.apps.youtube.kids", // YouTube Kids
        "com.google.android.youtube.tv",
        "app.revanced.android.youtube",
        "com.vanced.android.youtube",
        "com.zhiliaoapp.musically",            // TikTok
        "com.ss.android.ugc.trill",            // TikTok (Asia)
        "com.zhiliaoapp.musically.go",         // TikTok Lite
        "com.netflix.mediaclient",             // Netflix
        "com.disney.disneyplus",               // Disney+
        "com.amazon.avod.thirdpartyclient",    // Prime Video
        "tv.twitch.android.app",               // Twitch
        "com.vimeo.android.videoapp",          // Vimeo
        "com.dailymotion.dailymotion",         // Dailymotion
        "com.rumble.battles",                  // Rumble
        "org.videolan.vlc",                    // VLC
        "com.mxtech.videoplayer.ad",           // MX Player
        "com.mxtech.videoplayer.pro",
        "com.google.android.apps.youtube.creator",
        "com.instagram.android",               // Instagram (Reels)
        "com.facebook.katana",                 // Facebook (Watch)
        "com.snapchat.android"                 // Snapchat
    )

    val DEFAULT_SITES = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "tiktok.com", "netflix.com", "vimeo.com", "twitch.tv",
        "dailymotion.com", "disneyplus.com", "primevideo.com",
        "rumble.com", "bitchute.com", "instagram.com",
        "facebook.com/watch", "fb.watch", "kick.com", "odysee.com"
    )

    private fun sp(c: Context) = c.getSharedPreferences("vb", Context.MODE_PRIVATE)

    // ---------- password ----------
    fun hasPin(c: Context) = sp(c).contains("hash")

    fun setPin(c: Context, pin: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        sp(c).edit().putString("salt", salt).putString("hash", hash(salt, pin)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("salt", null) ?: return false
        val stored = sp(c).getString("hash", null) ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), stored.toByteArray())
    }

    private fun hash(salt: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var b = (salt + ":" + pin).toByteArray(Charsets.UTF_8)
        repeat(20000) { b = md.digest(b) }
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    // ---------- switches ----------
    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Blocks the system screens that could switch off or uninstall the blocker. */
    fun isTamperProtect(c: Context) = sp(c).getBoolean("tamper", false)
    fun setTamperProtect(c: Context, v: Boolean) = sp(c).edit().putBoolean("tamper", v).apply()

    /** Short pause of tamper protection, e.g. while confirming the device-admin screen. */
    fun startGrace(c: Context, ms: Long = 60_000) =
        sp(c).edit().putLong("grace", System.currentTimeMillis() + ms).apply()
    fun inGrace(c: Context) = System.currentTimeMillis() < sp(c).getLong("grace", 0)

    // ---------- device-level ----------
    /** Detects video players inside ANY app (WhatsApp, gallery, Telegram...). */
    fun isDetectVideo(c: Context) = sp(c).getBoolean("detect_video", true)
    fun setDetectVideo(c: Context, v: Boolean) = sp(c).edit().putBoolean("detect_video", v).apply()

    /** Stricter detection: also generic "video_view"/"player_view" style views. */
    fun isStrict(c: Context) = sp(c).getBoolean("strict", true)
    fun setStrict(c: Context, v: Boolean) = sp(c).edit().putBoolean("strict", v).apply()

    /** Only apps in the allowed list (plus system essentials) may open. */
    fun isAllowlistMode(c: Context) = sp(c).getBoolean("allowlist_mode", false)
    fun setAllowlistMode(c: Context, v: Boolean) = sp(c).edit().putBoolean("allowlist_mode", v).apply()

    /** Device-owner only: forbid installing new apps. */
    fun isBlockInstalls(c: Context) = sp(c).getBoolean("block_installs", false)
    fun setBlockInstalls(c: Context, v: Boolean) = sp(c).edit().putBoolean("block_installs", v).apply()

    fun hiddenApps(c: Context): Set<String> = sp(c).getStringSet("hidden", emptySet())!!.toSet()
    fun setHiddenApps(c: Context, s: Set<String>) = sp(c).edit().putStringSet("hidden", s).apply()

    val DEFAULT_ALLOWED = listOf(
        "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer",
        "com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.google.android.deskclock", "com.sec.android.app.clockpackage",
        "com.google.android.calculator", "com.sec.android.app.popupcalculator",
        "com.google.android.calendar", "com.samsung.android.calendar",
        "com.google.android.apps.maps", "com.waze", "com.moovit",
        "com.google.android.gm", "com.whatsapp"
    )

    fun allowedText(c: Context): String =
        sp(c).getString("allowed", null) ?: DEFAULT_ALLOWED.joinToString("\n")
    fun saveAllowed(c: Context, t: String) =
        sp(c).edit().putString("allowed", parse(t).joinToString("\n")).apply()
    fun allowedApps(c: Context): Set<String> = parse(allowedText(c))

    // ---------- lists ----------
    fun appsText(c: Context): String =
        sp(c).getString("apps", null) ?: DEFAULT_APPS.joinToString("\n")

    fun sitesText(c: Context): String =
        sp(c).getString("sites", null) ?: DEFAULT_SITES.joinToString("\n")

    fun saveLists(c: Context, apps: String, sites: String) {
        sp(c).edit()
            .putString("apps", parse(apps).joinToString("\n"))
            .putString("sites", parseSites(sites).joinToString("\n"))
            .apply()
    }

    fun blockedApps(c: Context): Set<String> = parse(appsText(c))
    fun blockedSites(c: Context): Set<String> = parseSites(sitesText(c))

    private fun parse(t: String): Set<String> =
        t.split('\n', ',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun parseSites(t: String): Set<String> =
        parse(t).map {
            it.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        }.filter { it.isNotEmpty() }.toSet()
}
