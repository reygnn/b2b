package com.github.reygnn.b2b

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.b2b.data.auth.AuthEvent
import com.github.reygnn.b2b.data.auth.AuthEventBus
import com.github.reygnn.b2b.data.auth.PkceAuthManager
import com.github.reygnn.b2b.ui.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pkceAuthManager: PkceAuthManager
    @Inject lateinit var authEvents: AuthEventBus

    // Runtime permission for the foreground-service notification. minSdk = 36
    // so this is always a runtime concern; without granting it,
    // startForeground() succeeds but the notification is hidden, leaving
    // the user with no visible signal that the service is alive.
    private val postNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice doesn't gate anything else; the service still runs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { AppNavHost() }
            }
        }
        maybeRequestNotificationPermission()
        // Process the callback only on fresh launch; rotations get a non-null
        // savedInstanceState and the auth code (single-use) has already been
        // consumed by the previous onCreate.
        if (savedInstanceState == null) {
            handleAuthRedirect(intent)
        }
    }

    private fun maybeRequestNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) postNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != AUTH_CALLBACK_SCHEME || data.host != AUTH_CALLBACK_HOST) return
        val error = data.getQueryParameter("error")
        if (error != null) {
            lifecycleScope.launch { authEvents.emit(AuthEvent.LoginFailed(error)) }
            return
        }
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
