@file:Suppress("DEPRECATION")
package com.sentinel.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

data class Priv(val title: String, val note: String, val action: String, val apps: List<String>)

object Access {
    private fun userApps(c: Context) = c.packageManager.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != c.packageName }

    private fun label(c: Context, p: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString()
    } catch (_: Exception) { p }

    fun privileged(c: Context): List<Priv> {
        val user = userApps(c).associateBy { it.packageName }
        fun names(pk: Collection<String>) = pk.filter { it in user }.map { label(c, it) }.sorted()
        val acc = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') }
        val nl = NotificationManagerCompat.getEnabledListenerPackages(c)
        val adm: List<String> = try {
            c.getSystemService(DevicePolicyManager::class.java).activeAdmins?.map { it.packageName } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val ops = c.getSystemService(AppOpsManager::class.java)
        fun op(n: String) = user.values.filter { a ->
            try { ops.checkOpNoThrow(n, a.uid, a.packageName) == AppOpsManager.MODE_ALLOWED } catch (_: Exception) { false }
        }.map { it.packageName }
        return listOf(
            Priv("Accessibility is ON", "Can read your screen and tap for you", "android.settings.ACCESSIBILITY_SETTINGS", names(acc)),
            Priv("Notification access is ON", "Can read every notification, including OTP codes", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", names(nl)),
            Priv("Device admin is ON", "Hard to uninstall, can lock or wipe", "android.settings.SECURITY_SETTINGS", names(adm)),
            Priv("Draw over other apps", "Can cover other apps with fake screens", "android.settings.action.MANAGE_OVERLAY_PERMISSION", names(op("android:system_alert_window"))),
            Priv("Install unknown apps", "Can install other APKs", "android.settings.MANAGE_UNKNOWN_APP_SOURCES", names(op("android:request_install_packages"))),
            Priv("Usage access", "Can see which apps you open and when", "android.settings.USAGE_ACCESS_SETTINGS", names(op("android:get_usage_stats")))
        )
    }

    private fun snap(c: Context): Map<String, Pair<String, Set<String>>> {
        val pm = c.packageManager
        val fl = PackageManager.GET_PERMISSIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        return userApps(c).mapNotNull { a ->
            try {
                val pi = pm.getPackageInfo(a.packageName, fl)
                a.packageName to (ApkScanner.certs(pi).joinToString(",") to (pi.requestedPermissions?.toSet() ?: emptySet()))
            } catch (_: Exception) { null }
        }.toMap()
    }

    fun saveBaseline(c: Context, prefs: SharedPreferences): Int {
        val j = JSONObject()
        snap(c).forEach { (p, v) -> j.put(p, JSONObject().put("c", v.first).put("p", v.second.joinToString(","))) }
        prefs.edit().putString("base", j.toString()).apply()
        return j.length()
    }

    /** null = no baseline saved yet. Pair(severity, text), 2 = serious. */
    fun checkBaseline(c: Context, prefs: SharedPreferences): List<Pair<Int, String>>? {
        val raw = prefs.getString("base", null) ?: return null
        val j = JSONObject(raw); val out = mutableListOf<Pair<Int, String>>()
        snap(c).forEach { (p, v) ->
            val l = label(c, p); val o = j.optJSONObject(p)
            if (o == null) out += 1 to "$l is new since the baseline"
            else {
                if (o.getString("c") != v.first) out += 2 to "$l: signer CHANGED, the update came from a different key"
                val old = o.getString("p").split(',').filter { it.isNotBlank() }.toSet()
                val add = v.second - old
                if (add.isNotEmpty()) out += 1 to "$l gained ${add.size} permissions: ${add.take(5).joinToString { it.substringAfterLast('.') }}"
            }
        }
        return out.sortedByDescending { it.first }
    }
}
