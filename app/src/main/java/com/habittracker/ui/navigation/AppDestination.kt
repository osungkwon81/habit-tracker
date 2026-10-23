package com.habittracker.ui.navigation

enum class AppDestination(
    val route: String,
    val label: String,
) {
    HOME("home", "홈"),
    MORE("more", "전체"),
    ENTRY("entry", "기록"),
    ENTRY_EXERCISE("entry/exercise", "운동 기록"),
    DIARY("diary", "일기"),
    DIARY_NEW("diary/new", "일기 작성"),
    MEMO("memo", "메모"),
    MEMO_NEW("memo/new", "메모 작성"),
    STATS("stats", "통계"),
    SETTINGS("settings", "앱 관리"),
    STOCK("stock", "주식"),
    STOCK_ORDER("stock/order", "매수·매도"),
    STOCK_PORTFOLIO("stock/portfolio", "보유·매수"),
    STOCK_AUTOMATION("stock/automation", "자동매매·알림"),
    STOCK_REBALANCE("stock/rebalance", "리밸런싱"),
    STOCK_JOURNAL("stock/journal", "매매일지"),
    STOCK_SETTINGS("stock/settings", "KIS·안전 설정"),
    CARD("card", "카드"),
    CARD_CHARTS("card/charts", "카드 그래프"),
    ADMIN("admin", "기록 관리"),
    LOTTO("lotto", "동행복권"),
    LOTTO_645("lotto/645", "로또 6/45"),
    LOTTO_GENERATOR("lotto/645/generator", "번호 생성"),
    LOTTO_SAVED("lotto/645/saved", "저장 번호"),
    LOTTO_QR("lotto/645/qr", "QR 복권"),
    LOTTO_PURCHASE("lotto/645/purchases", "구입 이력"),
    LOTTO_WINNING("lotto/645/winnings", "당첨 이력"),
    LOTTO_DRAW("lotto/645/draws", "추첨 결과"),
    LOTTO_STATS("lotto/645/stats", "손익·성과"),
    LOTTO_IMPROVEMENT("lotto/645/improvement", "자동 개선"),
    PENSION_LOTTO("lotto/pension", "연금720+"),
    PENSION_LOTTO_GENERATOR("lotto/pension/generator", "번호 생성"),
    PENSION_LOTTO_SAVED("lotto/pension/saved", "저장 번호"),
    PENSION_LOTTO_PURCHASE("lotto/pension/purchases", "구입 이력"),
    PENSION_LOTTO_WINNING("lotto/pension/winnings", "당첨 이력"),
    PENSION_LOTTO_DRAW("lotto/pension/draws", "추첨 결과"),
    PENSION_LOTTO_MATCH("lotto/pension/match", "번호 조회"),
    PENSION_LOTTO_PATTERN("lotto/pension/pattern", "패턴 통계"),
    PENSION_LOTTO_STATS("lotto/pension/stats", "손익"),
    PENSION_LOTTO_IMPROVEMENT("lotto/pension/improvement", "자동 개선"),
    PLANT("plant", "반복 할 일"),
    PLANT_NEW("plant/new", "할 일 등록"),
    REMINDER("reminder", "리마인더"),
    REMINDER_NEW("reminder/new", "리마인더 등록"),
    EXCHANGE("exchange", "환율·환전"),
    EXCHANGE_RECORDS("exchange/records", "환전 기록"),
    EXCHANGE_EXPENSES("exchange/expenses", "외화 지출"),
    EXCHANGE_COLLECTION_SETTINGS("exchange/collection-settings", "환율 수집 설정"),
    NOTIFICATION_ASSISTANT("notification-assistant", "알림 생활비서"),
    NOTIFICATION_ASSISTANT_APPS("notification-assistant/apps", "알림 체크 앱"),
    NOTIFICATION_ASSISTANT_ACTIVE("notification-assistant/active", "실제 알림 목록"),
    NOTIFICATION_ASSISTANT_TASKS("notification-assistant/tasks", "알림에서 찾은 할 일");

    /** 상세 경로도 해당 상위 메뉴로 선택 표시하기 위한 경로 비교 함수다. */
    fun matches(candidateRoute: String?): Boolean =
        candidateRoute == route || candidateRoute?.startsWith("$route/") == true

    val parent: AppDestination?
        get() = when (this) {
            ENTRY_EXERCISE, STATS, ADMIN -> ENTRY
            DIARY_NEW -> DIARY
            MEMO_NEW -> MEMO
            PLANT_NEW -> PLANT
            REMINDER_NEW -> REMINDER
            STOCK_ORDER, STOCK_PORTFOLIO, STOCK_AUTOMATION, STOCK_REBALANCE,
            STOCK_JOURNAL, STOCK_SETTINGS -> STOCK
            CARD_CHARTS -> CARD
            LOTTO_645, PENSION_LOTTO -> LOTTO
            LOTTO_GENERATOR, LOTTO_SAVED, LOTTO_QR, LOTTO_PURCHASE, LOTTO_WINNING,
            LOTTO_DRAW, LOTTO_STATS, LOTTO_IMPROVEMENT -> LOTTO_645
            PENSION_LOTTO_GENERATOR, PENSION_LOTTO_SAVED, PENSION_LOTTO_PURCHASE,
            PENSION_LOTTO_WINNING, PENSION_LOTTO_DRAW, PENSION_LOTTO_MATCH,
            PENSION_LOTTO_PATTERN, PENSION_LOTTO_STATS, PENSION_LOTTO_IMPROVEMENT -> PENSION_LOTTO
            EXCHANGE_RECORDS, EXCHANGE_EXPENSES, EXCHANGE_COLLECTION_SETTINGS -> EXCHANGE
            NOTIFICATION_ASSISTANT_APPS, NOTIFICATION_ASSISTANT_ACTIVE,
            NOTIFICATION_ASSISTANT_TASKS -> NOTIFICATION_ASSISTANT
            else -> null
        }

    val children: List<AppDestination>
        get() = entries.filter { it.parent == this }

    val homeLabel: String
        get() = parent?.let { "${it.label} · $label" } ?: label

    val iconRes: Int
        get() = when (route.substringBefore('/')) {
            "stock" -> com.habittracker.R.drawable.ic_category_stock
            "card" -> com.habittracker.R.drawable.ic_category_card
            "lotto" -> com.habittracker.R.drawable.ic_category_lotto
            "memo" -> com.habittracker.R.drawable.ic_category_memo
            "diary" -> com.habittracker.R.drawable.ic_category_diary
            "plant" -> com.habittracker.R.drawable.ic_category_repeat
            "exchange" -> com.habittracker.R.drawable.ic_category_exchange
            "reminder", "notification-assistant" -> com.habittracker.R.drawable.ic_category_reminder
            "settings", "admin" -> com.habittracker.R.drawable.ic_category_settings
            else -> com.habittracker.R.drawable.ic_category_record
        }

    companion object {
        val bottomNavigation = listOf(HOME, STOCK, CARD, LOTTO, MORE)
        val menuGroups = entries.filter { it != HOME && it != MORE && it.parent == null }.groupBy {
            when (it.route.substringBefore('/')) {
                "stock", "card", "lotto", "exchange" -> "자산과 금융"
                "admin", "settings" -> "관리"
                else -> "일상과 기록"
            }
        }.let { groups ->
            listOf("자산과 금융", "일상과 기록", "관리").map { title -> title to groups.getValue(title) }
        }
    }
}
