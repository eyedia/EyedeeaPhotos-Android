package com.eyediatech.eyedeeaphotos.api

import com.eyediatech.eyedeeaphotos.data.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("/api/v1/auth/refresh")
    fun refreshSync(@Body request: RefreshRequest): retrofit2.Call<RefreshResponse>

    @Headers("Accept: application/json")
    @GET("/api/v1/auth/device")
    suspend fun getDeviceCode(): Response<DeviceCodeResponse>

    @Headers("Accept: application/json")
    @POST("/api/v1/auth/device")
    suspend fun pollDeviceStatus(@Body request: PollDeviceStatusRequest): Response<PollDeviceResponse>

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

    // Offline Sync Endpoints
    @GET
    fun getPhotoManifest(
        @Header("Authorization") token: String,
        @Url url: String
    ): retrofit2.Call<okhttp3.ResponseBody>

    @Streaming
    @GET("/api/v1/{householdId}/view/photos/{photo_id}")
    fun downloadPhoto(
        @Header("Authorization") token: String,
        @Path("householdId") householdId: String,
        @Path("photo_id") photoId: String,
        @Query("download") download: Boolean = true,
        @Query("size") size: String? = null,
        @Query("original") original: Boolean? = null
    ): retrofit2.Call<okhttp3.ResponseBody>

    @Streaming
    @POST("/api/v1/photos/download-zip")
    fun downloadZip(
        @Header("Authorization") token: String,
        @Body request: DownloadZipRequest
    ): retrofit2.Call<okhttp3.ResponseBody>
}

data class Source(val id: String, val name: String)
data class ScanStatus(val active: Boolean)

data class DownloadZipRequest(
    val size: String?,
    val original: Boolean? = null,
    val photoIds: List<ZipPhotoEntry>
)

data class ZipPhotoEntry(
    val photo_id: String,
    val source_id: Long,
    val filename: String,
    val folder_name: String
)
