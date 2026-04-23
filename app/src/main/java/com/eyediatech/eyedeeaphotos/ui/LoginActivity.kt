package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eyediatech.eyedeeaphotos.BuildConfig
import com.eyediatech.eyedeeaphotos.MainActivity
import com.eyediatech.eyedeeaphotos.api.RetrofitClient
import com.eyediatech.eyedeeaphotos.databinding.ActivityLoginBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.util.QRGenerator
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository
    private var pollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)

        android.util.Log.d("AUTH_DEBUG", "onCreate called with intent: $intent")

        if (BuildConfig.FLAVOR == "firetv") {
            setupFireTVLogin()
        } else {
            setupMobileLogin()
            intent?.let { handleDeepLink(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.d("AUTH_DEBUG", "onNewIntent called with intent: $intent")
        setIntent(intent) // This is the missing piece!
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        android.util.Log.d("AUTH_DEBUG", "--------------------------------------")
        android.util.Log.d("AUTH_DEBUG", "Intent Action: ${intent.action}")
        android.util.Log.d("AUTH_DEBUG", "Full Deep Link URI: $data")
        
        if (data != null) {
            android.util.Log.d("AUTH_DEBUG", "Scheme: ${data.scheme}")
            android.util.Log.d("AUTH_DEBUG", "Host: ${data.host}")
            android.util.Log.d("AUTH_DEBUG", "Path: ${data.path}")
            android.util.Log.d("AUTH_DEBUG", "Query: ${data.query}")
            
            // Check if it's the specific auth deep link
            if (data.scheme == "eyedeea" && data.host == "auth") {
                val token = data.getQueryParameter("token")
                val name = data.getQueryParameter("name")
                val householdId = data.getQueryParameter("household_id")
                val sourceId = data.getQueryParameter("source_id")
                val userJson = data.getQueryParameter("user")
                val group = data.getQueryParameter("group")

                android.util.Log.d("AUTH_DEBUG", "Parsed Token exists: ${token != null}")
                android.util.Log.d("AUTH_DEBUG", "Parsed Name: $name")
                android.util.Log.d("AUTH_DEBUG", "Parsed HouseholdID: $householdId")

                if (!token.isNullOrBlank() && !name.isNullOrBlank() && !householdId.isNullOrBlank()) {
                    android.util.Log.d("AUTH_DEBUG", "SUCCESS: Saving auth data and navigating to main")
                    authRepository.saveAuthData(token, householdId, sourceId, name, userJson ?: "", group)
                    navigateToMain()
                } else {
                    android.util.Log.e("AUTH_DEBUG", "FAILURE: One or more required params are null or blank")
                    Toast.makeText(this, "Login failed: Missing data", Toast.LENGTH_SHORT).show()
                }
            } else {
                android.util.Log.w("AUTH_DEBUG", "WARNING: Deep link scheme or host did not match 'eyedeea://auth'")
            }
        } else {
            android.util.Log.d("AUTH_DEBUG", "No data found in intent")
        }
        android.util.Log.d("AUTH_DEBUG", "--------------------------------------")
    }

    private fun setupMobileLogin() {
        binding.loginForm.visibility = View.VISIBLE
        binding.browserLoginButton.setOnClickListener {
            // LOGIN_URL already contains ?device=android
            val loginUrl = "${BuildConfig.LOGIN_URL}&callback=eyedeea://auth"
            android.util.Log.d("AUTH_DEBUG", "Launching browser with URL: $loginUrl")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))
            startActivity(intent)
        }
    }

    private fun setupFireTVLogin() {
        binding.qrContainer.visibility = View.VISIBLE
        fetchDeviceCode()
    }

    private fun fetchDeviceCode() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getDeviceCode()
                if (response.isSuccessful && response.body() != null) {
                    val deviceData = response.body()!!
                    val qrBitmap = QRGenerator.generateQRCode(deviceData.verificationUri, 600)
                    binding.qrImageView.setImageBitmap(qrBitmap)
                    binding.userCodeTextView.text = "Code: ${deviceData.userCode}"
                    startPolling(deviceData.deviceCode, deviceData.interval)
                } else {
                    Toast.makeText(this@LoginActivity, "Failed to get QR code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPolling(deviceCode: String, interval: Int) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (true) {
                delay(interval * 1000L)
                try {
                    val response = RetrofitClient.instance.pollDeviceStatus(deviceCode)
                    if (response.isSuccessful && response.body() != null) {
                        val authData = response.body()!!
                        val userJson = Gson().toJson(authData.user)
                        authRepository.saveAuthData(
                            authData.token,
                            authData.user.currentHouseholdId,
                            authData.user.defaultSourceId,
                            authData.user.name,
                            userJson,
                            authData.group
                        )
                        navigateToMain()
                        break
                    } else if (response.code() == 400 || response.code() == 404) {
                        // Still pending or not found, continue polling
                    } else if (response.code() == 410) {
                        // Expired
                        fetchDeviceCode()
                        break
                    }
                } catch (e: Exception) {
                    // Ignore network errors during polling
                }
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}
