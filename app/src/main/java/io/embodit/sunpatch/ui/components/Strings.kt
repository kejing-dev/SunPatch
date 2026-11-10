package io.embodit.sunpatch.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.sunpatch.platform.Lang
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / English), switched live: the screens provide a
 * Resources object for that locale and every panel reads its strings from it; the 3D plates use the same resources.
 */
val LocalSpResources = staticCompositionLocalOf<Resources> { error("LocalSpResources not provided") }

fun localizedResources(context: Context, lang: Lang): Resources {
    val tag =
        when (lang) {
            Lang.SYSTEM -> return context.resources
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalSpResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalSpResources.current.getString(id, *args)

/** True when the copy shown is Chinese (city / kind / direction names come from the domain in both languages). */
@Composable
@ReadOnlyComposable
fun zhUi(): Boolean = LocalSpResources.current.configuration.locales.get(0)?.language == "zh"

/** Sizes, dp (design `panels`): one Planar window 560 x 480; the Stage plate 360 x 160 at 0.8 mm / dp. */
object PanelSizes {
    const val WINDOW_W = 560f
    const val WINDOW_H = 480f
    const val PLATE_W = 360f
    const val PLATE_H = 160f
}
