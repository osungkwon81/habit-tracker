package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "kis_api_config")
data class KisApiConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "environment")
    val environment: String,
    @ColumnInfo(name = "app_key_encrypted")
    val encryptedAppKey: String,
    @ColumnInfo(name = "app_secret_encrypted")
    val encryptedAppSecret: String,
    @ColumnInfo(name = "account_number_encrypted")
    val encryptedAccountNumber: String,
    @ColumnInfo(name = "account_product_code_encrypted")
    val encryptedAccountProductCode: String,
    @ColumnInfo(name = "hts_id_encrypted")
    val encryptedHtsId: String? = null,
    @ColumnInfo(name = "access_token_encrypted")
    val encryptedAccessToken: String? = null,
    @ColumnInfo(name = "access_token_expired_at")
    val accessTokenExpiredAt: LocalDateTime? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
