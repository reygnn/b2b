package com.github.reygnn.b2b.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface TokenStore {
    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?
    suspend fun store(accessToken: String, refreshToken: String, expiresAtEpochMs: Long)
    suspend fun refresh(): String?
    suspend fun clear()

    /**
     * Reactive flag: true while a refresh token is stored, false otherwise.
     * Updated synchronously by [store] and [clear]. UI uses this to gate
     * between the login screen and the whitelist screen.
     */
    fun authState(): StateFlow<Boolean>
}

/**
 * EncryptedSharedPreferences-backed store. Refresh wiring is delegated to
 * [PkceAuthManager.refresh] to keep the HTTP call in one place.
 */
@Singleton
class TokenStoreImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val refresher: dagger.Lazy<PkceAuthManager>,
) : TokenStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "spotify_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val authStateFlow: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(prefs.contains(KEY_REFRESH))
    }

    override fun authState(): StateFlow<Boolean> = authStateFlow

    override suspend fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)
    override suspend fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override suspend fun store(accessToken: String, refreshToken: String, expiresAtEpochMs: Long) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
            .apply()
        authStateFlow.value = true
    }

    override suspend fun refresh(): String? = refresher.get().refresh()

    override suspend fun clear() {
        prefs.edit().clear().apply()
        authStateFlow.value = false
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
    }
}
