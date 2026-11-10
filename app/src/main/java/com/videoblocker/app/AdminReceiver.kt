package com.videoblocker.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Device admin: Android won't uninstall the app while this is active. */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // When set as Device Owner via adb, apply the system-level rules right away
        DeviceOwner.apply(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "יש לבטל את ההגנה מתוך אפליקציית חוסם הווידאו עם הסיסמה."
}
