package com.example.saudiaseattracker

import com.google.gson.annotations.SerializedName

data class SaudiaResponse(
    @SerializedName("data") val data: SaudiaData?
)

data class SaudiaData(
    @SerializedName("airBoundGroups") val airBoundGroups: List<AirBoundGroup>?
)

data class AirBoundGroup(
    @SerializedName("boundDetails") val boundDetails: BoundDetails?,
    @SerializedName("airBounds") val airBounds: List<AirBound>?
)

data class BoundDetails(
    @SerializedName("originLocationCode") val origin: String?,
    @SerializedName("destinationLocationCode") val destination: String?,
    @SerializedName("segments") val segments: List<Segment>?
)

data class Segment(
    @SerializedName("flightId") val flightId: String?
)

data class AirBound(
    @SerializedName("airBoundId") val airBoundId: String?,
    @SerializedName("fareFamilyCode") val fareFamilyCode: String?,
    @SerializedName("status") val status: FlightStatus?,
    @SerializedName("airOffer") val airOffer: AirOffer?
)

data class FlightStatus(
    @SerializedName("value") val value: String?
)

data class AirOffer(
    @SerializedName("totalPrice") val totalPrice: TotalPrice?
)

data class TotalPrice(
    @SerializedName("value") val value: Double?,
    @SerializedName("currencyCode") val currencyCode: String?
)
