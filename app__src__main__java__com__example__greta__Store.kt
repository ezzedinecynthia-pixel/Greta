package com.example.greta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny JSON-in-SharedPreferences store for notes, research and memory. */
object Store {
    fun arr(c: Context, k: String): JSONArray = try { JSONArray(c.getSharedPreferences("jv", 0).getString(k, "[]")) } catch (_: Exception) { JSONArray() }
    @Synchronized fun push(c: Context, k: String, o: Any, max: Int = 200) {
        val a = arr(c, k); a.put(o)
        val out = JSONArray(); for (i in maxOf(0, a.length() - max) until a.length()) out.put(a.get(i))
        c.getSharedPreferences("jv", 0).edit().putString(k, out.toString()).apply()
    }
    fun note(c: Context, kind: String, title: String, text: String) =
        push(c, "notes", JSONObject().put("kind", kind).put("title", title.take(70)).put("text", text).put("ts", System.currentTimeMillis()))
    fun memory(c: Context): List<String> = (0 until arr(c, "mem").length()).map { arr(c, "mem").getString(it) }
}
