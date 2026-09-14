package com.habittracker.data.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

data class ActiveAssistantNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val content: String,
)

class NotificationAssistantListenerService : NotificationListenerService() {
    companion object {
        val activeItems = MutableStateFlow<List<ActiveAssistantNotification>>(emptyList())
        private var connectedService: NotificationAssistantListenerService? = null
        fun refreshActiveSnapshot() { connectedService?.refreshActiveItems() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        refreshActiveItems()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val preferences = NotificationAssistantPreferences(this)
        preferences.recordObservedPackage(sbn.packageName)
        refreshActiveItems()
        if (sbn.packageName !in preferences.enabledPackages()) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val content = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val item = try {
            NotificationAssistantParser.parse(sbn.key, sbn.packageName, title, content, sbn.postTime)
        } catch (error: Exception) {
            Log.e("NotificationAssistant", "알림 분석 실패: ${sbn.packageName}", error)
            null
        } ?: return
        scope.launch {
            try {
                val container = (application as HabitTrackerApplication).appContainer
                container.awaitNotificationAssistantRepository().saveCandidate(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("NotificationAssistant", "알림 후보 저장 실패: ${sbn.packageName}", error)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshActiveItems()
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        activeItems.value = emptyList()
        super.onListenerDisconnected()
    }

    private fun refreshActiveItems() {
        val enabled = NotificationAssistantPreferences(this).enabledPackages()
        activeItems.value = activeNotifications.orEmpty()
            .asSequence()
            .filter { it.packageName in enabled && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { notification ->
                val extras = notification.notification.extras
                ActiveAssistantNotification(
                    key = notification.key,
                    packageName = notification.packageName,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    content = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                )
            }
            .toList()
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        activeItems.value = emptyList()
        scope.cancel()
        super.onDestroy()
    }
}
