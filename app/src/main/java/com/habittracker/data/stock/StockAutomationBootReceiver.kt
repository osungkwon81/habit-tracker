package com.habittracker.data.stock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StockAutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as HabitTrackerApplication
                val canPostNotifications =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                if (
                    canPostNotifications &&
                    application.appContainer.habitRepository.getStockSafetyConfig().monitoringEnabled
                ) {
                    StockAutomationService.start(context.applicationContext)
                }
            } catch (error: Exception) {
                Log.e("StockAutomationBoot", "Failed to restore stock monitoring after restart.", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
