package com.eyediatech.eyedeeaphotos.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class AuthRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAuthData(
        token: String,
        refreshToken: String?,
        householdId: String,
        sourceId: String?,
        username: String,
        userJson: String,
        group: String?
    ) {
        val expiresAt = decodeJwtExp(token)
        android.util.Log.d("AUTH_DEBUG", "Saving Auth Data: Token exists=${token.isNotEmpty()}, RefreshToken exists=${refreshToken?.isNotEmpty() == true}, Exp=$expiresAt")
        sharedPreferences.edit()
            .putString("token", token)
            .putString("refresh_token", refreshToken)
            .putLong("expires_at", expiresAt)
            .putString("household_id", householdId)
            .putString("source_id", sourceId)
            .putString("username", username)
            .putString("user_json", userJson)
            .putString("group", group)
            .apply()
    }

    private fun decodeJwtExp(token: String): Long {
        try {
            val parts = token.split(".")
            if (parts.size == 3) {
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE))
                val json = JSONObject(payload)
                if (json.has("exp")) {
                    return json.getLong("exp") * 1000L
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0L
    }

    fun saveTokens(token: String, refreshToken: String?) {
        val expiresAt = decodeJwtExp(token)
        sharedPreferences.edit()
            .putString("token", token)
            .putString("refresh_token", refreshToken)
            .putLong("expires_at", expiresAt)
            .apply()
    }

    fun getToken(): String? = sharedPreferences.getString("token", null)
    fun getRefreshToken(): String? = sharedPreferences.getString("refresh_token", null)
    fun getExpiresAt(): Long = sharedPreferences.getLong("expires_at", 0L)
    fun getHouseholdId(): String? = sharedPreferences.getString("household_id", null)
    fun getSourceId(): String? = sharedPreferences.getString("source_id", null)
    fun getUsername(): String? = sharedPreferences.getString("username", null)
    fun getUserJson(): String? = sharedPreferences.getString("user_json", null)
    fun getGroup(): String? = sharedPreferences.getString("group", null)

    fun getEmail(): String? {
        val userJson = getUserJson() ?: return null
        return try {
            val user = com.google.gson.Gson().fromJson(userJson, com.eyediatech.eyedeeaphotos.data.User::class.java)
            user.email.ifBlank { null }
        } catch (e: Exception) {
            // If it's not a valid JSON, maybe the userJson itself is just the email string or name
            if (userJson.contains("@")) userJson else null
        }
    }

    fun clearAuthData() {
        android.util.Log.d("AUTH_DEBUG", "Clearing all Auth Data")
        sharedPreferences.edit().clear().apply()
    }

    fun isAuthenticated(): Boolean = getToken() != null
}
