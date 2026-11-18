package com.eyediatech.eyedeeaphotos.data

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class QuotaResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: QuotaData?
)

@Keep
data class QuotaData(
    @SerializedName("hasQuota") val hasQuota: Boolean,
    @SerializedName("planName") val planName: String?,
    @SerializedName("usedMb") val usedMb: Double,
    @SerializedName("quotaMb") val quotaMb: Double,
    @SerializedName("remainingMb") val remainingMb: Double,
    @SerializedName("usedPercent") val usedPercent: Double,
    @SerializedName("photoCountQuota") val photoCountQuota: PhotoCountQuota?
)

@Keep
data class PhotoCountQuota(
    @SerializedName("hasQuota") val hasQuota: Boolean,
    @SerializedName("limits") val limits: List<QuotaLimit>?
)

@Keep
data class QuotaLimit(
    @SerializedName("intervalType") val intervalType: String,
    @SerializedName("limitCount") val limitCount: Int,
    @SerializedName("usedCount") val usedCount: Int,
    @SerializedName("remainingCount") val remainingCount: Int
)

@Keep
data class EnhancedUploadResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("uploadedCount") val uploadedCount: Int,
    @SerializedName("skippedCount") val skippedCount: Int,
    @SerializedName("folderPath") val folderPath: String?,
    @SerializedName("uploaded") val uploaded: List<UploadedFile>?,
    @SerializedName("scanQueued") val scanQueued: Boolean,
    @SerializedName("error") val error: String?,
    @SerializedName("code") val code: String?
)

@Keep
data class UploadedFile(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("size") val size: Long
)

@Keep
data class EnhancedScanResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("requestId") val requestId: String?,
    @SerializedName("error") val error: String?,
    @SerializedName("code") val code: String?
)

@Keep
data class BulkOperationsRequest(
    @SerializedName("schema_version") val schema_version: String,
    @SerializedName("job_code") val job_code: String,
    @SerializedName("idempotency_key") val idempotency_key: String,
    @SerializedName("generated_at") val generated_at: String,
    @SerializedName("household_id") val household_id: Long,
    @SerializedName("source_id") val source_id: Long,
    @SerializedName("dir_type") val dir_type: String,
    @SerializedName("process_count") val process_count: Int,
    @SerializedName("insert_into_db") val insert_into_db: Boolean,
    @SerializedName("curated_request") val curated_request: CuratedRequest?
)

@Keep
data class CuratedRequest(
    @SerializedName("top_level") val top_level: CuratedRequestTopLevel,
    @SerializedName("uploads") val uploads: List<CuratedUpload>,
    @SerializedName("operations") val operations: List<Any>
)

@Keep
data class CuratedRequestTopLevel(
    @SerializedName("schema_version") val schema_version: String,
    @SerializedName("idempotency_key") val idempotency_key: String,
    @SerializedName("generated_at") val generated_at: String,
    @SerializedName("household_id") val household_id: Long,
    @SerializedName("source_id") val source_id: Long,
    @SerializedName("dir_type") val dir_type: String,
    @SerializedName("process_count") val process_count: Int,
    @SerializedName("insert_into_db") val insert_into_db: Boolean,
    @SerializedName("current_folder_path") val current_folder_path: String
)

@Keep
data class CuratedUpload(
    @SerializedName("id") val id: String,
    @SerializedName("destination_path") val destination_path: String,
    @SerializedName("summary") val summary: CuratedUploadSummary,
    @SerializedName("files") val files: List<CuratedUploadFile>
)

@Keep
data class CuratedUploadSummary(
    @SerializedName("total_files") val total_files: Int,
    @SerializedName("uploaded_count") val uploaded_count: Int,
    @SerializedName("skipped_count") val skipped_count: Int,
    @SerializedName("by_skip_reason") val by_skip_reason: Map<String, Int>
)

@Keep
data class CuratedUploadFile(
    @SerializedName("id") val id: String,
    @SerializedName("relative_path") val relative_path: String,
    @SerializedName("name") val name: String,
    @SerializedName("size_bytes") val size_bytes: Long,
    @SerializedName("mime_type") val mime_type: String,
    @SerializedName("last_modified_ms") val last_modified_ms: Long?,
    @SerializedName("sha256_hash") val sha256_hash: String?,
    @SerializedName("source") val source: String,
    @SerializedName("status") val status: String,
    @SerializedName("skip_reason") val skip_reason: String?
)

@Keep
data class BulkOperationsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("queued") val queued: Boolean?,
    @SerializedName("mode") val mode: String?,
    @SerializedName("data") val data: BulkOperationsResponseData?
)

@Keep
data class BulkOperationsResponseData(
    @SerializedName("job_code") val job_code: String?,
    @SerializedName("request_id") val request_id: Long?,
    @SerializedName("message_id") val message_id: String?,
    @SerializedName("queued_at") val queued_at: String?
)
