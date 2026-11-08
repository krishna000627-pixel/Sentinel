package com.sentinel.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Scans installed apps off-screen. Only new and updated apps are scanned unless "force" is set. */
class ScanWorker(private val c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {

    @Suppress("DEPRECATION")
    override suspend fun doWork(): Result {
        val pm = c.packageManager
        val dao = Db.get(c).dao()
        val last: Map<String, Long> = if (inputData.getBoolean("force", false)) emptyMap() else dao.lastScans().associate { it.pkg to it.time }
        val todo = pm.getInstalledPackages(0).filter {
            val ai = it.applicationInfo
            ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != c.packageName &&
                (last[it.packageName] ?: -1L) < it.lastUpdateTime
        }
        val safe = dao.safeNow().toSet()
        val total = todo.size
        val done = AtomicInteger(0)
        val risky = java.util.Collections.synchronizedList(ArrayList<String>())
        val gate = Semaphore(3)
        setProgress(workDataOf("done" to 0, "total" to total, "label" to ""))
        coroutineScope {
            todo.map { pi ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
                        try {
                            val r = ApkScanner.scanInstalled(c, pi.packageName)
                            dao.add(ScanEntity(name = r.name, pkg = r.pkg, score = r.score, text = r.text(), time = System.currentTimeMillis()))
                            if (r.score >= 55 && r.pkg !in safe) risky.add(r.name)
                        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                        setProgress(workDataOf("done" to done.incrementAndGet(), "total" to total, "label" to label))
                    }
                }
            }.awaitAll()
        }
        c.getSharedPreferences("s", 0).edit().putString("last_audit", "${System.currentTimeMillis()}|$total|${risky.size}").apply()
        if (risky.isNotEmpty()) notifyRisky(risky.toList())
        return Result.success()
    }

    private fun notifyRisky(names: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("risk", "Risky apps", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, "risk")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (names.size == 1) "Sentinel found 1 risky app" else "Sentinel found ${names.size} risky apps")
            .setContentText(names.take(3).joinToString(", "))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(1, n)
    }
}

object Background {
    private const val NOW = "audit"
    private const val AUTO = "auto_audit"

    fun scanNow(c: Context, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<ScanWorker>().setInputData(workDataOf("force" to force)).build()
        WorkManager.getInstance(c).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** True when the repeating background check is really queued with the system. */
    fun scheduled(c: Context): Boolean = try {
        WorkManager.getInstance(c).getWorkInfosForUniqueWork(AUTO).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    } catch (_: Exception) { false }

    fun auto(c: Context, on: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (!on) { wm.cancelUniqueWork(AUTO); return }
        val req = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
        wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
