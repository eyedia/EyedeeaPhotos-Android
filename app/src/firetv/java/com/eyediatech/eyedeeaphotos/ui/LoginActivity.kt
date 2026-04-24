package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class LoginActivity : AppCompatActivity() {

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
        binding.failureTextView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getDeviceCode()
                if (response.isSuccessful && response.body() != null) {
                    val deviceData = response.body()!!
                    
                    val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
                    val qrUrl = "$baseUrl${deviceData.verificationUri}"
                    Log.d("AUTH_DEBUG", "Generating QR Code for URL: $qrUrl")
                    
                    val qrBitmap = QRGenerator.generateQRCode(qrUrl, 600)
                    
                    binding.qrImageView.setImageBitmap(qrBitmap)
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
                        PollDeviceStatusRequest(deviceCode)
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val authData = response.body()!!
                        if (authData.user != null) {
                            val userJson = Gson().toJson(authData.user)
                            authRepository.saveAuthData(
                                authData.token, authData.user.currentHouseholdId,
                                authData.user.defaultSourceId, authData.user.name, userJson, authData.group
                            )
                            navigateToMain()
                            break
                        }
                    } else if (response.code() == 410) { 
                        Log.d("AUTH_DEBUG", "Device code expired or consumed. Requesting a new one.")
                        fetchDeviceCode()
                        break 
                    }
                } catch (e: Exception) {
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
        binding.failureTextView.visibility = View.VISIBLE
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
