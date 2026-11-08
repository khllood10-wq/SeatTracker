package com.example.saudiaseattracker

sealed class Screen(val route: String, val title: String) {
    object Search        : Screen("search", "الرئيسية")
    object Passengers    : Screen("passengers", "المسافرين")
    object Payment       : Screen("payment", "الدفع")
    object Login         : Screen("login", "تجهيز الاتصال")
}
