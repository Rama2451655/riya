package com.rama.riya

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiyaService : Service() {

    data class Contact(val name: String, val number: String)

    // Used when a contact number has no country code (change if you are not in India)
    private val defaultCountryCode = "91"

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    // When Riya asks "whom?", the next sentence is the answer (no wake word needed)
    private var pendingContacts: List<Contact>? = null
    private var pendingAction: ((Contact) -> Unit)? = null
    private val clearPending = Runnable { pendingContacts = null; pendingAction = null }

    private lateinit var wakeRegex: Regex
    private var assistantName = "riya"

    private fun loadWakeName() {
        assistantName = getSharedPreferences("riya", Context.MODE_PRIVATE)
            .getString("name", "riya")!!.trim().lowercase().ifEmpty { "riya" }
        val variants = if (assistantName == "riya") listOf("riya", "ria", "reya", "rhea", "riyah")
                       else listOf(assistantName)
        wakeRegex = Regex("\\b(" + variants.joinToString("|") { Regex.escape(it) } + ")\\b")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadWakeName()
        val channelId = "riya_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Riya", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName is listening")
            .setContentText("Say \"$assistantName\" followed by a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        // Keep the CPU awake so Riya keeps listening when the screen is off / locked
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya:listen").apply { acquire() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { handler.post { speaking = false; startListening() } }
            override fun onDone(utteranceId: String?) { handler.post { speaking = false; startListening() } }
        })
        startListening()
    }

    // ---------- Listening ----------

    private fun startListening() {
        if (!running || speaking) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.lowercase() ?: ""
                    if (text.isNotEmpty()) handleSpeech(text)
                    restart()
                }
                override fun onError(error: Int) = restart()
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    private fun restart() {
        if (running && !speaking) handler.postDelayed({ startListening() }, 400)
    }

    // ---------- Speaking ----------

    private fun say(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        if (ttsReady) {
            speaking = true
            recognizer?.cancel()
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "riya_utt")
        }
    }

    // ---------- Command handling ----------

    private fun handleSpeech(text: String) {
        val pending = pendingContacts
        if (pending != null) {
            handleChoice(text, pending)
            return
        }
        val match = wakeRegex.find(text) ?: return
        val command = text.substring(match.range.last + 1).trim()
        if (command.isEmpty()) { say("Yes?"); return }
        handle(command)
    }

    private fun handle(c: String) {
        when {
            c.startsWith("stop") || c.startsWith("exit") -> stopSelf()
            "lock" in c && "unlock" !in c -> lockScreen()
            "flashlight" in c || "torch" in c || "flash light" in c -> flashlight(c)
            "volume" in c || "mute" in c || "louder" in c -> volume(c)
            Regex("\\b(time|clock)\\b").containsMatchIn(c) && !c.startsWith("play") ->
                say("The time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            Regex("\\b(date|today)\\b").containsMatchIn(c) && !c.startsWith("play") ->
                say("Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()))
            "whatsapp" in c || c.startsWith("message") || c.startsWith("send message") -> whatsapp(c)
            c.startsWith("call") || c.startsWith("phone") -> {
                val name = c.replace(Regex("^(call|phone)( to)?"), "").trim()
                if (name.isEmpty()) say("Whom should I call?")
                else pickContact(name) { dial(it) }
            }
            "camera" in c -> openCamera()
            "play" in c -> {
                val m = Regex("play (.+?)(?: on (youtube|spotify))?$").find(c)
                val song = m?.groupValues?.get(1) ?: c.replace("play", "").trim()
                val platform = m?.groupValues?.get(2).orEmpty()
                if (platform == "youtube") youtube(song) else playSpotify(song)
            }
            "youtube" in c -> youtube(null)
            "spotify" in c -> openSpotify()
            else -> say("Sorry, I did not understand")
        }
    }

    // ---------- Contacts: search + ask whom ----------

    private fun findContacts(name: String): List<Contact> {
        val result = LinkedHashMap<String, Contact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val key = num.filter { it.isDigit() }.takeLast(10)
                if (key.isNotEmpty() && !result.containsKey(key)) result[key] = Contact(n, num)
            }
        }
        return result.values.toList()
    }

    private fun pickContact(name: String, action: (Contact) -> Unit) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Please allow contacts permission in the Riya app"); return
        }
        val list = findContacts(name)
        when {
            list.isEmpty() -> say("I could not find $name in your contacts")
            list.size == 1 -> action(list[0])
            else -> {
                val shown = list.take(5)
                pendingContacts = shown
                pendingAction = action
                handler.removeCallbacks(clearPending)
                handler.postDelayed(clearPending, 25000)
                val options = shown.mapIndexed { i, ct -> "${i + 1}, ${labelFor(ct, shown)}" }.joinToString(". ")
                say("I found ${shown.size} contacts named $name. $options. Whom do you want?")
            }
        }
    }

    private fun labelFor(c: Contact, all: List<Contact>): String =
        if (all.count { it.name == c.name } > 1)
            "${c.name}, number ending ${c.number.filter { it.isDigit() }.takeLast(2)}"
        else c.name

    private val numberWords = mapOf(
        "one" to 1, "won" to 1, "first" to 1, "1" to 1,
        "two" to 2, "to" to 2, "too" to 2, "second" to 2, "2" to 2,
        "three" to 3, "third" to 3, "3" to 3,
        "four" to 4, "for" to 4, "fourth" to 4, "4" to 4,
        "five" to 5, "fifth" to 5, "5" to 5
    )

    private fun handleChoice(text: String, list: List<Contact>) {
        if (Regex("\\b(cancel|no|nobody|none|stop)\\b").containsMatchIn(text)) {
            clearPending.run(); handler.removeCallbacks(clearPending)
            say("Okay, cancelled")
            return
        }
        val idx = text.split(" ").mapNotNull { numberWords[it] }.firstOrNull()
        var chosen: Contact? = if (idx != null && idx in 1..list.size) list[idx - 1] else null
        if (chosen == null) {
            val matches = list.filter { text.contains(it.name.lowercase()) || it.name.lowercase().contains(text.trim()) }
            if (matches.size == 1) chosen = matches[0]
        }
        if (chosen != null) {
            val action = pendingAction
            clearPending.run(); handler.removeCallbacks(clearPending)
            action?.invoke(chosen)
        } else {
            say("Sorry, say the number, like one or two")
        }
    }

    // ---------- Call ----------

    private fun dial(c: Contact) {
        say("Calling ${c.name}")
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                // TelecomManager places the call even when the phone is locked
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return
            } catch (e: Exception) { /* fall through */ }
            if (launch(Intent(Intent.ACTION_CALL, uri))) return
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
    }

    // ---------- WhatsApp ----------

    private fun whatsapp(c: String) {
        val cleaned = c.replace(Regex("^(send )?(a )?(whatsapp )?(message )?(on whatsapp )?(to )?"), "").trim()
        val m = Regex("^(.+?) (?:saying|that|message|as) (.+)$").find(cleaned)
        if (m == null) {
            say("Say it like: Riya send WhatsApp to Vinod saying hello")
            return
        }
        val name = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        pickContact(name) { contact ->
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            say("Sending WhatsApp to ${contact.name}")
            RiyaAccessibility.arm()
            val url = "https://wa.me/$digits?text=" + Uri.encode(text)
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ---------- Flashlight ----------

    private fun flashlight(c: String) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { say("No flashlight found"); return }
        torchOn = if ("off" in c) false else if ("on" in c) true else !torchOn
        try {
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("Could not control the flashlight") }
    }

    // ---------- Volume ----------

    private fun volume(c: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stream = AudioManager.STREAM_MUSIC
        val percent = Regex("(\\d{1,3})").find(c)?.groupValues?.get(1)?.toIntOrNull()
        when {
            "unmute" in c -> am.setStreamVolume(stream, max / 2, AudioManager.FLAG_SHOW_UI)
            "mute" in c -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            percent != null -> am.setStreamVolume(stream, (percent.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            "max" in c || "full" in c -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            "up" in c || "increase" in c || "raise" in c || "louder" in c || "high" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            }
            "down" in c || "decrease" in c || "lower" in c || "low" in c || "reduce" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
            else -> { say("Say volume up, down, mute, or a number"); return }
        }
        say("Volume " + (am.getStreamVolume(stream) * 100 / max) + " percent")
    }

    // ---------- Other actions ----------

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun lockScreen() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            dpm.lockNow()
        } else say("Enable Device admin in the Riya app first")
    }

    private fun openCamera() {
        if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) {
            if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) say("Camera not found")
        }
    }

    private fun youtube(query: String?) {
        val url = if (query.isNullOrBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        if (!launch(i)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openSpotify() {
        val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i == null || !launch(i)) say("Spotify is not installed")
    }

    private fun playSpotify(song: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))))) {
                say("Spotify is not installed")
            }
        }
    }

    override fun onDestroy() {
        running = false
        recognizer?.destroy()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
