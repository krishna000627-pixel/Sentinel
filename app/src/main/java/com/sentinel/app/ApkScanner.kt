@file:Suppress("DEPRECATION")
package com.sentinel.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

data class Finding(val sev: Int, val text: String, val w: Int)
data class Report(val name: String, val pkg: String, val score: Int, val findings: List<Finding>)

fun Report.text() = findings.joinToString("\n") { (if (it.sev == 2) "✖ " else if (it.sev == 1) "! " else "· ") + it.text }

object ApkScanner {
    private val PERMS = mapOf(
        "SYSTEM_ALERT_WINDOW" to (8 to "draws over other apps (overlay)"),
        "REQUEST_INSTALL_PACKAGES" to (6 to "can install other apps"),
        "READ_SMS" to (8 to "reads your SMS"), "RECEIVE_SMS" to (7 to "intercepts incoming SMS"), "SEND_SMS" to (8 to "sends SMS"),
        "READ_CALL_LOG" to (6 to "reads call history"), "READ_CONTACTS" to (4 to "reads contacts"),
        "RECORD_AUDIO" to (4 to "records audio"), "CAMERA" to (3 to "uses the camera"),
        "ACCESS_BACKGROUND_LOCATION" to (6 to "tracks location in background"),
        "MANAGE_EXTERNAL_STORAGE" to (5 to "access to all files"),
        "QUERY_ALL_PACKAGES" to (3 to "sees every installed app"),
        "RECEIVE_BOOT_COMPLETED" to (2 to "starts at boot"), "WAKE_LOCK" to (2 to "keeps the CPU awake"),
        "BIND_ACCESSIBILITY_SERVICE" to (10 to "accessibility control")
    )
    private val MINER = listOf("stratum+tcp", "stratum+ssl", "xmrig", "coinhive", "coin-hive", "cryptonight", "minergate",
        "mining.subscribe", "nicehash", "randomx", "supportxmr", "minexmr", "nanopool.org", "moneroocean", "2miners.com", "hashvault.pro")
    private val SO_BAD = Regex("miner|xmr|cryptonight|cpuminer|coinhive")
    private val DEX = Regex("classes\\d*\\.dex")
    private val XMR = Regex("4[0-9AB][1-9A-HJ-NP-Za-km-z]{93}")
    private val BTC = Regex("bc1[a-z0-9]{38,58}")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun certs(pi: PackageInfo): List<String> {
        val s = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        return s?.map { sha(it.toByteArray()) } ?: emptyList()
    }

    private fun trackers(c: Context): List<Pair<String, List<String>>> {
        val f = File(c.filesDir, "trackers.txt")
        val txt = if (f.exists()) f.readText() else c.assets.open("trackers.txt").bufferedReader().readText()
        return txt.lines().filter { '|' in it }.map { l ->
            val (n, s) = l.split('|', limit = 2)
            n to s.split(',').map { it.trim().lowercase() }.filter { it.length > 5 }
        }
    }

    private fun http(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 30000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val s = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to s
    }

    suspend fun refreshTrackers(c: Context): Int = withContext(Dispatchers.IO) {
        val j = JSONObject(http("https://reports.exodus-privacy.eu.org/api/trackers").second).getJSONObject("trackers")
        val sb = StringBuilder(); var n = 0
        for (k in j.keys()) {
            val t = j.getJSONObject(k); val sig = t.optString("code_signature")
            if (sig.isBlank()) continue
            sb.append(t.optString("name").replace("|", " ")).append('|')
                .append(sig.replace("\\", "").replace('.', '/').replace('|', ',')).append('\n'); n++
        }
        File(c.filesDir, "trackers.txt").writeText(sb.toString()); n
    }

    private fun scanStream(s: InputStream, dex: Boolean, tr: List<Pair<String, List<String>>>,
                           hits: MutableSet<String>, trHit: MutableSet<String>, wallets: MutableSet<String>) {
        val buf = ByteArray(1 shl 18); var carry = ""
        while (true) {
            val n = s.read(buf); if (n < 0) break
            val t = carry + String(buf, 0, n, Charsets.ISO_8859_1); val l = t.lowercase()
            MINER.forEach { if (l.contains(it)) hits += it }
            XMR.find(t)?.let { wallets += "XMR " + it.value.take(10) + "…" }
            BTC.find(t)?.let { wallets += "BTC " + it.value.take(10) + "…" }
            if (dex) tr.forEach { (name, sigs) -> if (name !in trHit && sigs.any { l.contains(it) }) trHit += name }
            carry = t.takeLast(128)
        }
    }

    suspend fun scan(c: Context, path: String, refPath: String?, vt: String, mb: String): Report = withContext(Dispatchers.IO) {
        val pm = c.packageManager
        val fl = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val pi = pm.getPackageArchiveInfo(path, fl) ?: error("Not a valid APK")
        pi.applicationInfo?.let { it.sourceDir = path; it.publicSourceDir = path }
        val name = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
        val f = mutableListOf<Finding>()

        val req = pi.requestedPermissions?.toList() ?: emptyList()
        req.forEach { p ->
            val k = p.substringAfterLast('.')
            PERMS[k]?.let { f += Finding(if (it.first >= 6) 2 else 1, "Requests $k: ${it.second}", it.first) }
        }
        val svc = pi.services ?: emptyArray<ServiceInfo>()
        val rec = pi.receivers ?: emptyArray()
        if (svc.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
            f += Finding(2, "Runs an accessibility service: it can read your screen and tap for you", 14)
        if (svc.any { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" })
            f += Finding(2, "Can read all your notifications", 10)
        if (rec.any { it.permission == "android.permission.BIND_DEVICE_ADMIN" })
            f += Finding(2, "Registers as device admin: hard to uninstall", 12)
        val exp = svc.count { it.exported && it.permission == null } + rec.count { it.exported && it.permission == null }
        if (exp > 3) f += Finding(1, "$exp exported services/receivers with no permission guard", minOf(exp, 10))

        val cs = certs(pi)
        val ref: PackageInfo? = if (refPath != null) pm.getPackageArchiveInfo(refPath, fl)
            else try { pm.getPackageInfo(pi.packageName, fl) } catch (_: Exception) { null }
        if (ref == null) f += Finding(0, "No original to compare. Pick the original APK to see signer changes", 0)
        else {
            val rc = certs(ref)
            if (ref.packageName != pi.packageName) f += Finding(1, "Original has a different package name (${ref.packageName})", 5)
            if (cs.toSet() == rc.toSet()) f += Finding(0, "Signed by the same certificate as the original", 0)
            else f += Finding(2, "Re-signed: certificate ${cs.firstOrNull()?.take(16)}… differs from original ${rc.firstOrNull()?.take(16)}…", 20)
            val added = req.toSet() - (ref.requestedPermissions?.toSet() ?: emptySet())
            if (added.isNotEmpty()) f += Finding(1, "Mod adds ${added.size} permissions: ${added.take(6).joinToString { it.substringAfterLast('.') }}", minOf(added.size * 2, 12))
        }

        val hits = sortedSetOf<String>(); val trHit = sortedSetOf<String>(); val wallets = sortedSetOf<String>()
        val badSo = mutableListOf<String>(); val tr = trackers(c)
        ZipFile(path).use { z ->
            z.entries().asSequence().forEach { e ->
                val dex = DEX.matches(e.name); val so = e.name.endsWith(".so")
                if (so && SO_BAD.containsMatchIn(e.name.substringAfterLast('/').lowercase())) badSo += e.name
                if (dex || so) z.getInputStream(e).use { scanStream(it, dex, tr, hits, trHit, wallets) }
            }
        }
        if (hits.isNotEmpty()) f += Finding(2, "Crypto-miner strings in code: ${hits.joinToString()}", minOf(hits.size * 12, 40))
        if (wallets.isNotEmpty()) f += Finding(2, "Crypto wallet address inside code: ${wallets.joinToString()}", 15)
        if (badSo.isNotEmpty()) f += Finding(2, "Suspicious native libraries: ${badSo.take(4).joinToString()}", 15)
        if (trHit.isNotEmpty()) f += Finding(1, "${trHit.size} trackers/ad SDKs: ${trHit.take(8).joinToString()}", minOf(trHit.size * 2, 16))
        else f += Finding(0, "No known trackers found", 0)

        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val h = hex(md.digest())
        if (vt.isNotBlank()) try {
            val (code, body) = http("https://www.virustotal.com/api/v3/files/$h", headers = mapOf("x-apikey" to vt))
            if (code == 200) {
                val m = JSONObject(body).getJSONObject("data").getJSONObject("attributes").getJSONObject("last_analysis_stats").getInt("malicious")
                f += if (m > 0) Finding(2, "VirusTotal: $m engines flag this file", minOf(m * 5, 40)) else Finding(0, "VirusTotal knows this file: 0 detections", 0)
            } else if (code == 404) f += Finding(0, "VirusTotal has never seen this exact file (normal for a fresh mod)", 0)
            else f += Finding(0, "VirusTotal lookup failed ($code)", 0)
        } catch (_: Exception) { f += Finding(0, "VirusTotal lookup failed: offline?", 0) }
        if (mb.isNotBlank()) try {
            val body = http("https://mb-api.abuse.ch/api/v1/", "POST", mapOf("Auth-Key" to mb, "Content-Type" to "application/x-www-form-urlencoded"), "query=get_info&hash=$h").second
            f += when (val st = JSONObject(body).optString("query_status")) {
                "ok" -> Finding(2, "MalwareBazaar: this exact file is a known malware sample", 50)
                "hash_not_found" -> Finding(0, "MalwareBazaar: hash not in database", 0)
                else -> Finding(0, "MalwareBazaar: $st", 0)
            }
        } catch (_: Exception) { f += Finding(0, "MalwareBazaar lookup failed: offline?", 0) }
        f += Finding(0, "SHA-256 ${h.take(16)}…", 0)
        Report(name, pi.packageName, minOf(100, f.sumOf { it.w }), f.sortedByDescending { it.sev * 100 + it.w })
    }
}
