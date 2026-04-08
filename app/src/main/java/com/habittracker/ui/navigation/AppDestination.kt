package com.habittracker.ui.navigation

enum class AppDestination(
    val route: String,
    val label: String,
    val emoji: String,
) {
    HOME("home", "홈", "🗓️"),
    ENTRY("entry", "기록", "✍️"),
    DIARY("diary", "일기", "📔"),
    MEMO("memo", "메모", "📝"),
    LEARNING("learning", "학습", "🧠"),
    STATS("stats", "통계", "📊"),
    ADMIN("admin", "관리", "⚙️"),
    LOTTO("lotto", "로또", "🎯"),
}
