package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eyediatech.eyedeeaphotos.BuildConfig
import com.eyediatech.eyedeeaphotos.MainActivity
import com.eyediatech.eyedeeaphotos.databinding.ActivityLoginBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)

        setupMobileLogin()
        intent?.let { handleDeepLink(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null && data.scheme == "eyedeea" && data.host == "auth") {
            val refreshToken = data.getQueryParameter("refresh_token")
            val token = data.getQueryParameter("token")
            val name = data.getQueryParameter("name")
            val householdId = data.getQueryParameter("household_id")
            val sourceId = data.getQueryParameter("source_id")
            val userJson = data.getQueryParameter("user")
            val group = data.getQueryParameter("group")

            if (!token.isNullOrBlank() && !name.isNullOrBlank() && !householdId.isNullOrBlank()) {
                authRepository.saveAuthData(token, refreshToken, householdId, sourceId, name, userJson ?: "", group)
                navigateToMain()
            } else {
                Toast.makeText(this, "Login failed: Missing data", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMobileLogin() {
        binding.loginForm.visibility = View.VISIBLE
        binding.browserLoginButton.setOnClickListener {
            val timestamp = System.currentTimeMillis()
            val encodedCallback = Uri.encode("eyedeea://auth?t=$timestamp")
            val loginUrl = "${BuildConfig.LOGIN_URL}&callback=$encodedCallback"
            
            try {
                val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(this, Uri.parse(loginUrl))
            } catch (e: Exception) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))
                startActivity(intent)
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
