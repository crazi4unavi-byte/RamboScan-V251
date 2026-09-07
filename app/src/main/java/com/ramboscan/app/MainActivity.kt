package com.ramboscan.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        val scanButton = findViewById<Button>(R.id.scanButton).apply {
            // RAMBO Scan is intentionally silent. Do not inherit Android touch-click audio or haptics.
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
        }
        scanButton.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
    }
}
