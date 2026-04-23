package com.eyediatech.eyedeeaphotos.data

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val token: String,
    val user: User,
    val group: String?
)

data class User(
    val id: Int,
    val name: String,
    val email: String,
    @SerializedName("current_household_id")
    val currentHouseholdId: String,
    @SerializedName("default_source_id")
    val defaultSourceId: String?,
    @SerializedName("current_household_role")
    val role: String?
)

data class DeviceCodeResponse(
    @SerializedName("device_code")
    val deviceCode: String,
    @SerializedName("user_code")
    val userCode: String,
    @SerializedName("verification_uri")
    val verificationUri: String,
    @SerializedName("expires_in")
    val expiresIn: Int,
    val interval: Int
)

data class PollResponse(
    val token: String?,
    val user: User?,
    val status: String // "pending", "authorized", "expired"
)
