package com.habittracker.ui.navigation

enum class AppDestination(
    val route: String,
    val label: String,
) {
    HOME("home", "달력"),
    ENTRY("entry", "기록"),
    DIARY("diary", "일기장"),
    MEMO("memo", "메모장"),
    LEARNING("learning", "학습"),
    STATS("stats", "통계"),
    ADMIN("admin", "관리"),
    LOTTO("lotto", "로또"),
}
