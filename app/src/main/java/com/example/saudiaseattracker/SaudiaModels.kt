// ════════════ Passenger Confirmation Models ════════════

data class ElmVerifyRequest(
    @SerializedName("documentType") val documentType: String = "I", // I = Iqama/ID, P = Passport
    @SerializedName("documentNumber") val documentNumber: String,
    @SerializedName("nationality") val nationality: String = "SA",
    @SerializedName("dateOfBirth") val dateOfBirth: String // YYYY-MM-DD
)

data class CartExtensionRequest(
    @SerializedName("content") val content: String,
    @SerializedName("travelerId") val travelerId: String,
    @SerializedName("extensionType") val extensionType: String = "TextExtension",
    @SerializedName("name") val name: String = "YQENSTATUS"
)

// للاختبار الأول: نبعت JSON عادي (هنجرب لو السيرفر هيقبل)
data class TravelerWebUpdateRequest(
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("documentNumber") val documentNumber: String,
    @SerializedName("dateOfBirth") val dateOfBirth: String,
    @SerializedName("nationality") val nationality: String = "SA",
    @SerializedName("gender") val gender: String = "M"
)
