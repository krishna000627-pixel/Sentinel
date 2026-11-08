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

    fun certs(pi: PackageInfo): List<String> {
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

    /** One-pass multi-pattern search (Aho-Corasick): cost is the file size, however many signatures there are. */
    private class Matcher(val names: List<String>, private val miners: Int, patterns: List<Pair<Int, String>>) {
        private val map = IntArray(256)
        private val k: Int
        private val next: IntArray
        private val out: IntArray
        private val term: IntArray
        private val link: IntArray

        init {
            var a = 1
            for ((_, p) in patterns) for (ch in p) { val i = ch.code and 255; if (map[i] == 0) map[i] = a++ }
            for (u in 'A'..'Z') map[u.code] = map[u.code + 32]
            k = a
            var total = 1
            for ((_, p) in patterns) total += p.length
            val nx = IntArray(total * k) { -1 }
            val o = IntArray(total) { -1 }
            var n = 1
            for ((id, p) in patterns) {
                var s = 0
                for (ch in p) {
                    val c = map[ch.code and 255]
                    var t = nx[s * k + c]
                    if (t < 0) { t = n++; nx[s * k + c] = t }
                    s = t
                }
                if (o[s] < 0) o[s] = id
            }
            val fail = IntArray(n); val lk = IntArray(n) { -1 }; val tm = IntArray(n) { -1 }
            val q = IntArray(n); var qh = 0; var qt = 0
            for (c in 0 until k) { val v = nx[c]; if (v < 0) nx[c] = 0 else { fail[v] = 0; q[qt++] = v } }
            while (qh < qt) {
                val u = q[qh++]
                lk[u] = tm[fail[u]]
                tm[u] = if (o[u] >= 0) u else lk[u]
                for (c in 0 until k) {
                    val v = nx[u * k + c]
                    val f = nx[fail[u] * k + c]
                    if (v < 0) nx[u * k + c] = f else { fail[v] = f; q[qt++] = v }
                }
            }
            next = nx; out = o; term = tm; link = lk
        }

        fun feed(s0: Int, b: ByteArray, len: Int, found: BooleanArray, trackers: Boolean): Int {
            var s = s0
            for (i in 0 until len) {
                s = next[s * k + map[b[i].toInt() and 255]]
                var t = term[s]
                while (t >= 0) {
                    val id = out[t]
                    if (trackers || id < miners) found[id] = true
                    t = link[t]
                }
            }
            return s
        }
    }

    private var cached: Matcher? = null
    private var cachedKey = ""

    @Synchronized private fun matcher(c: Context): Matcher {
        val f = File(c.filesDir, "trackers.txt")
        val key = if (f.exists()) "${f.length()}:${f.lastModified()}" else "assets"
        cached?.let { if (cachedKey == key) return it }
        val tr = trackers(c)
        val pats = ArrayList<Pair<Int, String>>()
        MINER.forEachIndexed { i, s -> pats += i to s }
        tr.forEachIndexed { i, t -> t.second.forEach { pats += (MINER.size + i) to it } }
        return Matcher(tr.map { it.first }, MINER.size, pats).also { cached = it; cachedKey = key }
    }

    private fun scanStream(s: InputStream, m: Matcher, found: BooleanArray, trackers: Boolean) {
        val buf = ByteArray(1 shl 16); var st = 0
        while (true) {
            val n = s.read(buf); if (n < 0) break
            st = m.feed(st, buf, n, found, trackers)
        }
    }

    /** Wallet addresses are only looked for when mining strings were already found (rare, so this stays cheap). */
    private fun findWallets(z: ZipFile): Set<String> {
        val w = sortedSetOf<String>()
        val es = z.entries()
        while (es.hasMoreElements()) {
            val e = es.nextElement()
            if (!(DEX.matches(e.name) || e.name.endsWith(".so"))) continue
            z.getInputStream(e).use { s ->
                val buf = ByteArray(1 shl 18); var carry = ""
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    val t = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                    XMR.find(t)?.let { w += "XMR " + it.value.take(10) + "…" }
                    BTC.find(t)?.let { w += "BTC " + it.value.take(10) + "…" }
                    carry = t.takeLast(128)
                }
            }
        }
        return w
    }

    private fun hashOf(path: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return hex(md.digest())
    }

    /** Pre-install scan of an APK file (optionally compared with the original), with optional cloud hash checks. */
    suspend fun scan(c: Context, path: String, refPath: String?, vt: String, mb: String): Report = withContext(Dispatchers.IO) {
        val pm = c.packageManager
        val fl = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val pi = pm.getPackageArchiveInfo(path, fl) ?: error("Not a valid APK")
        pi.applicationInfo?.let { it.sourceDir = path; it.publicSourceDir = path }
        val f = mutableListOf<Finding>()
        val req = pi.requestedPermissions?.toList() ?: emptyList()
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
        analyze(c, pi, path, f, vt, mb, false)
    }

    /** Fast scan of an app that is already installed (used for background audits). No network, no signer comparison. */
    suspend fun scanInstalled(c: Context, pkg: String): Report = withContext(Dispatchers.IO) {
        val pi = c.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        val path = pi.applicationInfo?.sourceDir ?: error("No APK for $pkg")
        analyze(c, pi, path, mutableListOf(), "", "", true)
    }

    private fun analyze(c: Context, pi: PackageInfo, path: String, f: MutableList<Finding>, vt: String, mb: String, quick: Boolean): Report {
        val pm = c.packageManager
        val name = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName

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

        val m = matcher(c)
        val found = BooleanArray(MINER.size + m.names.size)
        val badSo = mutableListOf<String>()
        ZipFile(path).use { z ->
            val es = z.entries()
            while (es.hasMoreElements()) {
                val e = es.nextElement()
                val dex = DEX.matches(e.name); val so = e.name.endsWith(".so")
                if (so && SO_BAD.containsMatchIn(e.name.substringAfterLast('/').lowercase())) badSo += e.name
                if (dex || so) z.getInputStream(e).use { scanStream(it, m, found, dex) }
            }
        }
        val hits = sortedSetOf<String>(); for (i in MINER.indices) if (found[i]) hits += MINER[i]
        val trHit = sortedSetOf<String>(); for (i in m.names.indices) if (found[MINER.size + i]) trHit += m.names[i]
        val wallets: Set<String> = if (hits.isNotEmpty()) ZipFile(path).use { findWallets(it) } else emptySet()
        if (hits.isNotEmpty()) f += Finding(2, "Crypto-miner strings in code: ${hits.joinToString()}", minOf(hits.size * 12, 40))
        if (wallets.isNotEmpty()) f += Finding(2, "Crypto wallet address inside code: ${wallets.joinToString()}", 15)
        if (badSo.isNotEmpty()) f += Finding(2, "Suspicious native libraries: ${badSo.take(4).joinToString()}", 15)
        if (trHit.isNotEmpty()) f += Finding(1, "${trHit.size} trackers/ad SDKs: ${trHit.take(8).joinToString()}", minOf(trHit.size * 2, 16))
        else f += Finding(0, "No known trackers found", 0)

        if (!quick) {
            val h = hashOf(path)
            if (vt.isNotBlank()) try {
                val (code, body) = http("https://www.virustotal.com/api/v3/files/$h", headers = mapOf("x-apikey" to vt))
                if (code == 200) {
                    val n = JSONObject(body).getJSONObject("data").getJSONObject("attributes").getJSONObject("last_analysis_stats").getInt("malicious")
                    f += if (n > 0) Finding(2, "VirusTotal: $n engines flag this file", minOf(n * 5, 40)) else Finding(0, "VirusTotal knows this file: 0 detections", 0)
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
        }
        return Report(name, pi.packageName, minOf(100, f.sumOf { it.w }), f.sortedByDescending { it.sev * 100 + it.w })
    }
}
