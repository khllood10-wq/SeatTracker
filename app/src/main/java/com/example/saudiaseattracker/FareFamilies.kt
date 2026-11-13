package com.example.saudiaseattracker

object FareFamilies {
    const val HOSPITALITY_BASIC   = "hospitality_basic"
    const val HOSPITALITY_PREMIUM = "hospitality_premium"
    const val HOSPITALITY_ECONOMY = "hospitality_economy"
    const val ECONOMY             = "economy"
    const val BUSINESS            = "business"
    const val ALL_HOSPITALITY     = "all_hospitality"
    const val ALL_BUSINESS        = "all_business"
    const val FIRST               = "first"

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

    // خريطة ربط الأكواد الإنجليزية (من الـ API) بالأسماء العربية (من السكريبت)
    private val CODE_TO_NAME = mapOf(
        "NSAVERE" to "🟡 الضيافة الاقتصادية",
        "NBASICE" to "🟢 الضيافة الأساسية",
        "NFLEXE"  to "🔵 الضيافة المميزة",
        "NBASICB" to "🔷 الأعمال",
        "NFLEXB"  to "💼 جميع الأعمال"
    )

    fun getArabicNameForCode(code: String): String = CODE_TO_NAME[code] ?: code
}
