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

data class BulkOperationsRequest(
    val schema_version: String,
    val job_code: String,
    val idempotency_key: String,
    val generated_at: String,
    val household_id: Long,
    val source_id: Long,
    val dir_type: String,
    val process_count: Int,
    val insert_into_db: Boolean,
    val curated_request: CuratedRequest?
)

data class CuratedRequest(
    val top_level: CuratedRequestTopLevel,
    val uploads: List<CuratedUpload>,
    val operations: List<Any>
)

data class CuratedRequestTopLevel(
    val schema_version: String,
    val idempotency_key: String,
    val generated_at: String,
    val household_id: Long,
    val source_id: Long,
    val dir_type: String,
    val process_count: Int,
    val insert_into_db: Boolean,
    val current_folder_path: String
)

data class CuratedUpload(
    val id: String,
    val destination_path: String,
    val summary: CuratedUploadSummary,
    val files: List<CuratedUploadFile>
)

data class CuratedUploadSummary(
    val total_files: Int,
    val uploaded_count: Int,
    val skipped_count: Int,
    val by_skip_reason: Map<String, Int>
)

data class CuratedUploadFile(
    val id: String,
    val relative_path: String,
    val name: String,
    val size_bytes: Long,
    val mime_type: String,
    val last_modified_ms: Long?,
    val sha256_hash: String?,
    val source: String,
    val status: String,
    val skip_reason: String?
)

data class BulkOperationsResponse(
    val success: Boolean,
    val queued: Boolean?,
    val mode: String?,
    val data: BulkOperationsResponseData?
)

data class BulkOperationsResponseData(
    val job_code: String?,
    val request_id: Long?,
    val message_id: String?,
    val queued_at: String?
)
