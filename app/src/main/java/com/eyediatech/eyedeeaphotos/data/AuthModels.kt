package com.eyediatech.eyedeeaphotos.data

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val email: String,
    val pass: String,
    @SerializedName("device_name")
    val deviceName: String
)

data class LoginResponse(
    val token: String,
    val user: User,
    val group: String
)


data class User(
    val id: String,
    val name: String,
    val email: String,
    @SerializedName("current_household_id")
    val currentHouseholdId: String,
    @SerializedName("default_source_id")
    val defaultSourceId: String
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

data class PollDeviceStatusRequest(
    @SerializedName("device_code")
    val deviceCode: String
)
