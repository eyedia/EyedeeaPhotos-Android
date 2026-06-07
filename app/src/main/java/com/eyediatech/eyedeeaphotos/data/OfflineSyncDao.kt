package com.eyediatech.eyedeeaphotos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface OfflineSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: OfflineSyncSubscription): Long

    @Query("SELECT * FROM offline_sync_subscription WHERE type = 'album' AND householdId = :householdId AND sourceId = :sourceId AND folderPath = :folderPath LIMIT 1")
    suspend fun getAlbumSubscription(householdId: Long, sourceId: Long, folderPath: String): OfflineSyncSubscription?

    @Query("SELECT * FROM offline_sync_subscription WHERE type = 'story_pack' AND householdId = :householdId AND storyPackId = :storyPackId LIMIT 1")
    suspend fun getStoryPackSubscription(householdId: Long, storyPackId: Long): OfflineSyncSubscription?

    @Query("SELECT * FROM offline_sync_subscription WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): OfflineSyncSubscription?

    @Query("SELECT * FROM offline_sync_subscription WHERE enabled = 1")
    suspend fun getActiveSubscriptions(): List<OfflineSyncSubscription>

    @Update
    suspend fun update(subscription: OfflineSyncSubscription)

    @Query("UPDATE offline_sync_subscription SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}
