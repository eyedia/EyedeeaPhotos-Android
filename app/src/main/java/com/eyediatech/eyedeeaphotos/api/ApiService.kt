package com.eyediatech.eyedeeaphotos.api

import com.eyediatech.eyedeeaphotos.data.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("/api/v1/auth/device")
    suspend fun getDeviceCode(): Response<DeviceCodeResponse>

    @POST("/api/v1/auth/device")
    suspend fun pollDeviceStatus(@Query("device_code") deviceCode: String): Response<LoginResponse>

    @GET("/api/v1/{householdId}/sources")
    suspend fun getSources(@Path("householdId") householdId: String): Response<List<Source>>

    @GET("/api/v1/{householdId}/sources/{sourceId}/browse/upload/quota-summary")
    suspend fun getQuotaSummary(
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String
    ): Response<QuotaSummary>

    @Multipart
    @POST("/api/v1/{householdId}/sources/{sourceId}/browse/upload")
    suspend fun uploadPhotos(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String,
        @Part("folderPath") folderPath: okhttp3.RequestBody,
        @Part photos: List<okhttp3.MultipartBody.Part>
    ): Response<UploadResponse>

    @POST("/api/v1/{householdId}/sources/{sourceId}/scan")
    suspend fun triggerScan(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String,
        @Query("folder") folder: String = "raw",
        @Query("process") process: String = "background"
    ): Response<Unit>

    @GET("/api/v1/{householdId}/sources/{sourceId}/scan")
    suspend fun getScanStatus(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String
    ): Response<ScanStatus>
}

data class Source(val id: String, val name: String)
data class QuotaSummary(val canUpload: Boolean, val reason: String?)
data class UploadResponse(val success: Boolean, val message: String?)
data class ScanStatus(val active: Boolean)
