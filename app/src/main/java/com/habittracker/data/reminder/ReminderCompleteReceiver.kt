package com.habittracker.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(ReminderWorker.reminderIdKey, 0L)
        if (reminderId <= 0L) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as HabitTrackerApplication)
                    .appContainer.awaitReminderRepository()
                repository.complete(reminderId)
            } catch (error: Exception) {
                Log.e("ReminderComplete", "리마인더 $reminderId 완료 처리에 실패했습니다.", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
