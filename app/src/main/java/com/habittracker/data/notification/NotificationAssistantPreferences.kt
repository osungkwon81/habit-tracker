package com.habittracker.data.notification

import android.content.Context

class NotificationAssistantPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("notification_assistant", Context.MODE_PRIVATE)

    fun observedPackages(): Set<String> = preferences.getStringSet("observed_packages", emptySet()).orEmpty().toSet()

    fun enabledPackages(): Set<String> = preferences.getStringSet("enabled_packages", emptySet()).orEmpty().toSet()

    fun recordObservedPackage(packageName: String) {
        if (packageName in observedPackages()) return
        preferences.edit().putStringSet("observed_packages", observedPackages() + packageName).apply()
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        val next = enabledPackages().toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        preferences.edit().putStringSet("enabled_packages", next).apply()
    }
}
