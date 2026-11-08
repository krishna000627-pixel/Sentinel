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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
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
    primary = Color(0xFF3DDC97), onPrimary = Color(0xFF06140D), background = Color(0xFF0A0E13),
    surface = Color(0xFF121921), surfaceVariant = Color(0xFF1A232E), error = Color(0xFFFF5C5C)
)
private val Red = Color(0xFFFF5C5C); private val Amber = Color(0xFFFFB84D); private val Green = Color(0xFF3DDC97)
private val Muted = Color(0xFF8696A7)
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

private val Line = Color(0xFF222D3A); private val Blue = Color(0xFF6AA9FF); private val Purple = Color(0xFFB794F6)
private val DOCK = setOf("home", "apps", "scan", "more")

private fun firstFinding(text: String): String? = text.lines().firstOrNull { it.startsWith("✖") || it.startsWith("!") }?.drop(2)

private fun dayLabel(t: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis
    return if (t >= today) "TODAY" else if (t >= today - 86400000L) "YESTERDAY"
    else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t)).uppercase()
}

@Composable private fun Panel(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(mod.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }

@Composable private fun Title(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp)

@Composable private fun Label(t: String) =
    Text(t, Modifier.padding(start = 4.dp, top = 6.dp), fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

@Composable private fun Chip(label: String, on: Boolean, onClick: () -> Unit) =
    Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = if (on) Green.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), fontSize = 13.sp,
            color = if (on) Green else Color.White, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }

@Composable private fun Tag(t: String, col: Color) =
    Text(t, Modifier.clip(RoundedCornerShape(7.dp)).background(col.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp),
        color = col, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)

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
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
        Text(if (isSafe) "✓ Safe · undo" else "Mark as safe", fontSize = 12.sp)
    }

@Composable private fun MiniBtn(t: String, onClick: () -> Unit) =
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp), shape = RoundedCornerShape(12.dp)) {
        Text(t, fontSize = 12.sp, maxLines = 1)
    }

@Composable private fun Pill(score: Int, col: Color) =
    Box(Modifier.width(46.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.15f)).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text("$score", color = col, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    }

@Composable private fun Item(col: Color, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val m = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
    Box(if (onClick != null) m.clickable(onClick = onClick) else m) {
        Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(28.dp).clip(RoundedCornerShape(3.dp)).background(col))
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun Bar(frac: Float, col: Color) =
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).fillMaxHeight().background(col))
    }

@Composable private fun Legend(t: String, col: Color) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(col))
        Spacer(Modifier.width(5.dp))
        Text(t, fontSize = 12.sp, color = Muted)
    }

@Composable private fun Ring(dia: Dp, parts: List<Pair<Float, Color>>, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(dia), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 12.dp.toPx(); val inset = sw / 2
            val box = Size(this.size.width - sw, this.size.height - sw)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(sw))
            var start = -90f
            parts.forEach { (f, col) ->
                if (f > 0f) {
                    val sweep = 360f * f
                    drawArc(color = col, startAngle = start, sweepAngle = (sweep - 2f).coerceAtLeast(1f), useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(sw))
                    start += sweep
                }
            }
        }
        content()
    }
}

@Composable private fun Trend(pts: List<Int>, col: Color) {
    Canvas(Modifier.fillMaxWidth().height(46.dp)) {
        if (pts.size < 2) return@Canvas
        val mx = pts.max().toFloat(); val mn = pts.min().toFloat()
        val rng = (mx - mn).coerceAtLeast(1f)
        val pad = 8.dp.toPx(); val w = this.size.width - 2 * pad; val h = this.size.height - 2 * pad
        val xy = pts.mapIndexed { i, v -> Offset(pad + w * i / (pts.size - 1), pad + h * (1f - (v - mn) / rng)) }
        val path = Path().apply { moveTo(xy[0].x, xy[0].y); xy.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, col, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(col, 4.dp.toPx(), xy.last())
    }
}

@Composable private fun Seg(opts: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
        opts.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                .background(if (i == sel) MaterialTheme.colorScheme.background else Color.Transparent)
                .clickable { onSel(i) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(o, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (i == sel) Color.White else Muted)
            }
        }
    }
}

@Composable private fun DockItem(glyph: String, label: String, on: Boolean, cta: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(70.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.clip(RoundedCornerShape(if (cta) 16.dp else 12.dp))
            .background(if (cta) Green else if (on) Green.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = if (cta) 7.dp else 4.dp)) {
            Text(glyph, fontSize = 18.sp, color = if (cta) Color(0xFF06140D) else if (on) Green else Muted,
                fontWeight = if (cta) FontWeight.ExtraBold else FontWeight.Normal)
        }
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (cta || on) Green else Muted)
    }
}

@Composable private fun Dock(page: String, onGo: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(22.dp)),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            DockItem("⌂", "Home", page == "home") { onGo("home") }
            DockItem("☰", "Apps", page == "apps") { onGo("apps") }
            DockItem("⌕", "Scan", page == "scan", cta = true) { onGo("scan") }
            DockItem("⋯", "More", page == "more") { onGo("more") }
        }
    }
}

@Composable private fun Group(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface), content = content)

@Composable private fun SetRow(title: String, sub: String? = null, last: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
        }
        trailing()
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable private fun Chev() = Text("›", color = Muted, fontSize = 20.sp)

private val PAGE_TITLES = mapOf("scan" to "Scan", "apps" to "My apps", "more" to "More", "monitor" to "Monitor", "history" to "History",
    "access" to "Access", "settings" to "Settings", "about" to "About")

@Composable fun App(incoming: MutableState<Uri?>) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val dao = remember { Db.get(c).dao() }
    val prefs = remember { c.getSharedPreferences("s", 0) }
    var page by rememberSaveable { mutableStateOf("home") }
    var trail by rememberSaveable { mutableStateOf("") }
    var scanTab by rememberSaveable { mutableIntStateOf(0) }
    val ver = remember { try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" } }
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
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
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
                report = r; detail = null; page = "scan"; scanTab = 1
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

    fun go(p: String) {
        if (p == page) return
        trail = if (p in DOCK) "" else if (trail.isEmpty()) page else "$trail,$page"
        page = p
    }
    fun goBack() {
        if (detail != null) detail = null
        else if (page in DOCK || trail.isEmpty()) page = if (page in DOCK) "home" else "more"
        else { val l = trail.split(','); page = l.last(); trail = l.dropLast(1).joinToString(",") }
    }
    BackHandler(enabled = detail != null || page != "home") { goBack() }

    val showDock = detail == null && page in DOCK
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (detail != null || page !in DOCK) {
                    IconButton({ goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text(if (detail != null) "App details" else PAGE_TITLES[page] ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                } else if (page == "home") {
                    Spacer(Modifier.width(4.dp))
                    Image(painterResource(R.drawable.ic_logo), null, Modifier.size(38.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sentinel", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    IconButton({ go("settings") }) { Icon(Icons.Default.Settings, "Settings", tint = Muted) }
                } else {
                    Text(PAGE_TITLES[page] ?: "", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        bottomBar = { if (showDock) Dock(page) { go(it) } }
    ) { pad ->
        Box(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            val d = detail
            if (d != null) AppDetail(
                pkg = d, scans = scans.filter { it.pkg == d }, installed = d in inst, safe = d in safe, rescanning = rescanning, blocked = d in blocked,
                onSafe = { setSafe(d, it) }, onRescan = { rescan(d) },
                onBlock = { on -> scope.launch { if (on) dao.block(BlockEntity(d)) else dao.unblock(BlockEntity(d)) } }
            ) else when (page) {
                "home" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val low = (latest.size - high - med).coerceAtLeast(0)
                    val tot = latest.size.coerceAtLeast(1).toFloat()
                    Panel {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Ring(150.dp, listOf(high / tot to Red, med / tot to Amber, low / tot to Green)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${latest.size}", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                                    Text("apps checked", fontSize = 11.sp, color = Muted)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Legend("$high high", Red); Legend("$med medium", Amber); Legend("$low low", Green)
                            }
                            if (auditRun) {
                                if (auditTotal > 0) LinearProgressIndicator(progress = auditDone / auditTotal.toFloat(), modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (auditTotal > 0) "$auditDone/$auditTotal  $auditLabel" else "Starting…", fontSize = 12.sp, color = Muted, maxLines = 1)
                            } else {
                                val t = lastAudit.split('|').getOrNull(0)?.toLongOrNull()
                                Text(if (t != null) "Last check ${fmt(t)}" else "Not checked yet", fontSize = 12.sp, color = Muted)
                                if (safeN > 0) Text("$safeN marked safe", fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ auditRun = true; Background.scanNow(c, true) }, Modifier.weight(1f).height(48.dp), enabled = !auditRun, shape = RoundedCornerShape(14.dp)) {
                            Text("Scan all apps", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton({ scanTab = 1; go("scan") }, Modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("APK") }
                    }
                    val risky = latest.filter { it.score >= 25 && it.pkg !in safe }.sortedByDescending { it.score }
                    if (risky.isNotEmpty()) {
                        Label("NEEDS ATTENTION")
                        risky.take(3).forEach { s ->
                            Item(sc(s.score), { detail = s.pkg }) {
                                Pill(s.score, sc(s.score))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(firstFinding(s.text) ?: s.pkg, fontSize = 12.sp, color = Muted, maxLines = 1)
                                }
                                Chev()
                            }
                        }
                        if (risky.size > 3) TextButton({ go("apps") }, Modifier.align(Alignment.CenterHorizontally)) { Text("See all ${risky.size} ›") }
                    } else if (latest.isNotEmpty()) {
                        Text("Nothing needs your attention.", fontSize = 13.sp, color = Green, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                "scan" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    item { Seg(listOf("Installed apps", "APK file"), scanTab) { scanTab = it } }
                    if (scanTab == 0) {
                        item {
                            Panel {
                                if (auditRun) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Checking apps…", fontWeight = FontWeight.Bold)
                                            Text("Running in the background, you can leave", fontSize = 12.sp, color = Muted)
                                        }
                                        if (auditTotal > 0) Text("$auditDone/$auditTotal", color = Green, fontWeight = FontWeight.Bold)
                                    }
                                    if (auditTotal > 0) LinearProgressIndicator(progress = auditDone / auditTotal.toFloat(), modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (auditLabel.isNotEmpty()) Text(auditLabel, fontSize = 12.sp, color = Muted, maxLines = 1)
                                } else {
                                    Text("Installed apps", fontWeight = FontWeight.Bold)
                                    Text("Runs in the background, so you can leave the app.", fontSize = 12.sp, color = Muted)
                                    val la = lastAudit.split('|')
                                    val t = la.getOrNull(0)?.toLongOrNull()
                                    if (la.size == 3 && t != null) Text("Last check ${fmt(t)}: ${la[1]} apps scanned, ${la[2]} risky", fontSize = 12.sp)
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ auditRun = true; Background.scanNow(c) }, Modifier.weight(1f), enabled = !auditRun, shape = RoundedCornerShape(14.dp)) { Text("Scan new apps", maxLines = 1) }
                                OutlinedButton({ auditRun = true; Background.scanNow(c, true) }, Modifier.weight(1f), enabled = !auditRun, shape = RoundedCornerShape(14.dp)) { Text("Rescan all", maxLines = 1) }
                            }
                        }
                        item { TextButton({ go("apps") }) { Text("Review risky apps →") } }
                    } else {
                        item {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.5.dp, Line, RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("⬆", fontSize = 30.sp, color = Green)
                                Text("Check an APK before installing", fontWeight = FontWeight.Bold)
                                Text("Pick a file, or share it to Sentinel from any app", fontSize = 12.sp, color = Muted)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ pickApk.launch(arrayOf("*/*")) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(if (apk == null) "Pick APK" else "APK ✓", maxLines = 1) }
                                    OutlinedButton({ pickRef.launch(arrayOf("*/*")) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(if (ref == null) "+ Original" else "Original ✓", maxLines = 1) }
                                }
                                Button({ run(apk!!, ref) }, Modifier.fillMaxWidth(), enabled = apk != null && !busy, shape = RoundedCornerShape(14.dp)) { Text(if (busy) "Scanning…" else "Scan") }
                                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                err?.let { Text(it, color = Red, fontSize = 13.sp) }
                            }
                        }
                        report?.let { r ->
                            item { Label("LAST RESULT") }
                            item {
                                Panel {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${r.score}", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = sc(r.score))
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
                }
                "apps" -> AppsPage(latest, safe, ::setSafe) { detail = it }
                "more" -> MorePage { go(it) }
                "monitor" -> MonitorTab(blocked, safe, ::setSafe, { p, on -> scope.launch { if (on) dao.block(BlockEntity(p)) else dao.unblock(BlockEntity(p)) } }, { detail = it }) { src -> run(src, null) }
                "history" -> HistoryPage(scans, safe) { detail = it }
                "access" -> AccessTab(prefs, safe, ::setSafe) { detail = it }
                "about" -> AboutPage(ver)
                else -> SettingsTab(prefs, safe.size, ver, { go("about") }) { scope.launch { dao.clearSafe() } }
            }
        }
    }
}

@Composable fun MorePage(onGo: (String) -> Unit) {
    @Composable fun Row2(title: String, sub: String, icon: ImageVector, col: Color, page: String) =
        Item(col, { onGo(page) }) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = col) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(sub, fontSize = 12.sp, color = Muted)
            }
            Chev()
        }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row2("Monitor", "Network use and blocking", Icons.Default.Lock, Blue, "monitor")
        Row2("Access", "Apps with powerful permissions", Icons.Default.Warning, Amber, "access")
        Row2("History", "Every saved scan result", Icons.Default.DateRange, Purple, "history")
        Row2("Settings", "Background checks, keys, trackers", Icons.Default.Settings, Muted, "settings")
        Row2("About", "What Sentinel is and how it works", Icons.Default.Info, Green, "about")
    }
}

@Composable fun AboutPage(ver: String) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(72.dp))
                Text("Sentinel", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Version $ver", fontSize = 13.sp, color = Muted)
            }
        }
        Panel {
            Title("What it does")
            Text("Sentinel checks the apps on your phone, and APK files before you install them, for signs of risk: powerful permissions, trackers, re-signed apps and unusual network use. Each app gets a score from 0 to 100.", fontSize = 13.sp)
        }
        Panel {
            Title("Your privacy")
            Text("Scans run on your phone. If you add a VirusTotal or MalwareBazaar key, only the file's SHA-256 hash is sent, never the file. Blocking an app's internet uses a local VPN, so nothing leaves your phone.", fontSize = 13.sp)
        }
        Panel {
            Title("Tracker list")
            Text("Tracker names come from Exodus Privacy. You can update the list in Settings.", fontSize = 13.sp)
        }
    }
}

@Composable fun AppsPage(latest: List<ScanEntity>, safe: Set<String>, onSafe: (String, Boolean) -> Unit, onOpen: (String) -> Unit) {
    var f by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    val shown = remember(latest, safe, f, q) {
        when (f) {
            0 -> latest.filter { it.score >= 25 && it.pkg !in safe }.sortedByDescending { it.score }
            1 -> latest.filter { it.pkg in safe }.sortedBy { it.name.lowercase() }
            2 -> latest.sortedByDescending { it.score }
            else -> latest.filter { it.name.contains(q, true) || it.pkg.contains(q, true) }.sortedByDescending { it.score }
        }
    }
    val hi = latest.count { it.score >= 55 && it.pkg !in safe }
    val md = latest.count { it.score in 25..54 && it.pkg !in safe }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("High", hi, Red), Triple("Medium", md, Amber), Triple("Safe", latest.count { it.pkg in safe }, Green)).forEach { (l, n, col) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("$n", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = col)
                        Text(l, fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Risky", f == 0) { f = 0 }
                Chip("Safe", f == 1) { f = 1 }
                Chip("All", f == 2) { f = 2 }
                Chip("🔍 Search", f == 3) { f = 3 }
            }
        }
        if (f == 3) item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("App name or package") }, singleLine = true) }
        if (shown.isEmpty()) item {
            Text(if (f == 0) "No risky apps. Nothing to review." else if (f == 1) "You haven't marked any app as safe." else if (f == 3) "No app matches." else "No scanned apps yet. Run a scan first.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(8.dp))
        }
        items(shown, key = { it.pkg }) { s ->
            val isSafe = s.pkg in safe
            val col = if (isSafe) Green else sc(s.score)
            Item(col, { onOpen(s.pkg) }) {
                Pill(s.score, col)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (isSafe) Tag("SAFE", Green) else Tag(lvl(s.score).substringBefore(' ').uppercase(), col)
                    }
                    Text(if (isSafe) "Marked safe" else firstFinding(s.text) ?: s.pkg, fontSize = 12.sp, color = Muted, maxLines = 1)
                }
                if (isSafe) Chev() else MiniBtn("Safe") { onSafe(s.pkg, true) }
            }
        }
    }
}

@Composable fun HistoryPage(scans: List<ScanEntity>, safe: Set<String>, onOpen: (String) -> Unit) {
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
    val rows = remember(shown, byRisk) {
        val out = ArrayList<Any>(); var last = ""
        shown.forEach { s ->
            if (!byRisk) { val d = dayLabel(s.time); if (d != last) { out.add(d); last = d } }
            out.add(s)
        }
        out
    }
    val tm = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Clear history?") }, text = { Text("This removes every saved scan result. Your safe-app marks are kept.") },
        confirmButton = { TextButton({ scope.launch { dao.clear() }; confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
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
            }
        }
        items(rows, key = { if (it is ScanEntity) it.id else "h$it" }) { r ->
            if (r is String) Text(r, Modifier.padding(start = 4.dp, top = 6.dp), fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
            else if (r is ScanEntity) {
                val isSafe = r.pkg in safe
                val col = if (isSafe) Green else sc(r.score)
                val ser = r.text.lines().count { it.startsWith("✖") }
                Item(col, { onOpen(r.pkg) }) {
                    Pill(r.score, col)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(r.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (isSafe) Tag("SAFE", Green)
                        }
                        Text((if (byRisk) fmt(r.time) else tm.format(Date(r.time))) + " · " + (if (ser > 0) "$ser serious" else "no serious findings"), fontSize = 12.sp, color = Muted, maxLines = 1)
                    }
                    Chev()
                }
            }
        }
        if (scans.isNotEmpty()) item { TextButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("Clear history", color = Muted) } }
    }
}

@Composable fun MonitorTab(blocked: List<String>, safe: Set<String>, onSafe: (String, Boolean) -> Unit, onBlock: (String, Boolean) -> Unit, onOpen: (String) -> Unit, onScan: (String) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppNet>>(emptyList()) }
    var ok by remember { mutableStateOf(Monitor.hasUsage(c)) }
    fun reload() {
        ok = Monitor.hasUsage(c)
        if (ok) scope.launch { apps = withContext(Dispatchers.Default) { Monitor.load(c) } }
    }
    var sel by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { reload() }
    val flagged = apps.filter { it.flags.isNotEmpty() && it.pkg !in safe }
    val maxTx = (apps.maxOfOrNull { it.tx } ?: 1L).coerceAtLeast(1L).toFloat()
    val list = when (sel) { 0 -> flagged; 1 -> apps; else -> apps.filter { it.pkg in blocked } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (!ok) item {
            Panel {
                Text("Allow usage access so Sentinel can read per-app network totals.", fontSize = 13.sp)
                Button({ c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access") }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sent in the last 24h", fontSize = 12.sp, color = Muted)
                        Text(mb(apps.sumOf { it.tx }), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Flagged", fontSize = 12.sp, color = Muted)
                        Text("${flagged.size}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (flagged.isEmpty()) Green else Amber)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Flagged", sel == 0) { sel = 0 }
                    Chip("All apps", sel == 1) { sel = 1 }
                    Chip("Blocked", sel == 2) { sel = 2 }
                }
                TextButton({ reload() }) { Text("Refresh") }
            }
        }
        if (flagged.isNotEmpty()) item {
            OutlinedButton({ flagged.forEach { onBlock(it.pkg, true) } }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Block all flagged apps") }
        }
        if (list.isEmpty() && ok) item { Text(if (sel == 0) "Nothing flagged." else if (sel == 2) "No blocked apps." else "No data yet.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(8.dp)) }
        items(list, key = { it.pkg }) { a ->
            val isSafe = a.pkg in safe
            val col = if (a.flags.isEmpty() || isSafe) Blue else sc(a.score)
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpen(a.pkg) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(a.label, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (isSafe) Tag("SAFE", Green)
                        }
                        Text("↑ ${mb(a.tx)}   ↓ ${mb(a.rx)}   bg↑ ${mb(a.bgTx)}", fontSize = 12.sp, color = Muted)
                    }
                    TextButton({ onScan(a.src) }) { Text("Scan") }
                    Switch(checked = a.pkg in blocked, onCheckedChange = { onBlock(a.pkg, it) })
                }
                Bar(a.tx / maxTx, col)
                if (!isSafe) a.flags.forEach { Text("● $it", color = sc(a.score), fontSize = 12.sp) }
                if (a.flags.isNotEmpty() || isSafe) SafeButton(isSafe) { onSafe(a.pkg, !isSafe) }
            }
        }
    }
}

@Composable fun SettingsTab(prefs: SharedPreferences, safeCount: Int, ver: String, onAbout: () -> Unit, onClearSafe: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var vt by remember { mutableStateOf(prefs.getString("vt", "")!!) }
    var mbk by remember { mutableStateOf(prefs.getString("mb", "")!!) }
    var msg by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("PROTECTION")
        Group {
            SetRow("Background checks", "New and updated apps, every few hours") {
                Switch(checked = auto, onCheckedChange = { auto = it; prefs.edit().putBoolean("auto", it).apply(); Background.auto(c, it) })
            }
            SetRow("Safe apps", if (safeCount == 0) "No apps marked as safe" else "$safeCount app${if (safeCount == 1) "" else "s"} marked as safe", onClick = if (safeCount > 0) onClearSafe else null) {
                if (safeCount > 0) Text("Clear ›", color = Muted, fontSize = 13.sp)
            }
            SetRow("Usage access", "Needed for the network monitor", last = true, onClick = { c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Chev() }
        }
        Label("CLOUD CHECKS (OPTIONAL)")
        Panel {
            Text("Only the SHA-256 hash is sent, never the file.", fontSize = 12.sp, color = Muted)
            OutlinedTextField(vt, { vt = it; prefs.edit().putString("vt", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("VirusTotal API key") }, singleLine = true)
            OutlinedTextField(mbk, { mbk = it; prefs.edit().putString("mb", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("abuse.ch Auth-Key (MalwareBazaar)") }, singleLine = true)
        }
        Label("DATA")
        Group {
            SetRow("Update trackers", if (msg.isEmpty()) "From Exodus Privacy" else msg, onClick = {
                scope.launch { msg = try { "Updated: ${ApkScanner.refreshTrackers(c)} trackers" } catch (e: Exception) { "Update failed: are you offline?" } }
            }) { Chev() }
            SetRow("About Sentinel", last = true, onClick = onAbout) { Text("v$ver", color = Muted, fontSize = 13.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AccessTab(prefs: SharedPreferences, safe: Set<String>, onSafe: (String, Boolean) -> Unit, onOpen: (String) -> Unit) {
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
    @Composable fun Perms(ps: Set<String>, dim: Boolean) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ps.forEach { p ->
                val hot = p.contains("ccessib", true) || p.contains("overlay", true) || p.contains("draw over", true)
                val col = if (dim) Muted else if (hot) Red else Amber
                Text(p, Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 3.dp), color = col, fontSize = 11.sp)
            }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Baseline", fontWeight = FontWeight.Bold)
                        Text("Save one while your phone is clean, then check for new apps and new powerful access.", fontSize = 12.sp, color = Muted)
                    }
                    if (res != null && res!!.isNotEmpty()) Tag("${res!!.size} NEW", Amber)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val r = Access.checkBaseline(prefs, visible)
                        if (r.firstOrNull()?.startsWith("No baseline") == true) { msg = "Save a baseline first"; res = null }
                        else { res = r; msg = if (r.isEmpty()) "No changes" else "${r.size} changes" }
                    }, Modifier.weight(1f), enabled = !loading, shape = RoundedCornerShape(12.dp)) { Text("Check now") }
                    OutlinedButton({ msg = "Baseline saved for ${Access.saveBaseline(prefs, list)} apps"; res = null }, Modifier.weight(1f), enabled = !loading, shape = RoundedCornerShape(12.dp)) { Text("Save baseline") }
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp)
                res?.forEach { t -> Text("● $t", color = Amber, fontSize = 13.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("POWERFUL ACCESS · ${visible.size} APPS")
                Spacer(Modifier.weight(1f))
                TextButton({ reload() }) { Text("Refresh") }
            }
        }
        if (loading) item { Text("Loading…", fontSize = 12.sp) }
        else if (visible.isEmpty()) item { Text("No unreviewed app has powerful access", fontSize = 12.sp, color = Green) }
        items(visible, key = { it.first }) { p ->
            Panel(Modifier.clickable { onOpen(p.first) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.second.first, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    MiniBtn("Review") {
                        try { c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${p.first}"))) } catch (_: Exception) {}
                    }
                    Spacer(Modifier.width(6.dp))
                    MiniBtn("Safe") { onSafe(p.first, true) }
                }
                Perms(p.second.second, false)
            }
        }
        if (hidden > 0) {
            item { TextButton({ showSafe = !showSafe }) { Text(if (showSafe) "Hide $hidden safe app${if (hidden == 1) "" else "s"}" else "Show $hidden safe app${if (hidden == 1) "" else "s"}") } }
            if (showSafe) items(list.filter { it.first in safe }, key = { "s" + it.first }) { p ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.second.first, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false)); Tag("SAFE", Green)
                    }
                    Perms(p.second.second, true)
                    SafeButton(true) { onSafe(p.first, false) }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable fun AppDetail(pkg: String, scans: List<ScanEntity>, installed: Boolean, safe: Boolean, rescanning: String?, blocked: Boolean,
                          onSafe: (Boolean) -> Unit, onRescan: () -> Unit, onBlock: (Boolean) -> Unit) {
    val c = LocalContext.current
    val pm = c.packageManager
    val info = remember(pkg, installed) { try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null } }
    val cur = scans.firstOrNull()
    val name = cur?.name ?: info?.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
    var net by remember(pkg) { mutableStateOf<AppNet?>(null) }
    var usageOk by remember { mutableStateOf(Monitor.hasUsage(c)) }
    LaunchedEffect(pkg) {
        usageOk = Monitor.hasUsage(c)
        if (usageOk && installed) net = withContext(Dispatchers.Default) { Monitor.load(c).firstOrNull { it.pkg == pkg } }
    }
    val day = DateFormat.getDateInstance(DateFormat.SHORT)
    val col = if (cur == null) Muted else if (safe) Green else sc(cur.score)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Panel {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ring(120.dp, listOf((cur?.score ?: 0) / 100f to col)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (cur != null) "${cur.score}" else "–", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = col)
                            if (cur != null) Text(if (safe) "Safe" else lvl(cur.score), fontSize = 11.sp, color = Muted)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        if (safe) Tag("SAFE", Green)
                    }
                    Text(pkg, fontSize = 11.sp, color = Muted)
                    if (info != null) Text("Version ${info.versionName ?: "?"} · installed ${day.format(Date(info.firstInstallTime))} · updated ${day.format(Date(info.lastUpdateTime))}", fontSize = 12.sp, color = Muted)
                    else Text("No longer installed. Showing saved results.", fontSize = 12.sp, color = Muted)
                    if (scans.size >= 2) {
                        val first = scans.last(); val d = scans.first().score - first.score
                        val tc = if (d > 0) Red else if (d < 0) Green else Muted
                        Trend(scans.reversed().map { it.score }, tc)
                        Text((if (d > 0) "▲ Risk up $d" else if (d < 0) "▼ Risk down ${-d}" else "Same score") + " over ${scans.size} scans since ${day.format(Date(first.time))}", fontSize = 12.sp, color = tc)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installed) Button({ onRescan() }, Modifier.weight(1f).height(46.dp), enabled = rescanning == null, shape = RoundedCornerShape(14.dp)) {
                    Text(if (rescanning == pkg) "Checking…" else if (cur == null) "Scan now" else "Rescan", maxLines = 1, fontWeight = FontWeight.Bold)
                }
                OutlinedButton({ onSafe(!safe) }, Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(14.dp)) { Text(if (safe) "✓ Safe · undo" else "Mark as safe", maxLines = 1) }
                if (installed) OutlinedButton({
                    try { c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (_: Exception) {}
                }, Modifier.height(46.dp), shape = RoundedCornerShape(14.dp)) { Text("⋯") }
            }
        }
        if (installed) item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Network · 24h", fontWeight = FontWeight.Bold)
                        val n = net
                        if (!usageOk) Text("Allow usage access to see this app's network use.", fontSize = 12.sp, color = Muted)
                        else if (n == null) Text("Loading…", fontSize = 12.sp, color = Muted)
                        else Text("↑ ${mb(n.tx)} · ↓ ${mb(n.rx)} · bg ${mb(n.bgTx)}", fontSize = 12.sp, color = Muted)
                    }
                    val n = net
                    if (usageOk && n != null) { if (n.flags.isEmpty()) Tag("NORMAL", Green) else Tag("UNUSUAL", sc(n.score)) }
                }
                val n = net
                if (usageOk && n != null) {
                    Bar(if (n.tx + n.rx == 0L) 0f else n.tx / (n.tx + n.rx).toFloat(), if (n.flags.isEmpty()) Blue else sc(n.score))
                    n.flags.forEach { Text("● $it", color = sc(n.score), fontSize = 12.sp) }
                }
                if (!usageOk) OutlinedButton({ c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block this app's internet", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Uses Sentinel's local VPN. Nothing leaves your phone.", fontSize = 11.sp, color = Muted)
                    }
                    Switch(checked = blocked, onCheckedChange = onBlock)
                }
            }
        }
        if (cur != null) {
            val ls = cur.text.lines().filter { it.isNotBlank() }
            val ser = ls.filter { it.startsWith("✖") }
            val warn = ls.filter { it.startsWith("!") }
            val rest = ls.filter { !it.startsWith("✖") && !it.startsWith("!") }
            if (ser.isNotEmpty()) { item { Label("SERIOUS · ${ser.size}") }; item { Panel { Findings(ser.joinToString("\n")) } } }
            if (warn.isNotEmpty()) { item { Label("WARNINGS · ${warn.size}") }; item { Panel { Findings(warn.joinToString("\n")) } } }
            if (rest.isNotEmpty()) { item { Label("NOTES") }; item { Panel { Findings(rest.joinToString("\n")) } } }
            if (ls.isEmpty()) item { Text("Nothing suspicious found.", fontSize = 13.sp, color = Green, modifier = Modifier.padding(8.dp)) }
        } else item { Text("Not scanned yet.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(8.dp)) }
        if (scans.isNotEmpty()) {
            item { Label("SCAN TIMELINE · ${scans.size}") }
            itemsIndexed(scans, key = { _, s -> s.id }) { i, s ->
                var open by remember { mutableStateOf(false) }
                val older = scans.getOrNull(i + 1)
                Item(sc(s.score), { open = !open }) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(s.score, sc(s.score))
                            Column {
                                Text(fmt(s.time), fontSize = 13.sp)
                                val dl = if (older == null) 0 else s.score - older.score
                                Text(if (older == null) "First scan" else if (dl > 0) "▲ $dl higher than before" else if (dl < 0) "▼ ${-dl} lower than before" else "Same score, findings changed",
                                    fontSize = 11.sp, color = if (older == null) Muted else if (dl > 0) Red else if (dl < 0) Green else Muted)
                            }
                        }
                        if (open) { Spacer(Modifier.height(8.dp)); Findings(s.text) }
                    }
                    Text(if (open) "▲" else "▼", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}
