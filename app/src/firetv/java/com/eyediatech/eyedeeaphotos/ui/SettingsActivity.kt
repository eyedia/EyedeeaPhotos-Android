package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.eyediatech.eyedeeaphotos.databinding.ActivitySettingsBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)

        setupUI()
    }

    private fun setupUI() {
        val username = authRepository.getUsername() ?: "User"
        binding.userNameTextView.text = username
        binding.avatarTextView.text = username.take(1).uppercase()
        binding.userEmailTextView.text = authRepository.getEmail() ?: "No Email"

        binding.logoutButton.setOnClickListener { logout() }
        
        binding.logoLinkButton.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, "https://eyedeeaphotos.eyediatech.com/".toUri())
            startActivity(browserIntent)
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            authRepository.clearAuthData()
            // Clear web data to ensure clean logout
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()

            val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
