package com.sentinel.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileInputStream
import kotlin.concurrent.thread

data class AppNet(val pkg: String, val label: String, val src: String, val tx: Long, val rx: Long,
                  val bgTx: Long, val flags: List<String>, val score: Int)

fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)

object Monitor {
    fun hasUsage(c: Context): Boolean {
        val a = c.getSystemService(AppOpsManager::class.java)
        return a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun load(c: Context): List<AppNet> {
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
        val end = System.currentTimeMillis(); val start = end - 24 * 3600_000L
        val tx = HashMap<Int, Long>(); val rx = HashMap<Int, Long>(); val bg = HashMap<Int, Long>()
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) try {
            val ns = nsm.querySummary(type, null, start, end); val b = NetworkStats.Bucket()
            while (ns.hasNextBucket()) {
                ns.getNextBucket(b)
                tx.merge(b.uid, b.txBytes) { x, y -> x + y }
                rx.merge(b.uid, b.rxBytes) { x, y -> x + y }
                if (b.state != NetworkStats.Bucket.STATE_FOREGROUND) bg.merge(b.uid, b.txBytes) { x, y -> x + y }
            }
            ns.close()
        } catch (_: Exception) {}
        val pm = c.packageManager; val m = 1048576L
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != c.packageName }
            .map { a ->
                val t = tx[a.uid] ?: 0L; val r = rx[a.uid] ?: 0L; val g = bg[a.uid] ?: 0L
                val fl = mutableListOf<String>(); var s = 0
                if (t > 5 * m && t > r * 3) { fl += "Uploads ${mb(t)} but downloads only ${mb(r)}"; s += 35 }
                if (g > 5 * m) { fl += "${mb(g)} sent while in background"; s += if (g > 20 * m) 40 else 25 }
                AppNet(a.packageName, a.loadLabel(pm).toString(), a.sourceDir, t, r, g, fl, minOf(s, 100))
            }
            .sortedWith(compareByDescending<AppNet> { it.score }.thenByDescending { it.tx })
    }

    fun applyVpn(c: Context, list: List<String>) {
        val i = Intent(c, BlockVpn::class.java)
        if (list.isEmpty()) c.stopService(i) else c.startService(i.putExtra("pkgs", list.toTypedArray()))
    }
}

/** Per-app block switch: only the chosen apps are routed into this tunnel, and their packets are dropped. */
class BlockVpn : VpnService() {
    private var fd: ParcelFileDescriptor? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val pkgs = i?.getStringArrayExtra("pkgs")
        if (pkgs.isNullOrEmpty()) { stopSelf(); return START_NOT_STICKY }
        try { fd?.close() } catch (_: Exception) {}
        val b = Builder().setSession("Sentinel block")
            .addAddress("10.255.0.2", 32).addAddress("fd00:1::2", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
        pkgs.forEach { try { b.addAllowedApplication(it) } catch (_: Exception) {} }
        val p = b.establish()
        if (p == null) { stopSelf(); return START_NOT_STICKY }
        fd = p
        thread { try { val s = FileInputStream(p.fileDescriptor); val buf = ByteArray(32767); while (s.read(buf) >= 0) {} } catch (_: Exception) {} }
        return START_NOT_STICKY
    }

    override fun onDestroy() { try { fd?.close() } catch (_: Exception) {}; super.onDestroy() }
}
