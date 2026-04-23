package com.eyediatech.eyedeeaphotos.data

import com.google.gson.annotations.SerializedName

data class QuotaResponse(
    val success: Boolean,
    val data: QuotaData?
)

data class QuotaData(
    val hasQuota: Boolean,
    val planName: String?,
    val usedMb: Double,
    val quotaMb: Double,
    val remainingMb: Double,
    val usedPercent: Double,
    val photoCountQuota: PhotoCountQuota?
)

data class PhotoCountQuota(
    val hasQuota: Boolean,
    val limits: List<QuotaLimit>?
)

data class QuotaLimit(
    val intervalType: String,
    val limitCount: Int,
    val usedCount: Int,
    val remainingCount: Int
)

data class EnhancedUploadResponse(
    val success: Boolean,
    val uploadedCount: Int,
    val skippedCount: Int,
    val folderPath: String?,
    val uploaded: List<UploadedFile>?,
    val scanQueued: Boolean,
    val error: String?,
    val code: String?
)

data class UploadedFile(
    val name: String,
    val path: String,
    val size: Long
)

data class EnhancedScanResponse(
    val success: Boolean,
    val message: String?,
    val status: String?,
    val requestId: String?,
    val error: String?,
    val code: String?
)
