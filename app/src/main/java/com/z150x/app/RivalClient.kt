package com.z150x.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Another dash app (Carbit Ride, the one the Zontes dash is paired with from the factory) can hold
 * the same link ports the dash calls back on (10920-10922): the dash then connects to *it* and stays
 * blank for us ([EasyConnProber] detects the failed bind and surfaces this).
 *
 * Android has no way for one app to stop another, so all this does is take the rider to the place
 * where they can: that app's App-info screen (Force stop), or the list of apps if it isn't the one
 * we know.
 */
object RivalClient {
    const val PKG = "net.easyconn.easyride.wws"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Open the other app's App-info page (Force stop), or the app list when it isn't installed. */
    fun openAppInfo(ctx: Context): Boolean = try {
        val i = if (isInstalled(ctx)) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", PKG, null))
        } else {
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
