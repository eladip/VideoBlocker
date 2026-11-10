package com.videoblocker.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Device Owner mode (set once with adb). Gives real system-level control:
 *  - blocked apps are HIDDEN from the whole device (including preinstalled YouTube)
 *  - this app cannot be uninstalled
 *  - safe mode, factory reset and adding users are disabled
 *  - optionally: installing any new app is disabled
 */
object DeviceOwner {

    const val ADB_COMMAND = "adb shell dpm set-device-owner com.videoblocker.app/.AdminReceiver"

    /** Never hide these, even if someone adds them to the list. */
    private val NEVER_HIDE = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.google.android.permissioncontroller"
    )

    private val CORE_RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER
    )

    private val INSTALL_RESTRICTIONS = listOf(
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
    )

    private fun dpm(c: Context) =
        c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    /** Brings the device in line with the current settings. Safe to call any time. */
    fun apply(c: Context) {
        if (!isOwner(c)) return
        val dpm = dpm(c)
        val admin = admin(c)
        val on = Prefs.isEnabled(c)

        val target = if (on) Prefs.blockedApps(c) - NEVER_HIDE - c.packageName else emptySet()
        for (p in Prefs.hiddenApps(c) - target) {
            runCatching { dpm.setApplicationHidden(admin, p, false) }
        }
        val nowHidden = mutableSetOf<String>()
        for (p in target) {
            runCatching {
                dpm.setApplicationHidden(admin, p, true)
                if (dpm.isApplicationHidden(admin, p)) nowHidden += p
            }
        }
        Prefs.setHiddenApps(c, nowHidden)

        runCatching { dpm.setUninstallBlocked(admin, c.packageName, on) }
        CORE_RESTRICTIONS.forEach { setRestriction(c, it, on) }
        INSTALL_RESTRICTIONS.forEach { setRestriction(c, it, on && Prefs.isBlockInstalls(c)) }
    }

    /** Undo everything and give up device-owner status. */
    fun release(c: Context) {
        if (!isOwner(c)) return
        val dpm = dpm(c)
        val admin = admin(c)
        for (p in Prefs.hiddenApps(c)) runCatching { dpm.setApplicationHidden(admin, p, false) }
        Prefs.setHiddenApps(c, emptySet())
        runCatching { dpm.setUninstallBlocked(admin, c.packageName, false) }
        (CORE_RESTRICTIONS + INSTALL_RESTRICTIONS).forEach { setRestriction(c, it, false) }
        @Suppress("DEPRECATION")
        runCatching { dpm.clearDeviceOwnerApp(c.packageName) }
    }

    private fun setRestriction(c: Context, key: String, on: Boolean) {
        runCatching {
            if (on) dpm(c).addUserRestriction(admin(c), key)
            else dpm(c).clearUserRestriction(admin(c), key)
        }
    }
}
