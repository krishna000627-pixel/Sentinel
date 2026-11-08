@file:Suppress("DEPRECATION")
package com.sentinel.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

/** soft = permissions, trackers and other "might be normal" points. They are capped so they can never reach High risk alone. */
data class Finding(val sev: Int, val text: String, val w: Int, val soft: Boolean = false)
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
    private val BTC = Regex("bc1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{39,87}")
    private const val B32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val PAY_PKGS = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.paypal.android.p2pmobile", "com.squareup.cash", "com.venmo", "com.samsung.android.spay", "com.google.android.apps.walletnfcrel",
        "com.mobikwik_new", "com.freecharge.android", "com.dreamplug.androidapp")
    private val PAY_WORDS = listOf("bank", "wallet", "paisa", "bhim", "payment", "paypal", "paytm", "phonepe", "gpay", "razorpay", "mobikwik", "freecharge")
    /** Permissions that payment apps normally need (SIM binding, QR scan, contacts, always-on). */
    private val PAY_OK = setOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "CAMERA", "READ_CONTACTS", "ACCESS_BACKGROUND_LOCATION",
        "QUERY_ALL_PACKAGES", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED")

    private fun isPayment(pkg: String, label: String): Boolean {
        if (pkg in PAY_PKGS) return true
        val segs = pkg.lowercase().split('.')
        if (segs.any { s -> s == "upi" || s.startsWith("upi") || PAY_WORDS.any { s.contains(it) } }) return true
        val l = label.lowercase()
        return PAY_WORDS.any { l.contains(it) } || l.split(' ').any { it == "pay" || it == "upi" }
    }

    /** True only for a real Bitcoin bech32/bech32m checksum, so hex hashes starting with "bc1" no longer count. */
    private fun bech32ok(a: String): Boolean {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        val v = ArrayList<Int>()
        for (ch in "bc") v += ch.code shr 5
        v += 0
        for (ch in "bc") v += ch.code and 31
        for (ch in a.substring(3)) { val i = B32.indexOf(ch); if (i < 0) return false; v += i }
        var chk = 1
        for (x in v) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor x
            for (i in 0..4) if (((b ushr i) and 1) == 1) chk = chk xor gen[i]
        }
        return chk == 1 || chk == 0x2bc830a3
    }

    private fun btcIn(t: String): String? {
        for (m in BTC.findAll(t)) for (len in intArrayOf(42, 62)) {
            if (m.value.length >= len && bech32ok(m.value.take(len))) return m.value.take(len)
        }
        return null
    }

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
                    btcIn(t)?.let { w += "BTC " + it.take(10) + "…" }
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

    private class RawManifest(val pkg: String, val perms: List<String>, val svcPerms: List<String>, val rcvPerms: List<String>)

    /** Reads the binary AndroidManifest.xml straight from the zip, for APKs the system parser refuses. */
    private fun rawManifest(path: String): RawManifest? = try {
        val b = ZipFile(path).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: return@use null
            z.getInputStream(e).use { it.readBytes() }
        }
        if (b == null) null else parseAxml(b)
    } catch (_: Throwable) { null }

    private fun parseAxml(b: ByteArray): RawManifest? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(o: Int) = bb.getShort(o).toInt() and 0xffff
        fun i32(o: Int) = bb.getInt(o)
        if (b.size < 16 || u16(0) != 0x0003) return null
        var strings: List<String> = emptyList()
        var pkg = ""
        val perms = ArrayList<String>(); val sp = ArrayList<String>(); val rp = ArrayList<String>()
        var off = u16(2)
        val end = minOf(i32(4), b.size)
        while (off + 8 <= end) {
            val type = u16(off); val size = i32(off + 4)
            if (size <= 0) break
            if (type == 0x0001) {
                val count = i32(off + 8); val utf8 = (i32(off + 16) and 0x100) != 0
                val sStart = i32(off + 20); val offs = off + u16(off + 2)
                strings = (0 until count).map { i ->
                    try {
                        var so = off + sStart + i32(offs + 4 * i)
                        if (utf8) {
                            so += if ((b[so].toInt() and 0x80) != 0) 2 else 1
                            var bl = b[so].toInt() and 0xff
                            if ((bl and 0x80) != 0) { bl = ((bl and 0x7f) shl 8) or (b[so + 1].toInt() and 0xff); so += 2 } else so += 1
                            String(b, so, bl, Charsets.UTF_8)
                        } else {
                            var len = u16(so); var q = so + 2
                            if ((len and 0x8000) != 0) { len = ((len and 0x7fff) shl 16) or u16(so + 2); q = so + 4 }
                            String(b, q, len * 2, Charsets.UTF_16LE)
                        }
                    } catch (_: Throwable) { "" }
                }
            } else if (type == 0x0102 && strings.isNotEmpty()) {
                val tag = strings.getOrNull(i32(off + 20)) ?: ""
                val base = off + 16 + u16(off + 24); val asz = u16(off + 26); val ac = u16(off + 28)
                var nm = ""; var perm = ""; var pk = ""
                for (i in 0 until ac) {
                    val a = base + i * asz
                    if (a + 20 > b.size) break
                    val an = strings.getOrNull(i32(a + 4)) ?: ""
                    val rv = i32(a + 8)
                    val v = if (rv >= 0) strings.getOrNull(rv) ?: "" else if ((b[a + 15].toInt() and 0xff) == 0x03) strings.getOrNull(i32(a + 16)) ?: "" else ""
                    when (an) { "name" -> nm = v; "permission" -> perm = v; "package" -> pk = v }
                }
                when (tag) {
                    "manifest" -> if (pk.isNotEmpty()) pkg = pk
                    "uses-permission", "uses-permission-sdk-23" -> if (nm.isNotEmpty()) perms += nm
                    "service" -> if (perm.isNotEmpty()) sp += perm
                    "receiver" -> if (perm.isNotEmpty()) rp += perm
                }
            }
            off += size
        }
        return if (pkg.isEmpty() && perms.isEmpty()) null else RawManifest(pkg.ifEmpty { "unknown" }, perms, sp, rp)
    }

    @Suppress("DEPRECATION")
    private fun synth(r: RawManifest, path: String): PackageInfo {
        val pi = PackageInfo()
        pi.packageName = r.pkg
        pi.requestedPermissions = r.perms.toTypedArray()
        val ai = ApplicationInfo()
        ai.packageName = r.pkg; ai.sourceDir = path; ai.publicSourceDir = path; ai.nonLocalizedLabel = r.pkg
        pi.applicationInfo = ai
        pi.services = r.svcPerms.map { p -> ServiceInfo().also { it.permission = p } }.toTypedArray()
        pi.receivers = r.rcvPerms.map { p -> ActivityInfo().also { it.permission = p } }.toTypedArray()
        return pi
    }

    /** Reads an APK with the system parser, trying lighter flag sets, and falls back to reading the manifest ourselves. */
    @Suppress("DEPRECATION")
    private fun readApk(c: Context, path: String): PackageInfo? {
        val pm = c.packageManager
        val sign = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val a = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        for (fl in intArrayOf(a or sign, a, PackageManager.GET_PERMISSIONS, 0)) {
            val pi = try { pm.getPackageArchiveInfo(path, fl) } catch (_: Throwable) { null }
            if (pi != null) {
                pi.applicationInfo?.let { it.sourceDir = path; it.publicSourceDir = path }
                return pi
            }
        }
        return rawManifest(path)?.let { synth(it, path) }
    }

    /** Returns a path to a real APK: the file itself, or the base APK inside an .xapk / .apks / .apkm bundle. */
    private fun prepare(c: Context, path: String, outName: String): String {
        val f = File(path)
        if (!f.isFile || f.length() < 4) error("Not a valid APK: the file is empty or could not be read")
        val z = try { ZipFile(f) } catch (_: Exception) { error("Not a valid APK: this is not a ZIP archive. Pick the .apk file itself") }
        return z.use { zf ->
            if (zf.getEntry("AndroidManifest.xml") != null) return@use path
            val apks = zf.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.toList()
            if (apks.isEmpty()) error("Not a valid APK: no AndroidManifest.xml inside")
            val pick = apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) } ?: apks.maxByOrNull { it.size }!!
            val out = File(c.cacheDir, outName)
            zf.getInputStream(pick).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out.path
        }
    }

    /** Pre-install scan of an APK file (optionally compared with the original), with optional cloud hash checks. */
    @Suppress("DEPRECATION")
    suspend fun scan(c: Context, path: String, refPath: String?, vt: String, mb: String): Report = withContext(Dispatchers.IO) {
        val pm = c.packageManager
        val apk = prepare(c, path, "scan_base.apk")
        val pi = readApk(c, apk) ?: error("Not a valid APK: Android could not read it and its manifest is damaged")
        val f = mutableListOf<Finding>()
        val req = pi.requestedPermissions?.toList() ?: emptyList()
        val cs = certs(pi)
        val refApk = refPath?.let { try { prepare(c, it, "ref_base.apk") } catch (_: Exception) { null } }
        val ref: PackageInfo? = if (refPath != null) refApk?.let { readApk(c, it) }
            else try {
                pm.getPackageInfo(pi.packageName, PackageManager.GET_PERMISSIONS or
                    (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES))
            } catch (_: Exception) { null }
        if (cs.isEmpty()) f += Finding(1, "Signing certificate could not be read (unsigned, or the signature is damaged)", 6)
        if (ref == null) f += Finding(0, if (refPath != null) "The original file could not be read, so nothing was compared" else "No original to compare. Pick the original APK to see signer changes", 0)
        else {
            val rc = certs(ref)
            if (ref.packageName != pi.packageName) f += Finding(1, "Original has a different package name (${ref.packageName})", 5)
            if (cs.isEmpty() || rc.isEmpty()) f += Finding(0, "Signer comparison skipped: a certificate could not be read", 0)
            else if (cs.toSet() == rc.toSet()) f += Finding(0, "Signed by the same certificate as the original", 0)
            else f += Finding(2, "Re-signed: certificate ${cs.firstOrNull()?.take(16)}… differs from original ${rc.firstOrNull()?.take(16)}…", 20)
            val added = req.toSet() - (ref.requestedPermissions?.toSet() ?: emptySet())
            if (added.isNotEmpty()) f += Finding(1, "Mod adds ${added.size} permissions: ${added.take(6).joinToString { it.substringAfterLast('.') }}", minOf(added.size * 2, 12), true)
        }
        analyze(c, pi, apk, f, vt, mb, false)
    }

    /** Fast scan of an app that is already installed (used for background audits). No network, no signer comparison. */
    suspend fun scanInstalled(c: Context, pkg: String): Report = withContext(Dispatchers.IO) {
        val pi = c.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        val path = pi.applicationInfo?.sourceDir ?: error("No APK for $pkg")
        analyze(c, pi, path, mutableListOf(), "", "", true)
    }

    private fun analyze(c: Context, pi: PackageInfo, path: String, f: MutableList<Finding>, vt: String, mb: String, quick: Boolean): Report {
        val pm = c.packageManager
        val name = try { pi.applicationInfo?.loadLabel(pm)?.toString() } catch (_: Throwable) { null } ?: pi.packageName

        val req = pi.requestedPermissions?.toList() ?: emptyList()
        val pay = isPayment(pi.packageName, name)
        req.forEach { p ->
            val k = p.substringAfterLast('.')
            PERMS[k]?.let {
                f += if (pay && k in PAY_OK) Finding(1, "Requests $k: ${it.second} (normal for payment apps)", 1, true)
                else Finding(if (it.first >= 6) 2 else 1, "Requests $k: ${it.second}", it.first, true)
            }
        }
        if (pay) f += Finding(0, "Looks like a payment app: SMS, camera and contacts access is normal here", 0)
        val svc = pi.services ?: emptyArray<ServiceInfo>()
        val rec = pi.receivers ?: emptyArray()
        if (svc.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
            f += Finding(2, "Runs an accessibility service: it can read your screen and tap for you", 14)
        if (svc.any { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" })
            f += Finding(2, "Can read all your notifications", 10)
        if (rec.any { it.permission == "android.permission.BIND_DEVICE_ADMIN" })
            f += Finding(2, "Registers as device admin: hard to uninstall", 12)
        val a11y = svc.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
        val overlay = req.any { it.substringAfterLast('.') == "SYSTEM_ALERT_WINDOW" }
        if (a11y && overlay) f += Finding(2, "Overlay plus accessibility together: the classic banking-trojan combination", 20)
        val exp = svc.count { it.exported && it.permission == null } + rec.count { it.exported && it.permission == null }
        if (exp > 3) f += Finding(1, "$exp exported services/receivers with no permission guard", minOf(exp, 10), true)

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
        if (trHit.isNotEmpty()) f += Finding(1, "${trHit.size} trackers/ad SDKs: ${trHit.take(8).joinToString()}", minOf(trHit.size * 2, 16), true)
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
        val hardPts = f.filter { !it.soft }.sumOf { it.w }
        val softPts = minOf(f.filter { it.soft }.sumOf { it.w }, if (pay) 20 else 40)
        return Report(name, pi.packageName, minOf(100, hardPts + softPts), f.sortedByDescending { it.sev * 100 + it.w })
    }
}
