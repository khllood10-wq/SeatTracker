package com.example.saudiaseattracker

sealed class Screen(val route: String, val title: String) {
    object Search        : Screen("search", "الرئيسية")
    object FlightDetails : Screen("flight_details", "الرحلة")
    object Passengers    : Screen("passengers", "المسافرين")
    object Seats         : Screen("seats", "المقاعد")      // ← جديد
    object Payment       : Screen("payment", "الدفع")
    object Login         : Screen("login", "تجهيز الاتصال")
}
