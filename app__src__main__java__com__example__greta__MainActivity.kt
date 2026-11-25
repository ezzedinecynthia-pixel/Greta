package com.example.greta

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var dbg: TextView
    private lateinit var status: TextView
    private var testing = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("jv", 0)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 80, 60, 60); setBackgroundColor(Color.parseColor("#02080d"))
        }
        fun tv(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.parseColor("#3fe3ff")) }
        col.addView(tv("GRETA", 30f))
        col.addView(tv("\nGemini API key (from aistudio.google.com)"))
        val key = EditText(this).apply { setText(p.getString("key", "")); setTextColor(Color.WHITE); hint = "AIza..."; setHintTextColor(Color.GRAY); isSingleLine = true }
        col.addView(key)
        val test = Button(this).apply { text = "TEST KEY" }
        col.addView(test)
        col.addView(tv("\nWake mode"))
        val mode = RadioGroup(this)
        val rv = RadioButton(this).apply { text = "Say \u201cHey Greta\u201d"; setTextColor(Color.WHITE); id = 1 }
        val rc = RadioButton(this).apply { text = "Clap twice"; setTextColor(Color.WHITE); id = 2 }
        mode.addView(rv); mode.addView(rc)
        mode.check(if (p.getString("mode", "voice") == "clap") 2 else 1)
        col.addView(mode)
        status = tv("\nStatus: ${if (p.getBoolean("on", false)) "listening" else "off"}")
        val start = Button(this).apply { text = "START LISTENING" }
        val stop = Button(this).apply { text = "STOP" }
        val nb = Button(this).apply { text = "NOTES & RESEARCH" }
        dbg = tv("", 13f)

        test.setOnClickListener {
            val k = key.text.toString().trim(); p.edit().putString("key", k).apply()
            testing = true; dbg.text = "Testing key..."
            thread { val r = testKey(k); runOnUiThread { dbg.text = r } }
        }
        start.setOnClickListener {
            val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(need.toTypedArray(), 1); return@setOnClickListener
            }
            p.edit().putString("key", key.text.toString().trim())
                .putString("mode", if (mode.checkedRadioButtonId == 2) "clap" else "voice").putBoolean("on", true).apply()
            startForegroundService(Intent(this, GretaService::class.java))
            status.text = "\nStatus: listening (you can leave this app)"
        }
        stop.setOnClickListener {
            p.edit().putBoolean("on", false).apply(); stopService(Intent(this, GretaService::class.java)); status.text = "\nStatus: off"
        }
        nb.setOnClickListener { showNotes() }
        col.addView(start); col.addView(stop); col.addView(nb); col.addView(status)
        col.addView(tv("\nWhat Greta last heard / said / errors:", 13f)); col.addView(dbg)
        col.addView(tv("\nTip: Settings > Apps > Greta > Battery > Unrestricted.", 13f))
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        if (!testing) {
            val p = getSharedPreferences("jv", 0)
            dbg.text = "Heard: ${p.getString("heard", "-")}\nReply: ${p.getString("reply", "-")}\nError: ${p.getString("error", "-")}\nSpeech: ${p.getString("speech", "-")}"
        }
    }

    private fun testKey(k: String): String = try {
        val body = """{"contents":[{"role":"user","parts":[{"text":"Reply with the single word OK"}]}]}"""
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-goog-api-key", k)
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        val s = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (ok) "KEY WORKS (HTTP ${c.responseCode})"
        else "FAILED HTTP ${c.responseCode}: " + (try { JSONObject(s).getJSONObject("error").getString("message") } catch (e: Exception) { s.take(300) })
    } catch (e: Exception) { "NO CONNECTION: ${e.message}" }

    private fun showNotes() {
        val a = Store.arr(this, "notes")
        val items = (a.length() - 1 downTo 0).map { a.getJSONObject(it) }
        val d = AlertDialog.Builder(this).setTitle("Notes & research")
        if (items.isEmpty()) d.setMessage("Nothing yet. Say: \u201cGreta, note this down...\u201d")
        else d.setItems(items.map { (if (it.optString("kind") == "research") "R " else "N ") + it.optString("title") }.toTypedArray()) { _, i ->
            AlertDialog.Builder(this).setTitle(items[i].optString("title")).setMessage(items[i].optString("text")).setPositiveButton("OK", null).show()
        }
        d.setNegativeButton("Close", null).show()
    }
}
