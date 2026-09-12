package com.habittracker.data

import android.content.Context

object AppSettingsStore {
    private const val preferencesName = "app-settings"
    private const val lotteryResultNotificationsKey = "lottery-result-notifications"
    private const val lotterySyncFailureNotificationsKey = "lottery-sync-failure-notifications"

    fun areLotteryResultNotificationsEnabled(context: Context): Boolean =
        preferences(context).getBoolean(lotteryResultNotificationsKey, true)

    fun setLotteryResultNotificationsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(lotteryResultNotificationsKey, enabled).apply()
    }

    fun areLotterySyncFailureNotificationsEnabled(context: Context): Boolean =
        preferences(context).getBoolean(lotterySyncFailureNotificationsKey, true)

    fun setLotterySyncFailureNotificationsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(lotterySyncFailureNotificationsKey, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}
