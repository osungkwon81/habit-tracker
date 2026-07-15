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
    STATS("stats", "통계", "📊"),
    STOCK("stock", "주식", "📈"),
    STOCK_ORDER("stock/order", "매수·매도", "💱"),
    STOCK_PORTFOLIO("stock/portfolio", "보유·매수", "📊"),
    STOCK_AUTOMATION("stock/automation", "자동매도", "🛡️"),
    STOCK_REBALANCE("stock/rebalance", "리밸런싱", "⚖️"),
    STOCK_JOURNAL("stock/journal", "매매일지", "📓"),
    STOCK_SETTINGS("stock/settings", "KIS·안전 설정", "⚙️"),
    CARD("card", "카드", "💳"),
    ADMIN("admin", "관리", "⚙️"),
    LOTTO("lotto", "로또", "🎯"),
    PLANT("plant", "화분", "🪴"),
}
