package com.github.reygnn.b2b.data.remote

import com.github.reygnn.b2b.data.remote.dto.TokenResponseDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * Spotify accounts API — token endpoint only.
 *
 * Lives on a different base URL (`https://accounts.spotify.com/`) than the
 * Web API and MUST be served by an OkHttpClient that does NOT have
 * [com.github.reygnn.b2b.data.remote.AuthInterceptor] installed. The token
 * endpoint does not accept Bearer auth; a 401 here, fed back to the main
 * AuthInterceptor, would trigger refresh-on-refresh recursion.
 *
 * See `di/AppModules.kt` for the @AccountsClient-qualified wiring.
 */
interface SpotifyAccountsApi {

    @FormUrlEncoded
    @POST("api/token")
    suspend fun exchangeAuthorizationCode(
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("client_id") clientId: String,
        @Field("code_verifier") codeVerifier: String,
    ): Response<TokenResponseDto>

    @FormUrlEncoded
    @POST("api/token")
    suspend fun refreshToken(
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
        @Field("client_id") clientId: String,
    ): Response<TokenResponseDto>
}
