package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

/** KIS 잔고를 조회한 날의 주식 매입금액·평가금액·손익을 한 행으로 보관한다. */
@Entity(tableName = "stock_asset_snapshot")
data class StockAssetSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_date")
    val snapshotDate: LocalDate,
    @ColumnInfo(name = "purchase_amount")
    val purchaseAmount: Long,
    @ColumnInfo(name = "valuation_amount")
    val valuationAmount: Long,
    @ColumnInfo(name = "evaluation_profit_loss")
    val evaluationProfitLoss: Long,
    @ColumnInfo(name = "realized_profit_loss")
    val realizedProfitLoss: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
