package io.embodit.sunpatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * SpatialUI vibrant scheme with the design's four roles (palette.implementation): all 16 public roles written out,
 * interaction / passable / alert / error from the design, the other 12 = the system's own. Noto Sans / Noto Sans SC.
 */
@Composable
fun SpTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimary = system.labelPrimary,
                labelPrimaryLight = system.labelPrimaryLight,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                dividerLine = system.dividerLine,
                interaction = SpColors.Interaction,
                passable = SpColors.Passable,
                alert = SpColors.Alert,
                error = SpColors.Error,
            )
        }
    val family = rememberSpFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
