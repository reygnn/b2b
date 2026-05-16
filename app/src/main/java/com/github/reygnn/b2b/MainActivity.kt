package com.github.reygnn.b2b

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.b2b.data.auth.PkceAuthManager
import com.github.reygnn.b2b.ui.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pkceAuthManager: PkceAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { AppNavHost() }
            }
        }
        // Process the callback only on fresh launch; rotations get a non-null
        // savedInstanceState and the auth code (single-use) has already been
        // consumed by the previous onCreate.
        if (savedInstanceState == null) {
            handleAuthRedirect(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != AUTH_CALLBACK_SCHEME || data.host != AUTH_CALLBACK_HOST) return
        // Spotify sends `?error=access_denied` when the user rejects on the
        // consent screen. Surface to UI later; for now we just drop the redirect.
        if (data.getQueryParameter("error") != null) return
        val code = data.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            pkceAuthManager.exchangeAuthorizationCode(code)
        }
    }

    private companion object {
        const val AUTH_CALLBACK_SCHEME = "b2b"
        const val AUTH_CALLBACK_HOST = "callback"
    }
}
