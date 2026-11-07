package com.example.saudiaseattracker

sealed class Screen(val route: String) {
    object Search : Screen("search")
    object Passengers : Screen("passengers")
    object Flights : Screen("flights")
}
