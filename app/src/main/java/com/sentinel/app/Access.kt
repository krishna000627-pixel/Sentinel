package com.sentinel.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Apps that hold sensitive access (powerful permissions and special access), plus a saved
 * baseline so you can see what changed later.
 * An entry is: package to (app label to set of access names).
 */
object Access {
    private val PERMS: List<Triple<String, String, Int>> = listOf(
        Triple("android.permission.CAMERA", "Camera", 10),
        Triple("android.permission.RECORD_AUDIO", "Microphone", 12),
        Triple("android.permission.ACCESS_FINE_LOCATION", "Location", 10),
        Triple("android.permission.ACCESS_BACKGROUND_LOCATION", "Background location", 15),
        Triple("android.permission.READ_CONTACTS", "Contacts", 8),
        Triple("android.permission.READ_SMS", "SMS", 15),
        Triple("android.permission.RECEIVE_SMS", "SMS", 15),
        Triple("android.permission.READ_CALL_LOG", "Call log", 10)
    )
    private val WEIGHT: Map<String, Int> = mapOf(
        "Accessibility service" to 30, "Device admin" to 25, "Reads notifications" to 20,
        "Draw over apps" to 15, "Install apps" to 12, "Usage access" to 8
    )
    private const val KEY = "access_baseline"

    private fun weight(g: Set<String>): Int = g.sumOf { n -> WEIGHT[n] ?: PERMS.firstOrNull { it.second == n }?.third ?: 5 }

    @Suppress("DEPRECATION")
    fun privileged(c: Context): List<Pair<String, Pair<String, Set<String>>>> {
        val pm = c.packageManager
        val a11y = accessibilityPkgs(c)
        val listeners: Set<String> = try { NotificationManagerCompat.getEnabledListenerPackages(c) } catch (_: Exception) { emptySet() }
        val admins: Set<String> = try {
            c.getSystemService(DevicePolicyManager::class.java)?.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
        val ops = c.getSystemService(AppOpsManager::class.java)
        val out = ArrayList<Pair<String, Pair<String, Set<String>>>>()
        for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val ai = pi.applicationInfo ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || pi.packageName == c.packageName) continue
            val g = LinkedHashSet<String>()
            val req: Array<String>? = pi.requestedPermissions
            val flags: IntArray? = pi.requestedPermissionsFlags
            if (req != null && flags != null) {
                for (i in req.indices) {
                    if ((flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) continue
                    for (p in PERMS) if (p.first == req[i]) g.add(p.second)
                }
                if (req.contains("android.permission.SYSTEM_ALERT_WINDOW") && allowed(ops, "android:system_alert_window", ai.uid, pi.packageName)) g.add("Draw over apps")
                if (req.contains("android.permission.REQUEST_INSTALL_PACKAGES") && allowed(ops, "android:request_install_packages", ai.uid, pi.packageName)) g.add("Install apps")
                if (req.contains("android.permission.PACKAGE_USAGE_STATS") && allowed(ops, "android:get_usage_stats", ai.uid, pi.packageName)) g.add("Usage access")
            }
            if (pi.packageName in a11y) g.add("Accessibility service")
            if (pi.packageName in listeners) g.add("Reads notifications")
            if (pi.packageName in admins) g.add("Device admin")
            if (g.isNotEmpty()) out.add(pi.packageName to (ai.loadLabel(pm).toString() to g.toSet()))
        }
        return out.sortedWith(compareByDescending<Pair<String, Pair<String, Set<String>>>> { weight(it.second.second) }
            .thenBy { it.second.first.lowercase() })
    }

    /** Saves the current access as the baseline. Returns how many apps were saved. */
    fun saveBaseline(c: Context, list: List<Pair<String, Pair<String, Set<String>>>> = privileged(c)): Int =
        saveBaseline(c.getSharedPreferences("s", 0), list)

    fun saveBaseline(p: SharedPreferences, list: List<Pair<String, Pair<String, Set<String>>>>): Int {
        val s = HashSet<String>()
        for (e in list) for (g in e.second.second) s.add(e.first + "|" + g)
        p.edit().putStringSet(KEY, s).apply()
        return list.size
    }

    /** Lists what is new since the baseline (new apps or new access). */
    fun checkBaseline(c: Context, list: List<Pair<String, Pair<String, Set<String>>>> = privileged(c)): List<String> =
        checkBaseline(c.getSharedPreferences("s", 0), list)

    fun checkBaseline(p: SharedPreferences, list: List<Pair<String, Pair<String, Set<String>>>>): List<String> {
        val base: Set<String> = p.getStringSet(KEY, null) ?: return listOf("No baseline yet. Save one first.")
        val knownPkgs = base.map { it.substringBefore('|') }.toSet()
        val out = ArrayList<String>()
        for (e in list) {
            val fresh = e.second.second.filter { (e.first + "|" + it) !in base }
            if (fresh.isEmpty()) continue
            val what = fresh.joinToString(", ")
            out.add(if (e.first in knownPkgs) "${e.second.first} gained: $what" else "${e.second.first} is new: $what")
        }
        return out
    }

    private fun allowed(ops: AppOpsManager?, op: String, uid: Int, pkg: String): Boolean =
        try { ops?.checkOpNoThrow(op, uid, pkg) == AppOpsManager.MODE_ALLOWED } catch (_: Exception) { false }

    private fun accessibilityPkgs(c: Context): Set<String> {
        val raw = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return emptySet()
        return raw.split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') }.toSet()
    }
}
