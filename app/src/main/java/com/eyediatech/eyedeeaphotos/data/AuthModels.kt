package com.eyediatech.eyedeeaphotos.data

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class LoginRequest(
    @SerializedName("email")
    val email: String,
    @SerializedName("pass")
    val pass: String,
    @SerializedName("device_name")
    val deviceName: String
)

@Keep
data class LoginResponse(
    @SerializedName("token")
    val token: String,
    @SerializedName("refresh_token")
    val refreshToken: String?,
    @SerializedName("user")
    val user: User,
    @SerializedName("group")
    val group: String
)

@Keep
data class User(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("email")
    val email: String,
    @SerializedName("current_household_id")
    val currentHouseholdId: String,
    @SerializedName("default_source_id")
    val defaultSourceId: String
)

@Keep
data class DeviceCodeResponse(
    @SerializedName("device_code")
    val deviceCode: String,
    @SerializedName("user_code")
    val userCode: String,
    @SerializedName("verification_uri")
    val verificationUri: String,
    @SerializedName("expires_in")
    val expiresIn: Int,
    @SerializedName("interval")
    val interval: Int
)

@Keep
data class PollDeviceStatusRequest(
    @SerializedName("device_code")
    val deviceCode: String,
    @SerializedName("device_name")
    val deviceName: String
)

@Keep
data class PollDeviceResponse(
    @SerializedName("status")
    val status: String?, // "pending", "approved", "expired"
    @SerializedName("message")
    val message: String?,
    @SerializedName("token")
    val token: String?,
    @SerializedName("refresh_token")
    val refreshToken: String?,
    @SerializedName("user")
    val user: User?,
    @SerializedName("group")
    val group: String?
)

@Keep
data class RefreshRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

@Keep
data class RefreshResponse(
    @SerializedName("token")
    val token: String,
    @SerializedName("refresh_token")
    val refreshToken: String?
)
