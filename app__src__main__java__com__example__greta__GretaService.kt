package com.example.greta

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import android.provider.AlarmClock
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class GretaService : Service(), TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    private var running = false
    private var busy = false
    private var afterSpeak: (() -> Unit)? = null
    private var cmdRetries = 0
    private var clapThread: Thread? = null
    private lateinit var mode: String
    private lateinit var key: String
    private val history = JSONArray()
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val p = getSharedPreferences("jv", 0)
        mode = p.getString("mode", "voice")!!; key = p.getString("key", "")!!
        val ch = "jv"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "Greta", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("Greta is listening")
            .setContentText(if (mode == "clap") "Clap twice" else "Say “Hey Greta”")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "greta:w").apply { acquire() }
        tts = TextToSpeech(this, this)
        running = true
    }

    override fun onInit(s: Int) {
        tts?.language = Locale.UK; ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private fun fire() { main.post { val f = afterSpeak; afterSpeak = null; f?.invoke() } }
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { fire() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { fire() }
        })
        speak("Greta online.")
        main.postDelayed({ listen() }, 1500)
    }

    override fun onDestroy() { running = false; clapThread?.interrupt(); sr?.destroy(); tts?.shutdown(); wl?.release(); super.onDestroy() }

    private fun speak(t: String) {
        if (!ttsReady) return
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u")
    }
    private fun speakThen(t: String, f: () -> Unit) { afterSpeak = f; speak(t) }
    private fun dbg(k: String, v: String) { getSharedPreferences("jv", 0).edit().putString(k, v).apply() }
    private fun speaking() = tts?.isSpeaking == true

    /* ---- listening loop: voice mode = recognizer loop; clap mode = AudioRecord until double clap ---- */
    private fun listen() {
        if (!running) return
        if (mode == "clap") waitForClaps() else recognize(wakeOnly = true)
    }

    private fun recognize(wakeOnly: Boolean) {
        if (!running) return
        if (busy || speaking()) { main.postDelayed({ recognize(wakeOnly) }, 700); return }
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr!!.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (wakeOnly) {
                    val m = Regex("(?i)\\b(greta|gretta|grita|creta|gretha|great a)\\b[ ,.!?]*(.*)").find(t)
                    when {
                        m == null -> recognize(true)
                        m.groupValues[2].length > 2 -> ask(m.groupValues[2])
                        else -> { cmdRetries = 0; speakThen("Yes?") { main.postDelayed({ recognize(false) }, 250) } }
                    }
                } else { if (t.isNotBlank()) ask(t) else listen() }
            }
            override fun onError(e: Int) {
                dbg("speech", "recognizer error $e")
                if (wakeOnly) main.postDelayed({ recognize(true) }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
                else if (cmdRetries < 2) { cmdRetries++; main.postDelayed({ recognize(false) }, 500) }
                else { cmdRetries = 0; speakThen("Sorry, I didn't catch that.") { listen() } }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        sr!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        })
    }

    private fun waitForClaps() {
        clapThread = thread {
            val rate = 16000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
            val rec = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            val buf = ShortArray(512); var last = 0L; var base = 300.0
            rec.startRecording()
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
                    var pk = 0; var sum = 0.0
                    for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a > pk) pk = a; sum += a.toDouble() * a }
                    val rms = Math.sqrt(sum / n); base = base * 0.98 + rms * 0.02
                    val now = System.currentTimeMillis()
                    if (pk > 12000 && pk > base * 8 && rms < pk * 0.6 && !speaking()) {
                        val gap = now - last
                        if (gap in 150..800) { last = 0; break }
                        if (gap > 800 || last == 0L) last = now
                    }
                }
            } finally { rec.stop(); rec.release() }
            if (running) main.post { cmdRetries = 0; speakThen("Yes?") { main.postDelayed({ recognize(false) }, 250) } }
        }
    }

    /* ---- brain (Google Gemini) ---- */
    private fun ask(text: String) {
        busy = true
        dbg("heard", text)
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 10) history.remove(0)
        while (history.length() > 0 && history.getJSONObject(0).getString("role") != "user") history.remove(0)
        thread {
            val reply = try { callGemini() } catch (e: Exception) { "I hit a fault: ${e.message}" }
            if (reply.startsWith("I hit a fault")) dbg("error", reply) else dbg("reply", reply)
            main.post {
                history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", reply))))
                speak(reply); busy = false
                main.postDelayed({ listen() }, 800)
            }
        }
    }

    private fun fn(name: String, desc: String, vararg fields: Pair<String, String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (fields.isNotEmpty()) {
            val props = JSONObject(); val req = JSONArray()
            for ((k, t) in fields) { props.put(k, JSONObject().put("type", t)); req.put(k) }
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", req))
        }
        return o
    }

    private fun tools() = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(fn("save_note", "Save a note the user dictated", "text" to "STRING"))
        .put(fn("list_notes", "List saved notes and research"))
        .put(fn("remember", "Store a lasting fact or preference about the user", "fact" to "STRING"))
        .put(fn("save_research", "Save a finished research write-up", "title" to "STRING", "report" to "STRING"))
        .put(fn("start_timer", "Start a countdown timer", "seconds" to "NUMBER"))
        .put(fn("get_location", "Get the user's current location and street address"))))

    private fun runTool(name: String, i: JSONObject): String = when (name) {
        "save_note" -> { Store.note(this, "note", i.getString("text").take(60), i.getString("text")); "saved" }
        "list_notes" -> { val a = Store.arr(this, "notes"); val o = JSONArray()
            for (k in maxOf(0, a.length() - 15) until a.length()) { val n = a.getJSONObject(k); o.put(JSONObject().put("title", n.optString("title")).put("text", n.optString("text").take(500))) }
            o.toString() }
        "remember" -> { Store.push(this, "mem", i.getString("fact").take(200), 40); "remembered" }
        "save_research" -> { Store.note(this, "research", i.getString("title"), i.getString("report")); "saved" }
        "get_location" -> try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) "location permission not granted"
            else {
                val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                val loc = listOf("gps", "network", "fused").mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time }
                if (loc == null) "location unavailable right now"
                else {
                    val addr = try { android.location.Geocoder(this).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0) } catch (e: Exception) { null }
                    "lat=${loc.latitude}, lon=${loc.longitude}, accuracy=${loc.accuracy.toInt()}m, address=${addr ?: "unknown"}"
                }
            }
        } catch (e: Exception) { "location error" }
        "start_timer" -> { startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, i.getDouble("seconds").toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "started" }
        else -> "unknown tool"
    }

    private fun post(msgs: JSONArray): JSONObject {
        val mem = Store.memory(this).joinToString("; ").ifBlank { "nothing yet" }
        val sys = "You are Greta, the user's personal AI companion living on their tablet, like a brilliant best friend. You are witty, warm, a little teasing, and you show feeling: relieved, worried, proud, amused. You use the user's name if you know it from memory, and you remember their life. Replies are spoken aloud: 1-3 short plain sentences, no markdown, no emojis. " +
            "Never pretend to abilities you lack. For health worries give general information only, never a diagnosis, and suggest a doctor; if someone may be in danger, tell them to call their local emergency number now. " +
            "Memory about the user: $mem. When asked to note/jot something down call save_note. When the user shares something worth remembering call remember. " +
            "For research requests: you have NO live internet, so work from your own knowledge, call save_research with a titled write-up (and say it may be out of date), then speak a 2-3 sentence summary. " +
            "You have no smart-home access. Now: ${Date()}"
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", msgs).put("tools", tools())
        var lastErr = "unknown"
        for (attempt in 0..3) {
            val model = if (attempt < 3) "gemini-flash-latest" else "gemini-flash-lite-latest"
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 90000
            c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val ok = code in 200..299
            val s = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
            if (ok) return JSONObject(s)
            lastErr = "API error $code: " + (try { JSONObject(s).getJSONObject("error").getString("message") } catch (e: Exception) { s.take(150) })
            if (code != 503 && code != 429 && code != 500) break
            Thread.sleep(1500L * (attempt + 1))
        }
        throw RuntimeException(lastErr)
    }

    private fun callGemini(): String {
        val msgs = JSONArray(history.toString())
        var reply = ""
        for (n in 0 until 6) {
            val res = post(msgs)
            val content = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") ?: return "I can't answer that one, sir."
            val parts = content.optJSONArray("parts") ?: return "I have nothing to add."
            if (!content.has("role")) content.put("role", "model")
            msgs.put(content)
            val results = JSONArray(); val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("functionCall")) {
                    val fc = p.getJSONObject("functionCall")
                    val out = try { runTool(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()) } catch (e: Exception) { "error: ${e.message}" }
                    results.put(JSONObject().put("functionResponse", JSONObject().put("name", fc.getString("name")).put("response", JSONObject().put("result", out))))
                } else if (p.has("text") && !p.optBoolean("thought")) text.append(p.getString("text")).append(' ')
            }
            reply = text.toString().trim()
            if (results.length() == 0) break
            msgs.put(JSONObject().put("role", "user").put("parts", results))
        }
        return reply.ifBlank { "Done." }
    }
}
