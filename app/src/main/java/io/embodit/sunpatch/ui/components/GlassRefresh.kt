package io.embodit.sunpatch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.debug.LocalProbePanel
import io.embodit.sunpatch.ui.debug.Probe
import kotlinx.coroutines.delay

/**
 * Keeps a glass panel's backdrop fresh (ported from Qianliyan, QA round 2 q2-1).
 *
 * `backgroundMaterial` is a property of the panel's graphics layer: the backdrop is composited into the
 * panel's frame when that layer is drawn. On the emulator a frame drawn before the backdrop is ready comes
 * up black over the region that frame repainted (a static panel shows a black band),
 * and a static panel never draws again, so the black stayed until the card was hidden. The call strip
 * recovered from the same black frame within a second or two because its live dot redraws all the time.
 *
 * Put this right after `backgroundMaterial(...)`: it re-records the material layer a few times after the panel is
 * composed (0.7, 1.5, 3, 6, 10 s) and then stops. Drone Sight: a refresh every 500 ms on two panels kept the
 * 1-fps emulator's render thread busy enough for the runtime watchdog to kill it (build.log 2026-10-31).
 */
@Composable
internal fun glassRefresh(): Modifier {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L
        for (at in REFRESH_AT_MS) {
            delay(at - last)
            last = at
            tick.intValue++
        }
    }
    val panel = LocalProbePanel.current
    val draws = remember { IntArray(2) } // draws so far, tick of the last log line
    // reading the tick in the draw phase invalidates the layer's drawing, not the composition
    return Modifier.drawBehind {
        val n = tick.intValue
        draws[0]++
        if (Probe.enabled && (draws[0] == 1 || n - draws[1] >= LOG_EVERY)) {
            draws[1] = n
            SpLog.i("GLASS redraw panel=$panel tick=$n draws=${draws[0]}")
        }
    }
}

private const val LOG_EVERY = 1

private val REFRESH_AT_MS = longArrayOf(700L, 1_500L, 3_000L, 6_000L, 10_000L)
