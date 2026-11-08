package com.example.saudiaseattracker

import com.google.gson.annotations.SerializedName

// ═══════════════ REQUEST MODELS ═══════════════

data class SaudiaSearchRequest(
    @SerializedName("region") val region: String = "KSA",
    @SerializedName("fromRegion") val fromRegion: String = "KSA",
    @SerializedName("toRegion") val toRegion: String = "KSA",
    @SerializedName("airBoundsSearchInputs") val airBoundsSearchInputs: AirBoundsSearchInputs,
    @SerializedName("offerCodeAvailabilityInput") val offerCodeAvailabilityInput: OfferCodeAvailabilityInput
)

data class AirBoundsSearchInputs(
    @SerializedName("itineraries") val itineraries: List<Itinerary>,
    @SerializedName("travelers") val travelers: List<Traveler>,
    @SerializedName("commercialFareFamilies") val commercialFareFamilies: List<String> =
        listOf("NCFFGUESTN", "NCFFBUSN", "NCFFFIRSTN"),
    @SerializedName("searchPreferences") val searchPreferences: SearchPreferences
)

data class Itinerary(
    @SerializedName("originLocationCode") val originLocationCode: String,
    @SerializedName("destinationLocationCode") val destinationLocationCode: String,
    @SerializedName("departureDateTime") val departureDateTime: String,
    @SerializedName("directFlights") val directFlights: Boolean = false,
    @SerializedName("isRequestedBound") val isRequestedBound: Boolean = true
)

data class Traveler(@SerializedName("passengerTypeCode") val passengerTypeCode: String = "ADT")

data class SearchPreferences(
    @SerializedName("showUnavailableEntries") val showUnavailableEntries: Boolean = false,
    @SerializedName("showMilesPrice") val showMilesPrice: Boolean = false,
    @SerializedName("showSoldOut") val showSoldOut: Boolean = true
)

data class OfferCodeAvailabilityInput(
    @SerializedName("channel") val channel: String = "MOBILE_WEB",
    @SerializedName("pointOfSale") val pointOfSale: String = "SA",
    @SerializedName("currencyCode") val currencyCode: String = "SAR",
    @SerializedName("language") val language: String = "AR",
    @SerializedName("browserId") val browserId: String = "db9387ab-b6f2-4d1a-9a08-0a55ac7a89d9"
)

// ═══════════════ RESPONSE MODELS ═══════════════

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
