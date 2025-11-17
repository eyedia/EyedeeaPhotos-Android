package com.eyediatech.eyedeeaphotos

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editText = findViewById<EditText>(R.id.urlEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val currentUrl = prefs.getString("cast_url", "http://192.168.86.101") ?: "http://192.168.86.101"
        editText.setText(currentUrl)

        saveButton.setOnClickListener {
            val newUrl = editText.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                prefs.edit().putString("cast_url", newUrl).apply()
                finish()
            }
        }
    }
}
