package com.example.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var voiceController: AiVoiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Replace with your Gemini API key
        voiceController = AiVoiceController(this, apiKey = "YOUR_GEMINI_API_KEY")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)

            addView(Button(context).apply {
                text = "1. Grant Microphone & Overlay Permissions"
                setOnClickListener { requestCorePermissions() }
            })

            addView(Button(context).apply {
                text = "2. Enable Accessibility Service (Phone Control)"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })

            addView(Button(context).apply {
                text = "3. Start Draw-to-Open Gesture Pad"
                setOnClickListener {
                    startService(Intent(context, GestureOverlayService::class.java))
                }
            })

            addView(Button(context).apply {
                text = "4. Speak to AI Assistant"
                setOnClickListener {
                    voiceController.startListening()
                }
            })
        }

        setContentView(layout)
    }

    private fun requestCorePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
