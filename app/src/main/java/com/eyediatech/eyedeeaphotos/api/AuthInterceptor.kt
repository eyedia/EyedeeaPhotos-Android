package com.eyediatech.eyedeeaphotos.api

import android.content.Context
import android.content.Intent
import com.eyediatech.eyedeeaphotos.data.RefreshRequest
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.ui.LoginActivity
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context, private val authRepository: AuthRepository) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        // Skip auth checks for login, device, and refresh routes
        val path = request.url.encodedPath
        if (path.contains("/auth/login") || path.contains("/auth/device") || path.contains("/auth/refresh")) {
            return chain.proceed(request)
        }

        // 1. Preemptive token refresh if within 60s of expiry
        val expiresAt = authRepository.getExpiresAt()
        val now = System.currentTimeMillis()
        if (expiresAt > 0 && expiresAt - now < 60000) {
            val refreshToken = authRepository.getRefreshToken()
            if (!refreshToken.isNullOrEmpty()) {
                val refreshSuccess = performTokenRefresh(refreshToken)
                if (!refreshSuccess) {
                    handleLogout()
                    return chain.proceed(request) // proceed anyway to let 401 fail normally or handle otherwise
                }
            } else {
                handleLogout()
            }
        }

        // 2. Add current token
        val token = authRepository.getToken()
        if (!token.isNullOrEmpty()) {
            // Note: If request already has an Authorization header, we don't need to overwrite it unless we want to.
            // Retrofit might add it if we use @Header("Authorization"). 
            // We should ensure we always use the latest token.
            val builder = request.newBuilder()
            // Some calls pass authHeader explicitly, which might be stale if we just refreshed.
            // Let's replace the existing Authorization header if there's any.
            builder.header("Authorization", "Bearer $token")
            request = builder.build()
        }

        var response = chain.proceed(request)

        // 3. Reactive token refresh on 401
        if (response.code == 401) {
            response.close() // Close the original response body before retrying
            
            val refreshToken = authRepository.getRefreshToken()
            if (!refreshToken.isNullOrEmpty()) {
                val refreshSuccess = performTokenRefresh(refreshToken)
                if (refreshSuccess) {
                    val newToken = authRepository.getToken()
                    if (!newToken.isNullOrEmpty()) {
                        val newRequest = request.newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .build()
                        response = chain.proceed(newRequest)
                    }
                } else {
                    handleLogout()
                }
            } else {
                handleLogout()
            }
        }

        return response
    }

    @Synchronized
    private fun performTokenRefresh(refreshToken: String): Boolean {
        // Prevent multiple threads from refreshing simultaneously
        val currentToken = authRepository.getToken()
        val currentRefreshToken = authRepository.getRefreshToken()
        
        // If another thread already refreshed successfully (token doesn't match what we started with?),
        // Wait, just try refresh
        try {
            val call = RetrofitClient.instance.refreshSync(RefreshRequest(refreshToken))
            val response = call.execute()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    authRepository.saveTokens(body.token, body.refreshToken ?: refreshToken)
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun handleLogout() {
        authRepository.clearAuthData()
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }
}
