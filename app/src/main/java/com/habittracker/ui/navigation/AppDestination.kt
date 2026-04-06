package com.habittracker.ui.navigation

enum class AppDestination(
    val route: String,
    val label: String,
) {
    HOME("home", "\uB2EC\uB825"),
    ENTRY("entry", "\uAE30\uB85D"),
    DIARY("diary", "\uC77C\uAE30\uC7A5"),
    STATS("stats", "\uD1B5\uACC4"),
    ADMIN("admin", "\uAD00\uB9AC"),
    LOTTO("lotto", "\uB85C\uB610"),
}