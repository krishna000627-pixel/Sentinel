package com.sentinel.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

typealias AccessList = List<Pair<String, Pair<String, Set<String>>>>

/**
 * Keeps the last results in memory and on disk, so every screen is filled the moment the app opens.
 * Network and access lists are only reloaded when you refresh them or when they are older than 15 minutes.
 */
object Cache {
    private const val FRESH = 15 * 60_000L
    @Volatile private var net: List<AppNet>? = null
    @Volatile private var netAt = 0L
    @Volatile private var acc: AccessList? = null
    @Volatile private var accAt = 0L

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("cache", 0)

    fun loadInst(c: Context): Set<String> = HashSet(p(c).getStringSet("inst", null) ?: emptySet())
    fun saveInst(c: Context, s: Set<String>) { p(c).edit().putStringSet("inst", HashSet(s)).apply() }
    fun loadSafe(c: Context): Set<String> = HashSet(p(c).getStringSet("safe", null) ?: emptySet())
    fun saveSafe(c: Context, s: Set<String>) { p(c).edit().putStringSet("safe", HashSet(s)).apply() }

    fun loadLatest(c: Context): List<ScanEntity> = try {
        val a = JSONArray(p(c).getString("latest", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ScanEntity(id = -(i + 1).toLong(), name = o.getString("n"), pkg = o.getString("p"), score = o.getInt("s"), text = o.getString("t"), time = o.getLong("T"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveLatest(c: Context, l: List<ScanEntity>) {
        val a = JSONArray()
        l.forEach { s -> a.put(JSONObject().put("n", s.name).put("p", s.pkg).put("s", s.score).put("t", s.text).put("T", s.time)) }
        p(c).edit().putString("latest", a.toString()).apply()
    }

    fun net(c: Context): List<AppNet>? {
        net?.let { return it }
        val s = p(c).getString("net", null) ?: return null
        return try {
            val a = JSONArray(s)
            val l = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val f = o.getJSONArray("f")
                AppNet(o.getString("p"), o.getString("l"), o.getString("s"), o.getLong("t"), o.getLong("r"), o.getLong("b"),
                    (0 until f.length()).map { f.getString(it) }, o.getInt("c"))
            }
            net = l; netAt = p(c).getLong("net_t", 0L); l
        } catch (_: Exception) { null }
    }
    fun netAt(): Long = netAt
    fun netStale(): Boolean = System.currentTimeMillis() - netAt > FRESH
    fun putNet(c: Context, l: List<AppNet>) {
        net = l; netAt = System.currentTimeMillis()
        val a = JSONArray()
        l.forEach { x -> a.put(JSONObject().put("p", x.pkg).put("l", x.label).put("s", x.src).put("t", x.tx).put("r", x.rx).put("b", x.bgTx).put("f", JSONArray(x.flags)).put("c", x.score)) }
        p(c).edit().putString("net", a.toString()).putLong("net_t", netAt).apply()
    }

    fun access(c: Context): AccessList? {
        acc?.let { return it }
        val s = p(c).getString("acc", null) ?: return null
        return try {
            val a = JSONArray(s)
            val l: AccessList = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val g = o.getJSONArray("g")
                o.getString("p") to (o.getString("l") to (0 until g.length()).map { g.getString(it) }.toSet())
            }
            acc = l; accAt = p(c).getLong("acc_t", 0L); l
        } catch (_: Exception) { null }
    }
    fun accessStale(): Boolean = System.currentTimeMillis() - accAt > FRESH
    fun putAccess(c: Context, l: AccessList) {
        acc = l; accAt = System.currentTimeMillis()
        val a = JSONArray()
        l.forEach { e -> a.put(JSONObject().put("p", e.first).put("l", e.second.first).put("g", JSONArray(e.second.second.toList()))) }
        p(c).edit().putString("acc", a.toString()).putLong("acc_t", accAt).apply()
    }
}
