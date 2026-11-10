package io.embodit.sunpatch.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.sunpatch.R
import io.embodit.sunpatch.domain.LevelGate
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.Ev
import io.embodit.sunpatch.ui.Page
import io.embodit.sunpatch.ui.SpController
import io.embodit.sunpatch.ui.components.HoursPage
import io.embodit.sunpatch.ui.components.LocalSpResources
import io.embodit.sunpatch.ui.components.PanelSizes
import io.embodit.sunpatch.ui.components.SettingsPage
import io.embodit.sunpatch.ui.components.SunPage
import io.embodit.sunpatch.ui.components.SunToolbar
import io.embodit.sunpatch.ui.components.WindowPage
import io.embodit.sunpatch.ui.components.localizedResources
import io.embodit.sunpatch.ui.components.rs
import io.embodit.sunpatch.ui.debug.LocalProbePanel
import io.embodit.sunpatch.ui.theme.SpTheme

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "ControlPanel"
const val PANEL_TAG = "main"

/**
 * The main window's state shared by the Stage and the window (one process, native-window-move §3.4): the window reports
 * its lifecycle, the Stage opens / recalls it with its navigator. Every open goes through the [LevelGate] (D9, §7.9).
 * Main thread only.
 */
object WindowCtl {
    /** Last lifecycle event of the window ("ON_CREATE" … "ON_DESTROY"), "never" before the first open. */
    var panelState by mutableStateOf("never")
        private set

    var createdEpoch by mutableIntStateOf(0)
        private set

    /** Metres per window dp, from the window's LocalPhysicalLengthConverter. */
    @Volatile var dpM = 0.0008f

    /** The Stage's navigator while the Stage is composed. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private var openAfterDestroy = false
    private var hiddenForMarking = false
    private var askedAtMs = 0L
    private val gate = LevelGate()
    private var lastStepMs = 0L

    /** The Stage's "平视前方 / Look level ahead" hint: an open waits and the head is not level. */
    var levelHint by mutableStateOf(false)
        private set

    /** q1-3: an open waits because it would cover a marked window; +1 = turn left, -1 = turn right, 0 = no wait. */
    var asideTurn by mutableIntStateOf(0)
        private set

    /** Head position and yaw when the window last opened (it opens on that line of sight); null when not known. */
    var openedHead: io.embodit.sunpatch.domain.Vec3? = null
        private set
    var openedYaw: Float? = null
        private set
    private var stepHead: io.embodit.sunpatch.domain.Vec3? = null
    private var stepYaw: Float? = null

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    val waiting: Boolean
        get() = gate.waiting

    /** The Stage shows "打开面板" while the window is closed / minimised by the user, or never came up within 8 s. */
    fun chipWanted(now: Long): Boolean =
        if (gate.waiting || hiddenForMarking || openAfterDestroy || recallPending) false
        else when (panelState) {
            "ON_DESTROY", "ON_STOP" -> true
            "never" -> askedAtMs > 0 && now - askedAtMs > 8000
            else -> false
        }

    /** Ask for the window: it opens once the head is level (or without Stage frames, [watchdog]). */
    fun openWindow(why: String) {
        val now = SystemClock.elapsedRealtime()
        SpLog.i("WINDOW ask why=$why state=$panelState (opens when the head is level)")
        gate.ask(why, now)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * One Stage frame: [pitchDeg] = head pitch (+ up), null while the head pose is not valid yet; [turnDeg] = the yaw turn
     * after which a window opened on the line of sight covers no marked window ([PanelCone.turnFor]: 0 = none needed,
     * null = no turn helps); [head] / [yawDeg] = the head the window would open in front of.
     */
    fun stepGate(now: Long, pitchDeg: Float?, turnDeg: Float? = 0f, head: io.embodit.sunpatch.domain.Vec3? = null, yawDeg: Float? = null) {
        lastStepMs = now
        stepHead = head
        stepYaw = yawDeg
        if (!gate.waiting) {
            if (levelHint) levelHint = false
            if (asideTurn != 0) asideTurn = 0
            return
        }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) {
            levelHint = hint
            SpLog.i(String.format(java.util.Locale.US, "GATE hint=%s pitch=%.1f waited=%d ms", hint, pitchDeg ?: Float.NaN, gate.waitedMs(now)))
        }
        val turn = if (pitchDeg == null || turnDeg == null) 0f else turnDeg
        val side = if (hint || turn == 0f) 0 else if (turn > 0f) 1 else -1
        if (side != asideTurn) {
            asideTurn = side
            SpLog.i(String.format(java.util.Locale.US, "GATE aside turn=%.0f side=%d (window behind the panel)", turn, side))
        }
        val why = gate.step(now, pitchDeg, aside = turn == 0f) ?: return
        levelHint = false
        asideTurn = 0
        SpLog.i(String.format(java.util.Locale.US, "GATE level ok pitch=%.1f yaw=%.1f -> open", pitchDeg ?: Float.NaN, yawDeg ?: Float.NaN))
        openNow(why)
    }

    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > FIRST_FRAME_MS else now - lastStepMs > WATCHDOG_MS
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    openNow("$why/noframes")
                } else main.postDelayed(this, WATCHDOG_MS)
            }
        }

    private fun openNow(why: String) {
        val nav = navigator ?: return SpLog.w("WINDOW open why=$why: no navigator")
        askedAtMs = SystemClock.elapsedRealtime()
        openedHead = if (why.endsWith("/nohead") || why.endsWith("/noframes")) null else stepHead
        openedYaw = if (openedHead == null) null else stepYaw
        SpLog.i("WINDOW open why=$why state=$panelState yaw=${openedYaw?.let { "%.1f".format(java.util.Locale.US, it) }}")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { SpLog.w("WINDOW open failed: $it") }
    }

    /** "打开面板": a minimised window is restored where it was, a closed one opens in front through the gate. */
    fun reopen(why: String) {
        if (panelState == "ON_STOP" && navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
            SpLog.i("WINDOW restore why=$why")
            return
        }
        openWindow(why)
    }

    /** "叫回面前": close, then open through the gate after ON_DESTROY (an open right after close is swallowed, §3.5). */
    fun recall(why: String) {
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        SpLog.i("WINDOW recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { SpLog.w("WINDOW close failed: $it"); recallPending = false }
    }

    /** S04: the panel may cover the wall to mark, so it closes while the corners are marked (design `container.notes`). */
    fun hideForMarking() {
        hiddenForMarking = true
        recallPending = false
        gate.cancel()
        levelHint = false
        asideTurn = 0
        if (panelState == "never" || panelState == "ON_DESTROY") return
        SpLog.i("WINDOW close for marking")
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { SpLog.w("WINDOW close failed: $it") }
    }

    /** S04 done / cancelled: open again through the gate once the window reported ON_DESTROY. */
    fun showAfterMarking() {
        hiddenForMarking = false
        if (panelState == "never" || panelState == "ON_DESTROY") openWindow("marked")
        else openAfterDestroy = true
    }

    /** Debug `goto S19` without a pitch: close the window as the user's × would. */
    fun closeAsUser() {
        SpLog.i("WINDOW close (as the user's ×)")
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }

    fun onLifecycle(event: String) {
        panelState = event
        SpLog.i("WINDOW lifecycle $event")
        if (event == "ON_CREATE") {
            createdEpoch++
            SpController.onPanelCreated()
        }
        if (event == "ON_DESTROY" && (recallPending || openAfterDestroy)) {
            val why = if (recallPending) "recall" else "marked"
            recallPending = false
            openAfterDestroy = false
            main.postDelayed({ openWindow(why) }, 300)
        }
    }

    /** Quit: close the window first (it would otherwise outlive the Stage, §7.7). */
    fun closeForQuit() {
        recallPending = false
        openAfterDestroy = false
        gate.cancel()
        levelHint = false
        asideTurn = 0
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }

    private const val WATCHDOG_MS = 3000L
    private const val FIRST_FRAME_MS = 15000L
}

/**
 * The content of the main window (design `windowModel` single_system_window): pages 窗 / 看光 / 日照图, then 设置 with
 * 退出应用 last. The Sun page carries the system Toolbar ([现在] [▶ 扫一天 / ■ 停]).
 */
@Composable
fun SpWindow() {
    val context = LocalContext.current
    val state by SpController.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        runCatching { converter.dpToLength(1000.dp, LengthUnit.Meters) / 1000f }.getOrNull()?.takeIf { it in 0.0002f..0.005f }?.let {
            WindowCtl.dpM = it
            SpLog.i(String.format(java.util.Locale.US, "WINDOW dp=%.5f m (%.2f x %.2f m)", it, PanelSizes.WINDOW_W * it, PanelSizes.WINDOW_H * it))
        }
    }
    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    val onEvent: (Ev) -> Unit = SpController::onEvent
    CompositionLocalProvider(LocalSpResources provides resources, LocalProbePanel provides "window") {
        SpTheme {
            MainWindow(
                pages =
                    listOf(
                        MainWindowPage(Page.WINDOW.id, rs(R.string.tab_window), R.drawable.ic_window) { WindowPage(state, onEvent) },
                        MainWindowPage(Page.SUN.id, rs(R.string.tab_sun), R.drawable.ic_sunny, toolbar = { SunToolbar(state, onEvent) }) { SunPage(state, onEvent) },
                        MainWindowPage(Page.HOURS.id, rs(R.string.tab_hours), R.drawable.ic_grid_on) { HoursPage(state, onEvent) },
                    ),
                settings = MainWindowSettings(rs(R.string.tab_settings), R.drawable.ic_settings) { SettingsPage(state, onEvent) },
                current = if (state.page == Page.SETTINGS) SETTINGS_PAGE else state.page.id,
                onSelect = { id -> onEvent(Ev.Tab(Page.values().firstOrNull { it.id == id } ?: Page.SETTINGS)) },
            )
        }
    }
}
