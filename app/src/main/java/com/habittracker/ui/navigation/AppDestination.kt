package com.habittracker.ui.navigation

enum class AppDestination(
    val route: String,
    val label: String,
) {
    HOME("home", "홈"),
    ENTRY("entry", "기록"),
    DIARY("diary", "일기"),
    MEMO("memo", "메모"),
    STATS("stats", "통계"),
    STOCK("stock", "주식"),
    STOCK_ORDER("stock/order", "매수·매도"),
    STOCK_PORTFOLIO("stock/portfolio", "보유·매수"),
    STOCK_AUTOMATION("stock/automation", "자동매도"),
    STOCK_REBALANCE("stock/rebalance", "리밸런싱"),
    STOCK_JOURNAL("stock/journal", "매매일지"),
    STOCK_SETTINGS("stock/settings", "KIS·안전 설정"),
    CARD("card", "카드"),
    ADMIN("admin", "관리"),
    LOTTO("lotto", "동행복권"),
    LOTTO_645("lotto/645", "로또 6/45"),
    PENSION_LOTTO("lotto/pension", "연금720+"),
    PENSION_LOTTO_GENERATOR("lotto/pension/generator", "연금번호 생성"),
    PLANT("plant", "화분");

    /** 상세 경로도 해당 상위 메뉴로 선택 표시하기 위한 경로 비교 함수다. */
    fun matches(candidateRoute: String?): Boolean =
        candidateRoute == route || candidateRoute?.startsWith("$route/") == true

    companion object {
        val bottomNavigation = listOf(HOME, CARD, STOCK, MEMO, PLANT, LOTTO)
    }
}
