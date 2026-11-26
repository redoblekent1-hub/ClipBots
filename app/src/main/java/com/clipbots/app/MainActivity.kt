package com.clipbots.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var logView: TextView
    private lateinit var queueView: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            queueView.text = "Queue: ${store.size()} link(s) waiting | ${Scheduler.pending(store)} post(s) scheduled"
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }

        fun check(label: String, v: Boolean): CheckBox {
            val c = CheckBox(this)
            c.text = label
            c.isChecked = v
            root.addView(c)
            return c
        }
        fun field(h: String, value: String, secret: Boolean = false, number: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = h
            e.setText(value)
            e.setSingleLine()
            if (secret) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
            root.addView(e)
            return e
        }
        fun button(t: String, f: () -> Unit) {
            val btn = Button(this)
            btn.text = t
            btn.setOnClickListener { f() }
            root.addView(btn)
        }

        val base = field("API base URL", store.baseUrl)
        val key = field("API key", store.apiKey, secret = true)
        val model = field("Chat model", store.model)
        val voice = field("TTS voice (alloy, nova, onyx...)", store.voice)
        val clips = field("Shorts per video (1-10)", store.clipCount.toString(), number = true)
        val minScore = field("Minimum virality score (0-100)", store.minScore.toString(), number = true)
        val c1080 = check("Export 1080p (off = 720p, faster and cooler)", store.p1080)
        val c916 = check("Export 9:16 vertical (shorts)", store.out916)
        val c169 = check("Export 16:9 horizontal", store.out169)
        val cVoice = check("Add voiceover hook line (off = original audio only)", store.voiceover)
        val cAuto = check("Auto-post shorts (9:16) after rendering", store.autoPost)
        val cYT = check("Post to YouTube Shorts", store.postYT)
        val cFB = check("Post to Facebook Reels", store.postFB)
        val times = field("Post times, 24h, comma separated", store.postTimes)
        val extraTags = field("Extra hashtags on every post (optional)", store.extraTags)
        val cDel = check("Delete the video file after it is posted", store.deleteAfter)
        val ytId = field("YouTube OAuth client ID", store.ytClientId)
        val ytSecret = field("YouTube OAuth client secret", store.ytClientSecret, secret = true)
        val fbPage = field("Facebook Page ID", store.fbPageId)
        val fbTok = field("Facebook Page access token", store.fbToken, secret = true)
        button("Save settings") {
            store.baseUrl = base.text.toString().trim()
            store.apiKey = key.text.toString().trim()
            store.model = model.text.toString().trim()
            store.voice = voice.text.toString().trim()
            store.clipCount = clips.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
            store.minScore = minScore.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 60
            store.p1080 = c1080.isChecked
            store.out916 = c916.isChecked || !c169.isChecked
            store.out169 = c169.isChecked
            store.voiceover = cVoice.isChecked
            store.autoPost = cAuto.isChecked
            store.postYT = cYT.isChecked
            store.postFB = cFB.isChecked
            store.postTimes = times.text.toString().trim()
            store.extraTags = extraTags.text.toString().trim()
            store.deleteAfter = cDel.isChecked
            store.ytClientId = ytId.text.toString().trim()
            store.ytClientSecret = ytSecret.text.toString().trim()
            store.fbPageId = fbPage.text.toString().trim()
            store.fbToken = fbTok.text.toString().trim()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        button("Connect YouTube (device code)") {
            store.ytClientId = ytId.text.toString().trim()
            store.ytClientSecret = ytSecret.text.toString().trim()
            YouTubeAuth.start(
                store,
                onCode = { code, url ->
                    runOnUiThread {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code", code))
                        AlertDialog.Builder(this)
                            .setTitle("Your code: $code")
                            .setMessage("The code is copied. Open $url, paste it, sign in to the channel you want to post to, and tap Allow. This app finishes connecting by itself.")
                            .setPositiveButton("Open page") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                },
                onResult = { msg ->
                    BotLog.log(msg)
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                },
            )
        }

        val link = field("Direct video link (.mp4)", "")
        button("Add link to queue") {
            val u = link.text.toString().trim()
            if (u.startsWith("http")) { store.enqueue(u); link.setText("") }
            else Toast.makeText(this, "Paste a full http(s) link", Toast.LENGTH_SHORT).show()
        }

        queueView = TextView(this).also { root.addView(it) }

        button("START bots (24/7)") {
            store.autostart = true
            startForegroundService(Intent(this, BotService::class.java))
        }
        button("STOP bots") {
            store.autostart = false
            startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP))
        }
        button("Allow unrestricted battery (important)") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        BotLog.listener = { ui.post { logView.text = BotLog.dump() } }
        logView.text = BotLog.dump()
        ui.post(tick)
    }

    override fun onPause() {
        BotLog.listener = null
        ui.removeCallbacks(tick)
        super.onPause()
    }
}
