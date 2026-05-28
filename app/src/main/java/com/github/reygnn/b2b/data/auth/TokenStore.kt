// androidx.security:security-crypto (EncryptedSharedPreferences + MasterKey) is
// deprecated Jetpack-wide with no drop-in successor; the API still functions at
// runtime and we keep it deliberately for this single-device app. Suppress is
// file-scoped because the deprecated imports themselves emit warnings.
@file:Suppress("DEPRECATION")

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

    /**
     * Persist tokens from the initial OAuth code exchange. **The only correct
     * call site is [com.github.reygnn.b2b.data.auth.PkceAuthManager.exchangeAuthorizationCode]**;
     * the name encodes that contract so a future contributor who reaches for
     * a generic `store` finds no such method and has to think about whether
     * they meant [storeIfMatchingEpoch] (refresh path, race-safe against
     * concurrent logout) or whether they really do mean "initial exchange,
     * no prior session can be in flight".
     *
     * Unconditional write — bypasses the session-epoch check used by
     * [storeIfMatchingEpoch]. Safe here because a [clear] (logout) during
     * the initial OAuth round-trip is not a meaningful user gesture: the
     * user is on the Login screen, there is no Sign-out control yet, and
     * by the time the redirect callback fires they have not had a chance
     * to interact with the session in any way.
     *
     * Serialised on the same monitor as [clear] and [storeIfMatchingEpoch],
     * so the prefs / authState / sessionEpoch transitions are still
     * mutually atomic even on the exchange path.
     */
    suspend fun storeFromInitialExchange(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
    )

    /**
     * Refresh the access token via Spotify's `/api/token`.
     *
     * Concurrent callers are coalesced inside [PkceAuthManager.refresh]: only
     * one HTTP refresh request goes out at a time, and a caller that arrives
     * after another caller has already produced a fresher token simply
     * receives that fresher value without re-hitting the network. This avoids
     * the refresh-token-invalidation race when Spotify rotates the refresh
     * token: two parallel 401s used to race two POSTs to /api/token, the
     * second of which Spotify would (after rotation) reject.
     *
     * @param staleAccessToken the access token that triggered the 401 in the
     *   calling interceptor. If the store already holds a different access
     *   token by the time this call enters the mutex (i.e. another thread has
     *   refreshed in the meantime), that fresher token is returned directly
     *   and no HTTP request is issued. Pass null when the caller has no
     *   anchor (e.g. there was no prior token); in that case the call
     *   unconditionally performs a refresh.
     * @return the access token to use going forward, or null if no refresh
     *   token is stored or the refresh request failed.
     */
    suspend fun refresh(staleAccessToken: String?): String?
    suspend fun clear()

    /**
     * Reactive flag: true while a refresh token is stored, false otherwise.
     * Updated synchronously by [storeFromInitialExchange] / [storeIfMatchingEpoch]
     * and [clear]. UI uses this to gate between the login screen and the
     * whitelist screen.
     */
    fun authState(): StateFlow<Boolean>

    /**
     * Monotonically increasing session counter. Incremented by every [clear]
     * call. The refresh path captures this value before its HTTP round-trip
     * and passes it to [storeIfMatchingEpoch] when persisting the result; if
     * a [clear] (logout) lands while the refresh is in flight, the epoch will
     * have advanced and the store call is rejected. Without this, a refresh
     * that happens to be suspended on the network at the moment the user taps
     * "Sign out" would resume after the prefs wipe and silently re-create
     * the tokens — the user would appear logged out in the UI but remain
     * fully authenticated under the hood.
     */
    fun sessionEpoch(): Long

    /**
     * Conditionally persist a refreshed token. The write happens iff
     * [expectedEpoch] still equals the current [sessionEpoch] at the moment
     * the operation acquires the internal lock. Returns true on persist,
     * false on a session-epoch mismatch (logout-during-refresh) — in the
     * false case the refresh result is dropped on the floor by the caller.
     *
     * The check-and-write pair is atomic with respect to [clear]; the two
     * methods serialise on the same lock.
     */
    suspend fun storeIfMatchingEpoch(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
    ): Boolean
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

    // Serialises the prefs-write + authStateFlow + sessionEpoch transitions
    // in [storeFromInitialExchange], [storeIfMatchingEpoch], and [clear].
    // We use a JVM monitor rather than a coroutines [kotlinx.coroutines.sync.Mutex] because the
    // critical sections only touch non-suspending operations (prefs edit,
    // state-flow assignment, long increment) and the rest of the codebase
    // already uses `synchronized` for the same pattern (see
    // [com.github.reygnn.b2b.diagnostics.LogBuffer]). Held very briefly; no
    // contention concerns.
    private val lock = Any()

    // Single-writer (every mutator runs under [lock]) but multi-reader
    // ([sessionEpoch] is read from the refresh-coordination path on
    // arbitrary dispatchers). `@Volatile` gives readers the JMM
    // release/acquire semantics they need without taking the monitor.
    @Volatile private var _sessionEpoch: Long = 0L

    override fun authState(): StateFlow<Boolean> = authStateFlow
    override fun sessionEpoch(): Long = _sessionEpoch

    override suspend fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)
    override suspend fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override suspend fun storeFromInitialExchange(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
    ) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_ACCESS, accessToken)
                .putString(KEY_REFRESH, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
                .apply()
            authStateFlow.value = true
        }
    }

    override suspend fun storeIfMatchingEpoch(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
    ): Boolean = synchronized(lock) {
        if (expectedEpoch != _sessionEpoch) return@synchronized false
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
            .apply()
        authStateFlow.value = true
        true
    }

    override suspend fun refresh(staleAccessToken: String?): String? =
        refresher.get().refresh(staleAccessToken)

    override suspend fun clear() {
        synchronized(lock) {
            // Order matters only for *unlocked* readers of sessionEpoch() —
            // i.e. PkceAuthManager.doRefresh, which captures the epoch
            // before its HTTP round-trip without holding [lock]. Bumping
            // before the prefs wipe keeps the invariant "epoch advanced ⇒
            // session ended" monotonic from any observer's vantage: a
            // doRefresh that races with this clear and sees the new epoch
            // is guaranteed to also see (or not yet have read) the wiped
            // refreshToken on its next prefs read.
            //
            // Locked readers (storeIfMatchingEpoch) are unaffected by the
            // intra-block order — they serialise on the same monitor and
            // observe the synchronized block atomically.
            _sessionEpoch += 1
            prefs.edit().clear().apply()
            authStateFlow.value = false
        }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
    }
}
