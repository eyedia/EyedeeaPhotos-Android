package com.eyediatech.eyedeeaphotos

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editText = findViewById<EditText>(R.id.urlEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val keepScreenOnSwitch = findViewById<Switch>(R.id.keepScreenOnSwitch)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val currentUrl = prefs.getString("cast_url", "http://192.168.86.101") ?: "http://192.168.86.101"
        val keepScreenOn = prefs.getBoolean("keep_screen_on", true)

        editText.setText(currentUrl)
        keepScreenOnSwitch.isChecked = keepScreenOn

        saveButton.setOnClickListener {
            val newUrl = editText.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                prefs.edit().apply {
                    putString("cast_url", newUrl)
                    putBoolean("keep_screen_on", keepScreenOnSwitch.isChecked)
                    apply()
                }
                finish()
            }
        }
    }
}
