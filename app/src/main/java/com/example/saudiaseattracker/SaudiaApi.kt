@POST("security/elm-profiles/status-verification")
suspend fun verifyElmProfile(
    @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
    @Query("lang") lang: String = "ar-SA",
    @HeaderMap headers: Map<String, String>,
    @Body request: ElmVerifyRequest
): Response<ResponseBody>

@POST("shopping/carts/{cartId}/extensions")
suspend fun addCartExtension(
    @Path("cartId") cartId: String,
    @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
    @Query("lang") lang: String = "ar-SA",
    @HeaderMap headers: Map<String, String>,
    @Body request: List<CartExtensionRequest>
): Response<ResponseBody>

@PATCH("shopping/carts/{cartId}/travelers/web/{travelerId}")
suspend fun updateTravelerWeb(
    @Path("cartId") cartId: String,
    @Path("travelerId") travelerId: String,
    @Query("guestOfficeId") guestOfficeId: String = "JEDSV08MA",
    @Query("lang") lang: String = "ar-SA",
    @HeaderMap headers: Map<String, String>,
    @Body request: Any // مؤقتاً، لأنه ممكن يكون JSON عادي أو مشفر
): Response<ResponseBody>
