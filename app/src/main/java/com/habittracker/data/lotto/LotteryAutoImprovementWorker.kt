package com.habittracker.data.lotto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.habittracker.HabitTrackerApplication
import com.habittracker.MainActivity
import kotlinx.coroutines.CancellationException

class LotteryAutoImprovementWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val product = inputData.getString(PRODUCT)?.let { value -> LotteryProduct.entries.find { it.name == value } }
            ?: return Result.failure()
        val automatic = inputData.getBoolean(AUTOMATIC, false)
        if (automatic && !isEnabled(applicationContext, product)) return Result.success()
        return try {
            val repository = (applicationContext as HabitTrackerApplication).appContainer.awaitRepository()
            repository.autoImprovement.run(product, automatic)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("LotteryAutoImprovement", "product=$product phase=worker failed; no automatic retry", error)
            if (automatic && isEnabled(applicationContext, product)) showChange(applicationContext, product,
                "자동 개선 보류: ${error.message ?: "자료·기록을 확인해 주세요."}")
            Result.failure(workDataOf("error" to (error.message ?: "자동 개선 실패").take(1000)))
        }
    }

    companion object {
        private const val PRODUCT = "product"
        private const val AUTOMATIC = "automatic"
        private const val PREFS = "lottery-auto-improvement"
        private const val CHANNEL = "lottery-improvement-changes"

        fun isEnabled(context: Context, product: LotteryProduct): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(product.name, false)

        internal fun setEnabled(context: Context, product: LotteryProduct, enabled: Boolean) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = isEnabled(context, product)
            require(preferences.edit().putBoolean(product.name, enabled).commit()) {
                "자동 개선 설정을 저장하지 못했습니다."
            }
            try {
                if (enabled) enqueue(context, product, automatic = true)
                else WorkManager.getInstance(context).cancelUniqueWork(workName(product))
            } catch (error: Exception) {
                if (!preferences.edit().putBoolean(product.name, previous).commit()) {
                    error.addSuppressed(IllegalStateException("자동 개선 예약 실패 후 설정 복구도 실패했습니다."))
                }
                throw error
            }
        }

        fun enqueueIfEnabled(context: Context, product: LotteryProduct) {
            if (isEnabled(context, product)) enqueue(context, product, automatic = true)
        }

        internal fun enqueue(context: Context, product: LotteryProduct, automatic: Boolean) {
            val request = OneTimeWorkRequestBuilder<LotteryAutoImprovementWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(PRODUCT to product.name, AUTOMATIC to automatic))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(product), ExistingWorkPolicy.KEEP, request)
        }

        internal fun showChange(context: Context, product: LotteryProduct, message: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            try {
                val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val messageHash = LottoDesignRecordJson.hash(message)
                if (preferences.getString("notification-${product.name}", null) == messageHash) return
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "복권 생성 설정 변경", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "자동 개선의 적용·복구 및 확인이 필요한 중단을 알립니다."; lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
                val intent = PendingIntent.getActivity(context, 6400 + product.ordinal,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(6500 + product.ordinal, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("${product.label} 생성 개선")
                    .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                    .setContentIntent(intent).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
                if (!preferences.edit().putString("notification-${product.name}", messageHash).commit()) {
                    Log.e("LotteryAutoImprovement", "product=$product phase=notification_dedup save failed")
                }
            } catch (error: Exception) { Log.e("LotteryAutoImprovement", "product=$product phase=notification failed", error) }
        }

        private fun workName(product: LotteryProduct) = "lottery-auto-improvement-${product.name}"
    }
}
