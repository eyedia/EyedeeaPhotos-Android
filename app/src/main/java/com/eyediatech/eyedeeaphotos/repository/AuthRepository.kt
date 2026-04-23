package com.eyediatech.eyedeeaphotos.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun saveAuthData(token: String, householdId: String, sourceId: String?, username: String, userJson: String, group: String?) {
        sharedPreferences.edit()
            .putString("token", token)
            .putString("household_id", householdId)
            .putString("source_id", sourceId)
            .putString("username", username)
            .putString("user_json", userJson)
            .putString("group", group)
            .apply()
    }

    fun getToken(): String? = sharedPreferences.getString("token", null)
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
        sharedPreferences.edit().clear().apply()
    }

    fun isAuthenticated(): Boolean = getToken() != null
}
