package com.sentinel.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Shows which installed apps hold sensitive access: granted permissions and special access. */
object Access {
    data class Row(val pkg: String, val label: String, val grants: Set<String>, val score: Int)

    private val PERMS: List<Triple<String, String, Int>> = listOf(
        Triple("android.permission.CAMERA", "Camera", 10),
        Triple("android.permission.RECORD_AUDIO", "Microphone", 12),
        Triple("android.permission.ACCESS_FINE_LOCATION", "Location", 10),
        Triple("android.permission.ACCESS_BACKGROUND_LOCATION", "Background location", 15),
        Triple("android.permission.READ_CONTACTS", "Contacts", 8),
        Triple("android.permission.READ_SMS", "SMS", 15),
        Triple("android.permission.RECEIVE_SMS", "SMS", 15),
        Triple("android.permission.READ_CALL_LOG", "Call log", 10),
        Triple("android.permission.READ_PHONE_STATE", "Phone state", 4)
    )

    @Suppress("DEPRECATION")
    fun scan(c: Context): List<Row> {
        val pm = c.packageManager
        val a11y = accessibilityPkgs(c)
        val listeners: Set<String> = try { NotificationManagerCompat.getEnabledListenerPackages(c) } catch (_: Exception) { emptySet() }
        val admins: Set<String> = try {
            c.getSystemService(DevicePolicyManager::class.java)?.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
        val ops = c.getSystemService(AppOpsManager::class.java)
        val out = ArrayList<Row>()
        for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val ai = pi.applicationInfo ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || pi.packageName == c.packageName) continue
            val grants = LinkedHashSet<String>()
            var score = 0
            fun add(name: String, w: Int) { if (grants.add(name)) score += w }
            val req: Array<String>? = pi.requestedPermissions
            val flags: IntArray? = pi.requestedPermissionsFlags
            if (req != null && flags != null) {
                for (i in req.indices) {
                    if ((flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) continue
                    for (p in PERMS) if (p.first == req[i]) add(p.second, p.third)
                }
                if (req.contains("android.permission.SYSTEM_ALERT_WINDOW") && opAllowed(ops, "android:system_alert_window", ai.uid, pi.packageName)) add("Draw over apps", 15)
                if (req.contains("android.permission.REQUEST_INSTALL_PACKAGES") && opAllowed(ops, "android:request_install_packages", ai.uid, pi.packageName)) add("Install apps", 12)
            }
            if (pi.packageName in a11y) add("Accessibility service", 30)
            if (pi.packageName in listeners) add("Reads notifications", 20)
            if (pi.packageName in admins) add("Device admin", 25)
            if (grants.isNotEmpty()) out.add(Row(pi.packageName, ai.loadLabel(pm).toString(), grants, minOf(score, 100)))
        }
        return out.sortedWith(compareByDescending<Row> { it.score }.thenBy { it.label.lowercase() })
    }

    private fun opAllowed(ops: AppOpsManager?, op: String, uid: Int, pkg: String): Boolean =
        try { ops?.checkOpNoThrow(op, uid, pkg) == AppOpsManager.MODE_ALLOWED } catch (_: Exception) { false }

    private fun accessibilityPkgs(c: Context): Set<String> {
        val raw = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return emptySet()
        return raw.split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') }.toSet()
    }
}
