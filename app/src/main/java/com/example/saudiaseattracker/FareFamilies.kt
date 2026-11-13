package com.example.saudiaseattracker

/**
 * فئات السفر (Fare Families) الخاصة بالسعودية
 * مستخرجة من سكريبت تمم 19.3.0
 */
object FareFamilies {
    const val HOSPITALITY_BASIC   = "hospitality_basic"
    const val HOSPITALITY_PREMIUM = "hospitality_premium"
    const val HOSPITALITY_ECONOMY = "hospitality_economy"
    const val ECONOMY             = "economy"
    const val BUSINESS            = "business"
    const val ALL_HOSPITALITY     = "all_hospitality"
    const val ALL_BUSINESS        = "all_business"
    const val FIRST               = "first"

    /** أكواد الفئات لكل نوع (من FARE_CODE_MAP في السكريبت) */
    val FARE_CODE_MAP: Map<String, List<String>> = mapOf(
        HOSPITALITY_BASIC   to listOf("NBASICE"),
        HOSPITALITY_PREMIUM to listOf("NFLEXE"),
        HOSPITALITY_ECONOMY to listOf("NSAVERE"),
        ECONOMY             to listOf("NSAVERE"),
        BUSINESS            to listOf("NBASICB"),
        ALL_HOSPITALITY     to listOf("NSAVERE", "NBASICE", "NFLEXE"),
        ALL_BUSINESS        to listOf("NBASICB", "NFLEXB"),
        FIRST               to emptyList()
    )

    /** قائمة العرض في الواجهة */
    val DISPLAY_OPTIONS: List<Pair<String, String>> = listOf(
        HOSPITALITY_ECONOMY to "🟡 الضيافة الاقتصادية",
        HOSPITALITY_BASIC   to "🟢 الضيافة الأساسية",
        HOSPITALITY_PREMIUM to "🔵 الضيافة المميزة",
        ALL_HOSPITALITY     to "🎫 جميع الضيافة",
        BUSINESS            to "🔷 الأعمال",
        ALL_BUSINESS        to "💼 جميع الأعمال",
        FIRST               to "👑 الأولى",
        ECONOMY             to "✈️ الاقتصادية"
    )

    fun displayName(key: String): String =
        DISPLAY_OPTIONS.firstOrNull { it.first == key }?.second ?: key
}
