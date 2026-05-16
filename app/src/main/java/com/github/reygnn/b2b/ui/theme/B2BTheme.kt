package com.github.reygnn.b2b.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App-wide Compose theme. Follows the system dark/light setting and uses
 * Material You dynamic colors (always available — minSdk = 36).
 *
 * The previous setup wrapped the navigation host in a bare `MaterialTheme { }`
 * which silently fell back to the Compose default light color scheme,
 * ignoring the system's night-mode setting and rendering the same colors
 * regardless of the OS theme. The XML `<style name="Theme.B2B">` resources
 * in `values/` and `values-night/` don't reach Compose — those control only
 * the brief pre-Compose window backdrop on Activity launch.
 */
@Composable
fun B2BTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
