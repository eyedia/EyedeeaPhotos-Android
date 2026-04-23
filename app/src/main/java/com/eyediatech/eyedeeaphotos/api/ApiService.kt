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
    suspend fun getSources(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String
    ): Response<List<Source>>

    @GET("/api/v1/{householdId}/sources/{sourceId}/browse/upload/quota-summary")
    suspend fun getQuotaSummary(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String
    ): Response<QuotaResponse>

    @Multipart
    @POST("/api/v1/{householdId}/sources/{sourceId}/browse/upload")
    suspend fun uploadPhotos(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String,
        @Part("folderPath") folderPath: okhttp3.RequestBody,
        @Part("scanAfterUpload") scanAfterUpload: okhttp3.RequestBody,
        @Part photos: List<okhttp3.MultipartBody.Part>,
        @Part("relativePaths") relativePaths: Array<okhttp3.RequestBody>
    ): Response<EnhancedUploadResponse>

    @POST("/api/v1/{householdId}/sources/{sourceId}/scan")
    suspend fun triggerScan(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String,
        @Query("folder_name") folderName: String = "raw",
        @Query("process") process: String = "background",
        @Query("process_count") processCount: Int = 4
    ): Response<EnhancedScanResponse>

    @GET("/api/v1/{householdId}/sources/{sourceId}/scan")
    suspend fun getScanStatus(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("sourceId") sourceId: String
    ): Response<ScanStatus>
}

data class Source(val id: String, val name: String)
data class ScanStatus(val active: Boolean)
