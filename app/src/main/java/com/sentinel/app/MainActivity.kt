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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
private fun sc(s: Int) = if (s >= 55) Red else if (s >= 25) Amber else Green
private fun lvl(s: Int) = if (s >= 55) "High risk" else if (s >= 25) "Medium risk" else "Low risk"

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
        pick(intent)
        setContent { MaterialTheme(colorScheme = Dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(incoming) } } }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); pick(i) }
}

@Composable private fun Panel(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(mod.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }

@Composable fun App(incoming: MutableState<Uri?>) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val dao = remember { Db.get(c).dao() }
    val prefs = remember { c.getSharedPreferences("s", 0) }
    var tab by remember { mutableIntStateOf(0) }
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
    var byRisk by remember { mutableStateOf(false) }
    val blocked by remember { dao.blocks() }.collectAsState(emptyList())
    val scans by remember { dao.scans() }.collectAsState(emptyList())

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
        scope.launch {
            busy = true; err = null
            try {
                val r = ApkScanner.scan(c, path, refP, prefs.getString("vt", "")!!, prefs.getString("mb", "")!!)
                report = r; tab = 0
                dao.add(ScanEntity(name = r.name, pkg = r.pkg, score = r.score, text = r.text(), time = System.currentTimeMillis()))
            } catch (e: Exception) { err = e.message ?: "Scan failed" }
            busy = false
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(40.dp))
                Text("Sentinel", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Scan" to Icons.Default.Search, "Monitor" to Icons.Default.Lock,
                    "History" to Icons.Default.DateRange, "Access" to Icons.Default.Warning, "Settings" to Icons.Default.Settings)
                    .forEachIndexed { i, (l, ic) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l, fontSize = 11.sp) })
                    }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(horizontal = 10.dp)) {
            when (tab) {
                0 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    item {
                        Panel {
                            Text("Pre-install scan", fontWeight = FontWeight.Bold)
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
                            Text("Installed apps", fontWeight = FontWeight.Bold)
                            Text("Runs in the background, so you can leave the app.", fontSize = 12.sp)
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
                                if (la.size == 3 && t != null)
                                    Text("Last check ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))}: ${la[1]} apps scanned, ${la[2]} risky", fontSize = 12.sp)
                            }
                        }
                    }
                    report?.let { r ->
                        item {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${r.score}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = sc(r.score))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(lvl(r.score), color = sc(r.score), fontWeight = FontWeight.Bold)
                                        Text("${r.name} (${r.pkg})", fontSize = 12.sp)
                                        Text("${r.findings.count { it.sev == 2 }} serious, ${r.findings.count { it.sev == 1 }} warnings", fontSize = 12.sp)
                                    }
                                }
                                TextButton({
                                    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "Sentinel: ${r.name} ${r.score}/100 (${lvl(r.score)})\n" + r.text()), null))
                                }) { Text("Share report") }
                                r.findings.forEach { f ->
                                    Row {
                                        Text(if (f.sev == 0) "○" else "●", color = if (f.sev == 2) Red else if (f.sev == 1) Amber else Color.Gray, fontSize = 12.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(f.text, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> MonitorTab(blocked, { p, on -> scope.launch { if (on) dao.block(BlockEntity(p)) else dao.unblock(BlockEntity(p)) } }) { src -> run(src, null) }
                2 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${scans.size} scans", Modifier.weight(1f))
                            TextButton({ byRisk = !byRisk }) { Text(if (byRisk) "Riskiest first" else "Newest first") }
                            TextButton({
                                c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, scans.joinToString("\n") { "${it.score}  ${it.name} (${it.pkg})" }), null))
                            }) { Text("Export") }
                            TextButton({ scope.launch { dao.clear() } }) { Text("Clear") }
                        }
                    }
                    items(if (byRisk) scans.sortedByDescending { it.score } else scans, key = { it.id }) { s ->
                        var open by remember { mutableStateOf(false) }
                        Panel(Modifier.clickable { open = !open }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${s.score}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = sc(s.score))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(s.name, fontWeight = FontWeight.Bold)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.time)), fontSize = 11.sp)
                                }
                            }
                            if (open) Text(s.text, fontSize = 12.sp)
                        }
                    }
                }
                3 -> AccessTab(prefs)
                else -> SettingsTab(prefs)
            }
        }
    }
}

@Composable fun MonitorTab(blocked: List<String>, onBlock: (String, Boolean) -> Unit, onScan: (String) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppNet>>(emptyList()) }
    var ok by remember { mutableStateOf(Monitor.hasUsage(c)) }
    fun reload() {
        ok = Monitor.hasUsage(c)
        if (ok) scope.launch { apps = withContext(Dispatchers.Default) { Monitor.load(c) } }
    }
    var only by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reload() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
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
        if (apps.any { it.flags.isNotEmpty() }) item {
            OutlinedButton({ apps.filter { it.flags.isNotEmpty() }.forEach { onBlock(it.pkg, true) } }, Modifier.fillMaxWidth()) { Text("Block all flagged apps") }
        }
        items(if (only) apps.filter { it.flags.isNotEmpty() } else apps, key = { it.pkg }) { a ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("↑ ${mb(a.tx)}   ↓ ${mb(a.rx)}   bg↑ ${mb(a.bgTx)}", fontSize = 12.sp)
                        a.flags.forEach { Text("● $it", color = sc(a.score), fontSize = 12.sp) }
                    }
                    TextButton({ onScan(a.src) }) { Text("Scan") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = a.pkg in blocked, onCheckedChange = { onBlock(a.pkg, it) })
                        Text("Block", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable fun SettingsTab(prefs: SharedPreferences) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var vt by remember { mutableStateOf(prefs.getString("vt", "")!!) }
    var mbk by remember { mutableStateOf(prefs.getString("mb", "")!!) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel {
            Text("Cloud hash check (optional)", fontWeight = FontWeight.Bold)
            Text("Only the SHA-256 hash is sent, never the file.", fontSize = 12.sp)
            OutlinedTextField(vt, { vt = it; prefs.edit().putString("vt", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("VirusTotal API key") }, singleLine = true)
            OutlinedTextField(mbk, { mbk = it; prefs.edit().putString("mb", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("abuse.ch Auth-Key (MalwareBazaar)") }, singleLine = true)
        }
        Panel {
            var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check apps in the background", fontWeight = FontWeight.Bold)
                    Text("Scans new and updated apps every few hours and alerts you about risky ones.", fontSize = 12.sp)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; prefs.edit().putBoolean("auto", it).apply(); Background.auto(c, it) })
            }
        }
        Panel {
            Text("Trackers", fontWeight = FontWeight.Bold)
            Button({ scope.launch { msg = try { "Updated: ${ApkScanner.refreshTrackers(c)} trackers" } catch (e: Exception) { "Update failed: are you offline?" } } }) { Text("Update from Exodus Privacy") }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
        }
        Panel {
            Text("Usage access", fontWeight = FontWeight.Bold)
            Button({ c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access") }
        }
    }
}

@Composable fun AccessTab(prefs: SharedPreferences) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Pair<String, Pair<String, Set<String>>>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var res by remember { mutableStateOf<List<String>?>(null) }
    var msg by remember { mutableStateOf("") }
    fun reload() {
        scope.launch {
            loading = true
            list = withContext(Dispatchers.Default) { Access.privileged(c) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Panel {
                Text("Changes since baseline", fontWeight = FontWeight.Bold)
                Text("Save a baseline while your phone is clean. Later, check for new apps and new powerful access.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ msg = "Baseline saved for ${Access.saveBaseline(prefs, list)} apps"; res = null }, Modifier.weight(1f), enabled = !loading) { Text("Save baseline") }
                    Button({
                        val r = Access.checkBaseline(prefs, list)
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
        else if (list.isEmpty()) item { Text("No third-party app has powerful access", fontSize = 12.sp, color = Green) }
        items(list, key = { it.first }) { p ->
            Panel {
                Text(p.second.first, fontWeight = FontWeight.Bold)
                Text(p.second.second.joinToString(", "), color = Amber, fontSize = 13.sp)
                TextButton({
                    try { c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${p.first}"))) } catch (_: Exception) {}
                }) { Text("Review") }
            }
        }
    }
}
