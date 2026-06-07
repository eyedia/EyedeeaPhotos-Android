package com.eyediatech.eyedeeaphotos.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_sync_subscription",
    indices = [
        Index(value = ["householdId", "sourceId", "folderPath"], unique = true),
        Index(value = ["householdId", "storyPackId"], unique = true)
    ]
)
data class OfflineSyncSubscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val householdId: Long,
    val sourceId: Long? = null,
    val folderPath: String? = null,
    val storyPackId: Long? = null,
    val displayName: String,
    val galleryRelativePath: String? = null,
    val listUrl: String,
    val lastSyncedPhotoIds: String = "[]",
    val lastSyncAt: Long? = null,
    val downloadVariant: String = "small",
    val enabled: Boolean = true,
    val status: String = "idle"
)
