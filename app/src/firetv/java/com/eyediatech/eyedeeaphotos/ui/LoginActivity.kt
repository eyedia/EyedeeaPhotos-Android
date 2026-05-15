package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eyediatech.eyedeeaphotos.BuildConfig
import com.eyediatech.eyedeeaphotos.MainActivity
import com.eyediatech.eyedeeaphotos.api.RetrofitClient
import com.eyediatech.eyedeeaphotos.data.PollDeviceStatusRequest
import com.eyediatech.eyedeeaphotos.databinding.ActivityLoginBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.util.QRGenerator
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginActivity : FragmentActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository
    
    private var pollingJob: Job? = null
    private var countdownJob: Job? = null

    private var deviceCodeRefreshAttempts = 0
    private val maxRefreshAttempts = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)
        
        if (authRepository.isAuthenticated()) {
            navigateToMain()
            return
        }
        
        binding.refreshButton.setOnClickListener {
            deviceCodeRefreshAttempts = 0
            fetchDeviceCode()
        }
        
        setupFireTVLogin()
    }

    private fun setupFireTVLogin() {
        fetchDeviceCode()
    }

    private fun fetchDeviceCode() {
        pollingJob?.cancel()
        countdownJob?.cancel()

        if (deviceCodeRefreshAttempts >= maxRefreshAttempts) {
            showFailureState()
            return
        }

        deviceCodeRefreshAttempts++
        
        binding.loadingProgressBar.visibility = View.VISIBLE
        binding.qrContainer.visibility = View.GONE
        binding.noInputContainer.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getDeviceCode()
                if (response.isSuccessful && response.body() != null) {
                    val deviceData = response.body()!!
                    
                    // Removed QR Code generation to comply with Amazon Appstore policies
                    // val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
                    // val qrUrl = "$baseUrl${deviceData.verificationUri}"
                    // val qrBitmap = QRGenerator.generateQRCode(qrUrl, 600)
                    // binding.qrImageView.setImageBitmap(qrBitmap)
                    
                    binding.userCodeTextView.text = deviceData.userCode
                    
                    binding.loadingProgressBar.visibility = View.GONE
                    binding.qrContainer.visibility = View.VISIBLE
                    
                    startPolling(deviceData.deviceCode, deviceData.interval)
                    startCountdown(deviceData.expiresIn)
                } else {
                    handleFetchError()
                }
            } catch (e: Exception) {
                handleFetchError()
            }
        }
    }

    private fun startPolling(deviceCode: String, interval: Int) {
        pollingJob = lifecycleScope.launch {
            while (true) {
                delay(interval * 1000L)
                try {
                    val response = RetrofitClient.instance.pollDeviceStatus(
                        PollDeviceStatusRequest(deviceCode, android.os.Build.MODEL)
                    )
                    Log.d("AUTH_DEBUG", "Poll response code: ${response.code()}")
                    if (response.isSuccessful && response.body() != null) {
                        val pollData = response.body()!!
                        Log.d("AUTH_DEBUG", "Poll data status: ${pollData.status}, hasUser: ${pollData.user != null}, hasToken: ${pollData.token != null}")
                        
                        // Handle the new 200 OK polling logic
                        when (pollData.status) {
                            "approved" -> {
                                if (pollData.user != null && pollData.token != null) {
                                    val userJson = Gson().toJson(pollData.user)
                                    authRepository.saveAuthData(
                                        pollData.token, pollData.refreshToken, pollData.user.currentHouseholdId,
                                        pollData.user.defaultSourceId, pollData.user.name, userJson, pollData.group ?: ""
                                    )
                                    Log.d("AUTH_DEBUG", "Auth data saved, navigating to main.")
                                    navigateToMain()
                                    break
                                } else {
                                    Log.w("AUTH_DEBUG", "Status is approved but missing user or token!")
                                }
                            }
                            "expired" -> {
                                Log.d("AUTH_DEBUG", "Device code expired (200 OK). Requesting a new one.")
                                fetchDeviceCode()
                                break
                            }
                            "pending" -> {
                                // Continue polling
                                Log.d("AUTH_DEBUG", "Status is pending, continuing to poll.")
                            }
                            else -> {
                                // Fallback for old API behavior if `status` field is missing 
                                // but we got a 200 OK with user info.
                                Log.d("AUTH_DEBUG", "Unknown or null status: ${pollData.status}. Checking user/token fallback.")
                                if (pollData.user != null && pollData.token != null) {
                                    val userJson = Gson().toJson(pollData.user)
                                    authRepository.saveAuthData(
                                        pollData.token, pollData.refreshToken, pollData.user.currentHouseholdId,
                                        pollData.user.defaultSourceId, pollData.user.name, userJson, pollData.group ?: ""
                                    )
                                    Log.d("AUTH_DEBUG", "Fallback auth data saved, navigating to main.")
                                    navigateToMain()
                                    break
                                }
                            }
                        }
                    } else if (response.code() == 410) { 
                        Log.d("AUTH_DEBUG", "Device code expired or consumed (410). Requesting a new one.")
                        fetchDeviceCode()
                        break 
                    } else if (response.code() == 400) {
                        val errorStr = response.errorBody()?.string() ?: ""
                        Log.d("AUTH_DEBUG", "Polling returned 400: $errorStr")
                        // If it's the expected 'authorization_pending', keep polling.
                        if (errorStr.contains("expired")) {
                            Log.d("AUTH_DEBUG", "Device code expired (400). Requesting a new one.")
                            fetchDeviceCode()
                            break
                        }
                    } else {
                        Log.w("AUTH_DEBUG", "Unhandled poll response code: ${response.code()} error: ${response.errorBody()?.string()}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("AUTH_DEBUG", "Polling failed with exception: ${e.message}")
                }
            }
        }
    }

    private fun startCountdown(ttlSeconds: Int) {
        binding.pollingProgressBar.max = ttlSeconds
        countdownJob = lifecycleScope.launch {
            for (i in ttlSeconds downTo 0) {
                binding.pollingProgressBar.progress = i
                delay(1000)
            }
            Log.d("AUTH_DEBUG", "Local countdown finished. Awaiting poll result to refresh code.")
        }
    }

    private fun handleFetchError() {
        lifecycleScope.launch {
            binding.loadingProgressBar.visibility = View.GONE
            Toast.makeText(this@LoginActivity, "Could not get code. Retrying...", Toast.LENGTH_SHORT).show()
            delay(3000) 
            fetchDeviceCode()
        }
    }

    private fun showFailureState() {
        binding.loadingProgressBar.visibility = View.GONE
        binding.qrContainer.visibility = View.GONE
        binding.noInputContainer.visibility = View.VISIBLE
        binding.refreshButton.requestFocus()
    }



    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        countdownJob?.cancel()
    }
}
