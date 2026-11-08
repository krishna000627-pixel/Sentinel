package com.sentinel.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Dark = darkColorScheme(
    primary = Color(0xFF3DDC97), onPrimary = Color(0xFF06140D), background = Color(0xFF0B0F14),
    surface = Color(0xFF131A22), surfaceVariant = Color(0xFF1B242E), error = Color(0xFFFF5C5C)
)
private val Red = Color(0xFFFF5C5C); private val Amber = Color(0xFFFFB84D); private val Green = Color(0xFF3DDC97)
private val Muted = Color(0xFF8D9BAA)
private fun sc(s: Int) = if (s >= 55) Red else if (s >= 25) Amber else Green
private fun lvl(s: Int) = if (s >= 55) "High risk" else if (s >= 25) "Medium risk" else "Low risk"
private fun fmt(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
private fun Report.entity() = ScanEntity(name = name, pkg = pkg, score = score, text = text(), time = System.currentTimeMillis())

@Suppress("DEPRECATION")
private fun installedPkgs(c: android.content.Context): Set<String> =
    try { c.packageManager.getInstalledPackages(0).map { it.packageName }.toSet() } catch (_: Exception) { emptySet() }

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    @Suppress("DEPRECATION")
    private fun pick(i: Intent?) {
        incoming.value = when (i?.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (s == null) pick(intent)
        setContent { MaterialTheme(colorScheme = Dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(incoming) } } }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); pick(i) }
}

@Composable private fun Panel(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(mod.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }

@Composable private fun Title(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp)

@Composable private fun Chip(label: String, on: Boolean, onClick: () -> Unit) =
    Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = if (on) Green.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp,
            color = if (on) Green else Color.White, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }

@Composable private fun Tag(t: String, col: Color) =
    Text(t, Modifier.clip(RoundedCornerShape(6.dp)).background(col.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = col, fontSize = 10.sp, fontWeight = FontWeight.Bold)

@Composable private fun Findings(text: String) {
    text.lines().filter { it.isNotBlank() }.forEach { l ->
        val sev = if (l.startsWith("✖")) 2 else if (l.startsWith("!")) 1 else 0
        Row(verticalAlignment = Alignment.Top) {
            Text("●", color = if (sev == 2) Red else if (sev == 1) Amber else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.width(8.dp))
            Text(l.drop(2), fontSize = 13.sp)
        }
    }
}

@Composable private fun SafeButton(isSafe: Boolean, onClick: () -> Unit) =
    if (isSafe) OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("✓ Safe · undo", fontSize = 12.sp) }
    else OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("Mark as safe", fontSize = 12.sp) }

@Composable private fun Tile(title: String, sub: String, icon: ImageVector, accent: Color, mod: Modifier, onClick: () -> Unit) =
    Card(mod.height(112.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent)
            }
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1)
            }
        }
    }

private val PAGE_TITLES = mapOf("scan" to "Scan", "apps" to "My apps", "monitor" to "Monitor", "history" to "History", "access" to "Access", "settings" to "Settings")

@Composable fun App(incoming: MutableState<Uri?>) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val dao = remember { Db.get(c).dao() }
    val prefs = remember { c.getSharedPreferences("s", 0) }
    var page by rememberSaveable { mutableStateOf("home") }
    var report by remember { mutableStateOf<Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var apk by remember { mutableStateOf<String?>(null) }
    var ref by remember { mutableStateOf<String?>(null) }
    var auditRun by remember { mutableStateOf(false) }
    var auditDone by remember { mutableIntStateOf(0) }
    var auditTotal by remember { mutableIntStateOf(0) }
    var auditLabel by remember { mutableStateOf("") }
    var lastAudit by remember { mutableStateOf(prefs.getString("last_audit", "") ?: "") }
    var rescanning by remember { mutableStateOf<String?>(null) }
    val blocked by remember { dao.blocks() }.collectAsState(emptyList())
    val scans by remember { dao.scans() }.collectAsState(emptyList())
    val safeList by remember { dao.safes() }.collectAsState(emptyList())
    val safe = remember(safeList) { safeList.toSet() }
    val inst = remember(lastAudit, auditRun) { installedPkgs(c) }
    val latest = remember(scans, inst) { scans.distinctBy { it.pkg }.filter { it.pkg in inst } }
    val high = latest.count { it.score >= 55 && it.pkg !in safe }
    val med = latest.count { it.score in 25..54 && it.pkg !in safe }
    val safeN = latest.count { it.pkg in safe }

    fun setSafe(pkg: String, on: Boolean) { scope.launch { if (on) dao.markSafe(SafeEntity(pkg)) else dao.unmarkSafe(SafeEntity(pkg)) } }

    fun copy(u: Uri, n: String): String {
        val f = File(c.cacheDir, n)
        c.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f.path
    }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { apk = withContext(Dispatchers.IO) { copy(u, "scan.apk") } }
    }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { ref = withContext(Dispatchers.IO) { copy(u, "ref.apk") } }
    }
    fun run(path: String, refP: String?) {
        if (busy) return
        scope.launch {
            busy = true; err = null
            try {
                val r = ApkScanner.scan(c, path, refP, prefs.getString("vt", "")!!, prefs.getString("mb", "")!!)
                report = r; page = "scan"
                dao.add(r.entity())
            } catch (e: Exception) { err = e.message ?: "Scan failed" }
            busy = false
        }
    }
    fun rescan(pkg: String) {
        if (rescanning != null) return
        scope.launch {
            rescanning = pkg
            try { dao.add(ApkScanner.scanInstalled(c, pkg).entity()) } catch (_: Exception) { err = "Could not rescan that app" }
            rescanning = null
        }
    }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) Monitor.applyVpn(c, blocked)
    }
    LaunchedEffect(blocked) {
        val i = VpnService.prepare(c)
        if (blocked.isNotEmpty() && i != null) vpnLauncher.launch(i) else Monitor.applyVpn(c, blocked)
    }

    LaunchedEffect(incoming.value) {
        incoming.value?.let { u ->
            incoming.value = null
            try {
                val p = withContext(Dispatchers.IO) { copy(u, "scan.apk") }
                apk = p; run(p, null)
            } catch (e: Exception) { err = "Could not open that file" }
        }
    }
    val wm = remember { WorkManager.getInstance(c) }
    LaunchedEffect(Unit) {
        while (true) {
            val infos = try { withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork("audit").get() } } catch (_: Exception) { emptyList() }
            val cur = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            auditRun = cur != null
            auditDone = cur?.progress?.getInt("done", 0) ?: 0
            auditTotal = cur?.progress?.getInt("total", 0) ?: 0
            auditLabel = cur?.progress?.getString("label") ?: ""
            lastAudit = prefs.getString("last_audit", "") ?: ""
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        if (prefs.getBoolean("auto", true)) Background.auto(c, true)
        if (prefs.getString("last_audit", "").isNullOrEmpty()) Background.scanNow(c)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false) &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = page != "home") { page = "home" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page == "home") {
                    Spacer(Modifier.width(4.dp))
                    Image(painterResource(R.drawable.ic_logo), null, Modifier.size(40.dp))
                    Text("Sentinel", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                } else {
                    IconButton({ page = "home" }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text(PAGE_TITLES[page] ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            when (page) {
                "home" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val col = if (high > 0) Red else if (med > 0) Amber else Green
                    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { page = "apps" }, shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = col.copy(alpha = 0.14f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (auditRun) "Checking your apps…" else if (high > 0) "$high high-risk app${if (high == 1) "" else "s"}"
                                else if (med > 0) "$med app${if (med == 1) "" else "s"} to review" else "All clear",
                                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = col
                            )
                            if (auditRun) {
                                if (auditTotal > 0) LinearProgressIndicator(progress = auditDone / auditTotal.toFloat(), modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (auditTotal > 0) "$auditDone/$auditTotal  $auditLabel" else "Starting…", fontSize = 12.sp, maxLines = 1)
                            } else {
                                Text("${latest.size} apps checked" + (if (high > 0 && med > 0) ", $med more medium" else "") +
                                    (if (safeN > 0) ", $safeN marked safe" else ""), fontSize = 13.sp)
                                val la = lastAudit.split('|'); val t = la.getOrNull(0)?.toLongOrNull()
                                if (t != null) Text("Last check ${fmt(t)}", fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("Scan", "Check an APK or all apps", Icons.Default.Search, Green, Modifier.weight(1f)) { page = "scan" }
                        Tile("My apps", if (high + med > 0) "${high + med} to review" else "Nothing to review", Icons.Default.Menu, if (high > 0) Red else Amber, Modifier.weight(1f)) { page = "apps" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("Monitor", "Network use and block", Icons.Default.Lock, Color(0xFF6AA9FF), Modifier.weight(1f)) { page = "monitor" }
                        Tile("History", "${scans.size} saved results", Icons.Default.DateRange, Color(0xFFB794F6), Modifier.weight(1f)) { page = "history" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("Access", "Powerful permissions", Icons.Default.Warning, Amber, Modifier.weight(1f)) { page = "access" }
                        Tile("Settings", "Keys, background, trackers", Icons.Default.Settings, Muted, Modifier.weight(1f)) { page = "settings" }
                    }
                }
                "scan" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    item {
                        Panel {
                            Title("Pre-install scan")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ pickApk.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text(if (apk == null) "Pick APK" else "APK ✓", maxLines = 1) }
                                OutlinedButton({ pickRef.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text(if (ref == null) "Original (optional)" else "Original ✓", maxLines = 1) }
                            }
                            Button({ run(apk!!, ref) }, Modifier.fillMaxWidth(), enabled = apk != null && !busy) { Text(if (busy) "Scanning…" else "Scan") }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            err?.let { Text(it, color = Red, fontSize = 13.sp) }
                        }
                    }
                    item {
                        Panel {
                            Title("Installed apps")
                            Text("Runs in the background, so you can leave the app.", fontSize = 12.sp, color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ auditRun = true; Background.scanNow(c) }, Modifier.weight(1f), enabled = !auditRun) { Text("Scan new apps", maxLines = 1) }
                                OutlinedButton({ auditRun = true; Background.scanNow(c, true) }, Modifier.weight(1f), enabled = !auditRun) { Text("Rescan all", maxLines = 1) }
                            }
                            if (auditRun) {
                                if (auditTotal > 0) LinearProgressIndicator(progress = auditDone / auditTotal.toFloat(), modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (auditTotal > 0) "Scanning $auditDone/$auditTotal  $auditLabel" else "Starting…", fontSize = 12.sp, maxLines = 1)
                            } else {
                                val la = lastAudit.split('|')
                                val t = la.getOrNull(0)?.toLongOrNull()
                                if (la.size == 3 && t != null) Text("Last check ${fmt(t)}: ${la[1]} apps scanned, ${la[2]} risky", fontSize = 12.sp)
                                TextButton({ page = "apps" }) { Text("Review risky apps →") }
                            }
                        }
                    }
                    report?.let { r ->
                        item {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${r.score}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = sc(r.score))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(lvl(r.score), color = sc(r.score), fontWeight = FontWeight.Bold)
                                        Text("${r.name} (${r.pkg})", fontSize = 12.sp)
                                        Text("${r.findings.count { it.sev == 2 }} serious, ${r.findings.count { it.sev == 1 }} warnings", fontSize = 12.sp, color = Muted)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({
                                        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, "Sentinel: ${r.name} ${r.score}/100 (${lvl(r.score)})\n" + r.text()), null))
                                    }) { Text("Share report") }
                                    if (r.pkg in inst) SafeButton(r.pkg in safe) { setSafe(r.pkg, r.pkg !in safe) }
                                }
                                Findings(r.text())
                            }
                        }
                    }
                }
                "apps" -> AppsPage(latest, safe, rescanning, ::setSafe, ::rescan)
                "monitor" -> MonitorTab(blocked, safe, ::setSafe, { p, on -> scope.launch { if (on) dao.block(BlockEntity(p)) else dao.unblock(BlockEntity(p)) } }) { src -> run(src, null) }
                "history" -> HistoryPage(scans, safe, ::setSafe)
                "access" -> AccessTab(prefs, safe, ::setSafe)
                else -> SettingsTab(prefs, safe.size) { scope.launch { dao.clearSafe() } }
            }
        }
    }
}

@Composable fun AppsPage(latest: List<ScanEntity>, safe: Set<String>, rescanning: String?, onSafe: (String, Boolean) -> Unit, onRescan: (String) -> Unit) {
    var f by remember { mutableIntStateOf(0) }
    val shown = remember(latest, safe, f) {
        when (f) {
            0 -> latest.filter { it.score >= 25 && it.pkg !in safe }.sortedByDescending { it.score }
            1 -> latest.filter { it.pkg in safe }.sortedBy { it.name.lowercase() }
            else -> latest.sortedByDescending { it.score }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Risky", f == 0) { f = 0 }
                Chip("Safe", f == 1) { f = 1 }
                Chip("All apps", f == 2) { f = 2 }
            }
        }
        if (shown.isEmpty()) item {
            Text(if (f == 0) "No risky apps. Nothing to review." else if (f == 1) "You haven't marked any app as safe." else "No scanned apps yet. Run a scan first.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(8.dp))
        }
        items(shown, key = { it.pkg }) { s ->
            var open by remember { mutableStateOf(false) }
            val isSafe = s.pkg in safe
            Panel(Modifier.clickable { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.score}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (isSafe) Green else sc(s.score), modifier = Modifier.width(52.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (isSafe) Tag("SAFE", Green) else Tag(lvl(s.score).uppercase(), sc(s.score))
                        }
                        Text(s.pkg, fontSize = 11.sp, color = Muted, maxLines = 1)
                    }
                }
                if (!open) {
                    s.text.lines().filter { it.startsWith("✖") || it.startsWith("!") }.take(2).forEach { Text(it.drop(2), fontSize = 12.sp, color = Muted, maxLines = 1) }
                } else Findings(s.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SafeButton(isSafe) { onSafe(s.pkg, !isSafe) }
                    TextButton({ onRescan(s.pkg) }, enabled = rescanning == null) { Text(if (rescanning == s.pkg) "Checking…" else "Rescan", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable fun HistoryPage(scans: List<ScanEntity>, safe: Set<String>, onSafe: (String, Boolean) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val dao = remember { Db.get(c).dao() }
    var byRisk by remember { mutableStateOf(false) }
    var flt by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val shown = remember(scans, safe, flt, byRisk) {
        val l = when (flt) {
            1 -> scans.filter { it.score >= 55 && it.pkg !in safe }
            2 -> scans.filter { it.score in 25..54 && it.pkg !in safe }
            3 -> scans.filter { it.score < 25 && it.pkg !in safe }
            4 -> scans.filter { it.pkg in safe }
            else -> scans
        }
        if (byRisk) l.sortedByDescending { it.score } else l
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Clear history?") }, text = { Text("This removes every saved scan result. Your safe-app marks are kept.") },
        confirmButton = { TextButton({ scope.launch { dao.clear() }; confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "High", "Medium", "Low", "Safe").forEachIndexed { i, l -> Chip(l, flt == i) { flt = i } }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} results", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
                TextButton({ byRisk = !byRisk }) { Text(if (byRisk) "Riskiest first" else "Newest first") }
                TextButton({
                    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, shown.joinToString("\n") { "${it.score}  ${it.name} (${it.pkg})" }), null))
                }) { Text("Export") }
                TextButton({ confirm = true }) { Text("Clear") }
            }
        }
        items(shown, key = { it.id }) { s ->
            var open by remember { mutableStateOf(false) }
            val isSafe = s.pkg in safe
            Panel(Modifier.clickable { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.score}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (isSafe) Green else sc(s.score), modifier = Modifier.width(52.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (isSafe) Tag("SAFE", Green)
                        }
                        Text(fmt(s.time), fontSize = 11.sp, color = Muted)
                    }
                    Text(if (open) "▲" else "▼", color = Muted, fontSize = 11.sp)
                }
                if (open) {
                    Findings(s.text)
                    SafeButton(isSafe) { onSafe(s.pkg, !isSafe) }
                }
            }
        }
    }
}

@Composable fun MonitorTab(blocked: List<String>, safe: Set<String>, onSafe: (String, Boolean) -> Unit, onBlock: (String, Boolean) -> Unit, onScan: (String) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppNet>>(emptyList()) }
    var ok by remember { mutableStateOf(Monitor.hasUsage(c)) }
    fun reload() {
        ok = Monitor.hasUsage(c)
        if (ok) scope.launch { apps = withContext(Dispatchers.Default) { Monitor.load(c) } }
    }
    var only by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reload() }
    val flagged = apps.filter { it.flags.isNotEmpty() && it.pkg !in safe }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Network use, last 24h", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton({ only = !only }) { Text(if (only) "All apps" else "Flagged only") }
                TextButton({ reload() }) { Text("Refresh") }
            }
        }
        if (!ok) item {
            Panel {
                Text("Allow usage access so Sentinel can read per-app network totals.", fontSize = 13.sp)
                Button({ c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access") }
            }
        }
        if (flagged.isNotEmpty()) item {
            OutlinedButton({ flagged.forEach { onBlock(it.pkg, true) } }, Modifier.fillMaxWidth()) { Text("Block all flagged apps") }
        }
        items(if (only) flagged else apps, key = { it.pkg }) { a ->
            val isSafe = a.pkg in safe
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(a.label, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (isSafe) Tag("SAFE", Green)
                        }
                        Text("↑ ${mb(a.tx)}   ↓ ${mb(a.rx)}   bg↑ ${mb(a.bgTx)}", fontSize = 12.sp)
                        if (!isSafe) a.flags.forEach { Text("● $it", color = sc(a.score), fontSize = 12.sp) }
                    }
                    TextButton({ onScan(a.src) }) { Text("Scan") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = a.pkg in blocked, onCheckedChange = { onBlock(a.pkg, it) })
                        Text("Block", fontSize = 10.sp)
                    }
                }
                if (a.flags.isNotEmpty() || isSafe) SafeButton(isSafe) { onSafe(a.pkg, !isSafe) }
            }
        }
    }
}

@Composable fun SettingsTab(prefs: SharedPreferences, safeCount: Int, onClearSafe: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var vt by remember { mutableStateOf(prefs.getString("vt", "")!!) }
    var mbk by remember { mutableStateOf(prefs.getString("mb", "")!!) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            Title("Cloud hash check (optional)")
            Text("Only the SHA-256 hash is sent, never the file.", fontSize = 12.sp, color = Muted)
            OutlinedTextField(vt, { vt = it; prefs.edit().putString("vt", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("VirusTotal API key") }, singleLine = true)
            OutlinedTextField(mbk, { mbk = it; prefs.edit().putString("mb", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("abuse.ch Auth-Key (MalwareBazaar)") }, singleLine = true)
        }
        Panel {
            var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("Check apps in the background")
                    Text("Scans new and updated apps every few hours and alerts you about risky ones.", fontSize = 12.sp, color = Muted)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; prefs.edit().putBoolean("auto", it).apply(); Background.auto(c, it) })
            }
        }
        Panel {
            Title("Safe apps")
            Text(if (safeCount == 0) "No apps marked as safe. Mark one from My apps, History, Monitor or Access." else "$safeCount app${if (safeCount == 1) "" else "s"} marked as safe: no alerts, not counted as risky.", fontSize = 12.sp, color = Muted)
            if (safeCount > 0) OutlinedButton(onClearSafe) { Text("Clear safe list") }
        }
        Panel {
            Title("Trackers")
            Button({ scope.launch { msg = try { "Updated: ${ApkScanner.refreshTrackers(c)} trackers" } catch (e: Exception) { "Update failed: are you offline?" } } }) { Text("Update from Exodus Privacy") }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
        }
        Panel {
            Title("Usage access")
            Button({ c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access") }
        }
    }
}

@Composable fun AccessTab(prefs: SharedPreferences, safe: Set<String>, onSafe: (String, Boolean) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Pair<String, Pair<String, Set<String>>>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var res by remember { mutableStateOf<List<String>?>(null) }
    var msg by remember { mutableStateOf("") }
    var showSafe by remember { mutableStateOf(false) }
    fun reload() {
        scope.launch {
            loading = true
            list = withContext(Dispatchers.Default) { Access.privileged(c) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    val visible = list.filter { it.first !in safe }
    val hidden = list.size - visible.size
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Panel {
                Title("Changes since baseline")
                Text("Save a baseline while your phone is clean. Later, check for new apps and new powerful access.", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ msg = "Baseline saved for ${Access.saveBaseline(prefs, list)} apps"; res = null }, Modifier.weight(1f), enabled = !loading) { Text("Save baseline") }
                    Button({
                        val r = Access.checkBaseline(prefs, visible)
                        if (r.firstOrNull()?.startsWith("No baseline") == true) { msg = "Save a baseline first"; res = null }
                        else { res = r; msg = if (r.isEmpty()) "No changes" else "${r.size} changes" }
                    }, Modifier.weight(1f), enabled = !loading) { Text("Check now") }
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp)
                res?.forEach { t -> Text("● $t", color = Amber, fontSize = 13.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Apps with powerful access", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton({ reload() }) { Text("Refresh") }
            }
        }
        if (loading) item { Text("Loading…", fontSize = 12.sp) }
        else if (visible.isEmpty()) item { Text("No unreviewed app has powerful access", fontSize = 12.sp, color = Green) }
        items(visible, key = { it.first }) { p ->
            Panel {
                Text(p.second.first, fontWeight = FontWeight.Bold)
                Text(p.second.second.joinToString(", "), color = Amber, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({
                        try { c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${p.first}"))) } catch (_: Exception) {}
                    }) { Text("Review") }
                    SafeButton(false) { onSafe(p.first, true) }
                }
            }
        }
        if (hidden > 0) {
            item { TextButton({ showSafe = !showSafe }) { Text(if (showSafe) "Hide $hidden safe app${if (hidden == 1) "" else "s"}" else "Show $hidden safe app${if (hidden == 1) "" else "s"}") } }
            if (showSafe) items(list.filter { it.first in safe }, key = { "s" + it.first }) { p ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.second.first, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false)); Tag("SAFE", Green)
                    }
                    Text(p.second.second.joinToString(", "), color = Muted, fontSize = 13.sp)
                    SafeButton(true) { onSafe(p.first, false) }
                }
            }
        }
    }
}
