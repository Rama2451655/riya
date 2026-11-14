package com.rama.riya

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "Voice Assistant"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "Say: <name> lock screen / open camera / open YouTube / open Spotify / play <song> / call <name> / WhatsApp / flashlight / volume / time"
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        })

        val prefs = getSharedPreferences("riya", Context.MODE_PRIVATE)
        val nameBox = EditText(this).apply {
            hint = "Assistant name (e.g. Riya, Jarvis, Maya)"
            setText(prefs.getString("name", "Riya"))
            setSingleLine()
        }
        layout.addView(TextView(this).apply { text = "Call your assistant by this name:" })
        layout.addView(nameBox)

        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        button("1. Allow microphone, contacts, calls & notifications") {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        }
        button("2. Allow lock screen (Device admin)") {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed so Riya can lock your screen.")
            }
            startActivity(intent)
        }
        button("3. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("4. Allow background running (battery)") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("5. Enable WhatsApp auto-send (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("6. SAVE NAME & START") {
            val name = nameBox.text.toString().trim().ifEmpty { "Riya" }
            prefs.edit().putString("name", name).apply()
            val i = Intent(this, RiyaService::class.java)
            stopService(i)               // restart so the new name is used
            startForegroundService(i)
        }
        button("STOP") {
            stopService(Intent(this, RiyaService::class.java))
        }

        setContentView(layout)
    }
}
