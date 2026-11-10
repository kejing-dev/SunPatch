package io.embodit.sunpatch.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.sunpatch.domain.Cities
import io.embodit.sunpatch.domain.MarkerKind
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.domain.Want
import io.embodit.sunpatch.platform.Lang
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.Ev
import io.embodit.sunpatch.ui.Page
import io.embodit.sunpatch.ui.SpController
import io.embodit.sunpatch.ui.components.TextFit
import io.embodit.sunpatch.ui.debug.Probe
import io.embodit.sunpatch.ui.scene.SunScene
import io.embodit.sunpatch.ui.window.WindowCtl
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Debug builds only (design `verification.debugHooks`). Every command goes through the same events as the panels
 * ([SpController.onEvent]) or feeds the same inputs as the hardware (a stand-in controller ray + the tap a trigger would
 * deliver, the head pitch / yaw rate the gate and the sampler read, a fixed "now"): no command writes app state directly
 * (factory rule 2026-10-29). Usage and the screenshot list: AGENTS.md.
 *
 *   adb shell "am broadcast -a io.embodit.sunpatch.DEBUG -p io.embodit.sunpatch --es cmd 'time 12:00'"
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private fun ev(e: Ev): String {
        SpController.onEvent(e)
        return SpController.state.value.stateLine()
    }

    private fun scene(): SunScene? = Probe.host?.scene()

    private fun floats(s: String?): List<Float> = (s ?: "").split(',', ' ').filter { it.isNotBlank() }.mapNotNull { it.trim().toFloatOrNull() }

    private fun later(ms: Long, f: () -> Unit) = main.postDelayed({ runCatching(f).onFailure { SpLog.w("CMD later failed: $it") } }, ms)

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        appContext = context
        val arg = (s ?: a ?: "").trim()
        val out: String = when (cmd) {
            "goto", "state" -> goto(arg.uppercase(Locale.US))
            "room" -> room(arg)
            "ray" -> {
                val v = floats(arg)
                if (v.size < 3) "usage: ray x,y,z[,ms]" else scene()?.debugRay(Vec3(v[0], v[1], v[2]), (v.getOrNull(3) ?: 60000f).toLong()) ?: "no scene"
            }
            "trigger" -> scene()?.debugTrigger() ?: "no scene"
            "corner" -> {
                val v = floats(arg)
                if (v.size < 3) "usage: corner x,y,z" else {
                    val sc = scene() ?: throw IllegalArgumentException("no scene")
                    sc.debugRay(Vec3(v[0], v[1], v[2]), 60000L)
                    sc.debugTrigger()
                }
            }
            "stdwindow" -> stdWindow(arg.ifEmpty { "front" })
            "facing" -> ev(Ev.SetFacing(arg.toFloatOrNull() ?: throw IllegalArgumentException("usage: facing deg")))
            "city" -> {
                val c = Cities.byKey(arg) ?: Cities.search(arg).firstOrNull()
                if (c == null) "unknown city $arg" else ev(Ev.PickCity(c.key))
            }
            "time" -> {
                val p = arg.split(':')
                val m = (p.getOrNull(0)?.toIntOrNull() ?: throw IllegalArgumentException("usage: time hh:mm")) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
                ev(Ev.Minute(m))
            }
            "month" -> ev(Ev.Month(arg.toIntOrNull() ?: throw IllegalArgumentException("usage: month m")))
            "today" -> ev(Ev.Today)
            "now" -> ev(Ev.Now)
            "clock" -> clock(arg)
            "follow" -> ev(Ev.Follow(arg == "on"))
            "sweep" -> sweep(arg)
            "heat" -> ev(Ev.Heat(arg != "off"))
            "marker" -> marker(arg)
            "drag" -> drag(arg)
            "release" -> scene()?.debugRelease() ?: "no scene"
            "lang" -> ev(Ev.SetLang(when (arg) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.SYSTEM }))
            "lost" -> {
                scene()?.head?.forceLost = arg != "off"
                "lost=${arg != "off"}"
            }
            "mismatch" -> {
                SpController.debugMismatch()
                "mismatch: anchors hidden, restore re-run"
            }
            "noPermission", "nopermission" -> {
                SpController.debugNoPermission(arg != "off")
                "noPermission=${arg != "off"}"
            }
            "anchor" -> {
                SpController.anchors.hideStored = arg.startsWith("fail")
                "anchors hidden on next restore=${SpController.anchors.hideStored}"
            }
            "pitch" -> {
                val v = arg.toFloatOrNull()
                scene()?.debugPitch = if (arg == "off" || arg.isEmpty()) null else v
                "pitch override=${scene()?.debugPitch}"
            }
            "yawRate", "yawrate" -> {
                scene()?.debugYawRate = if (arg == "off" || arg.isEmpty()) null else arg.toFloatOrNull()
                "yawRate override=${scene()?.debugYawRate}"
            }
            "bar" -> {
                SpController.barLong = arg == "long"
                "barLong=${SpController.barLong}"
            }
            "tab", "page" -> ev(Ev.Tab(Page.values().firstOrNull { it.id == arg } ?: Page.SETTINGS))
            "recall" -> ev(Ev.Recall)
            "fit" -> {
                if (arg == "reset") TextFit.reset()
                TextFit.summary()
            }
            "where" -> scene()?.where() ?: "no scene"
            "dump" -> dump()
            else -> "unknown command $cmd"
        }
        log(cmd, out)
    }

    private fun log(cmd: String, out: String): String {
        SpLog.i("CMD $cmd -> $out")
        return out
    }

    // ------------------------------------------------------------------------------------------------ goto Sxx

    private fun ensureRoom() {
        val s = SpController.state.value
        if (s.welcome) SpController.onEvent(Ev.WelcomeOk)
        if (s.room == null || s.finding) SpController.onEvent(Ev.UseVirtual)
    }

    private fun ensureWindow() {
        ensureRoom()
        if (SpController.state.value.windows.isEmpty()) stdWindow("front")
    }

    private fun ensureFacing() {
        ensureWindow()
        val s = SpController.state.value
        if (!s.project.northSet) SpController.onEvent(Ev.SetFacing(180f))
    }

    private fun ensureMarker() {
        ensureFacing()
        val s = SpController.state.value
        if (s.markers.isNotEmpty()) return
        val room = s.room ?: return
        val w = s.windows.firstOrNull() ?: return
        val p = w.center - w.outward * 1.2f
        placeMarkerAt(Vec3(p.x, room.floorY, p.z))
    }

    private fun goto(id: String): String {
        when (id) {
            "S00" -> return "S00 is the cold start (launch and capture within 0.5 s)"
            "S01" -> SpController.debugFirstRun()
            "S02" -> {
                ensureRoom()
                SpController.onEvent(Ev.Tab(Page.WINDOW))
                SpController.onEvent(Ev.BackToScan)
            }
            "S03" -> {
                ensureRoom()
                SpController.onEvent(Ev.UseVirtual)
                SpController.onEvent(Ev.Tab(Page.WINDOW))
            }
            "S04" -> {
                ensureRoom()
                SpController.onEvent(Ev.Tab(Page.WINDOW))
                SpController.onEvent(Ev.MarkWindow)
            }
            "S05" -> {
                ensureWindow()
                SpController.onEvent(Ev.Tab(Page.WINDOW))
            }
            "S06" -> {
                ensureFacing()
                SpController.onEvent(Ev.Tab(Page.WINDOW))
            }
            "S07" -> {
                ensureFacing()
                SpController.onEvent(Ev.Tab(Page.SUN))
                SpController.onEvent(Ev.Month(6))
                SpController.onEvent(Ev.Minute(12 * 60))
            }
            "S07B" -> {
                ensureFacing()
                SpController.onEvent(Ev.Tab(Page.SUN))
                SpController.onEvent(Ev.Minute(3 * 60))
            }
            "S08" -> {
                ensureFacing()
                SpController.onEvent(Ev.Tab(Page.SUN))
                SpController.onEvent(Ev.SweepStart())
            }
            "S09" -> {
                ensureFacing()
                SpController.onEvent(Ev.Tab(Page.HOURS))
                SpController.onEvent(Ev.SelectMarker(null))
                SpController.onEvent(Ev.Heat(true))
            }
            "S10" -> {
                ensureMarker()
                later(300) { SpController.state.value.markers.firstOrNull()?.let { SpController.onEvent(Ev.SelectMarker(it.id)) } }
            }
            "S11" -> {
                ensureFacing()
                SpController.onEvent(Ev.PlaceMarker)
            }
            "S11B" -> {
                ensureMarker()
                later(300) { SpController.state.value.markers.firstOrNull()?.let { scene()?.debugDrag(it.id, 0.3f, 0f, true) } }
            }
            "S12" -> SpController.onEvent(Ev.Tab(Page.SETTINGS))
            "S13" -> {
                SpController.onEvent(Ev.Tab(Page.SETTINGS))
                SpController.onEvent(Ev.ResetAsk)
            }
            "S14" -> scene()?.head?.forceLost = true
            "S15" -> {
                SpController.onEvent(Ev.Tab(Page.SETTINGS))
                SpController.onEvent(Ev.QuitAsk)
            }
            "S16" -> SpController.debugNoPermission(true)
            "S17" -> SpController.debugMismatch()
            "S18" -> SpController.debugRestore()
            "S19" -> if (scene()?.debugPitch != null) WindowCtl.recall("goto S19") else WindowCtl.closeAsUser()
            else -> return "unknown state $id"
        }
        return "goto $id -> " + SpController.state.value.stateLine()
    }

    // ------------------------------------------------------------------------------------------------ room / windows

    private fun room(arg: String): String {
        val parts = arg.split(Regex("\\s+"), limit = 2)
        return when (parts[0]) {
            "virtual" -> ev(Ev.UseVirtual)
            "scan" -> ev(Ev.BackToScan)
            "size" -> {
                val v = floats(parts.getOrNull(1))
                if (v.size < 3) return "usage: room size w,d,h"
                val p = SpController.state.value.prefs
                ev(Ev.RoomSize(v[0] - p.roomW, v[1] - p.roomD, v[2] - p.roomH))
            }
            "yaw" -> ev(Ev.RoomTurn(parts.getOrNull(1)?.toFloatOrNull() ?: 5f))
            "recenter" -> ev(Ev.RoomRecenter)
            else -> "usage: room virtual|scan|size w,d,h|yaw deg|recenter"
        }
    }

    /** Aims the stand-in ray at the middle of that wall (1.5 m up), then presses [放一扇标准窗] (design T3: the wall you point at). */
    private fun stdWindow(which: String): String {
        ensureRoom()
        val sc = scene() ?: return "no scene"
        val room = SpController.state.value.room ?: return "no room yet"
        val wall = SpController.wallFor(which) ?: return "no wall $which"
        val c = wall.polygon.fold(Vec3.ZERO) { acc, v -> acc + v } / wall.polygon.size.toFloat()
        sc.debugRay(Vec3(c.x, room.floorY + 1.5f, c.z), 3000L)
        return ev(Ev.StdWindow)
    }

    // ------------------------------------------------------------------------------------------------ time

    private fun clock(arg: String): String {
        if (arg.isEmpty() || arg == "off") {
            SpController.debugNow = null
            return "clock real"
        }
        val utc = arg.endsWith("Z")
        val t = LocalDateTime.parse(arg.removeSuffix("Z"))
        val zone = if (utc) ZoneId.of("UTC") else ZoneId.of(SpController.state.value.project.city.zoneId)
        val i: Instant = t.atZone(zone).toInstant()
        SpController.debugNow = i
        // what [今天] does with that clock (design T6)
        SpController.onEvent(Ev.Today)
        return "clock now=$i (${t.atZone(zone)})"
    }

    private fun sweep(arg: String): String {
        if (arg == "stop") return ev(Ev.SweepStop)
        val hold = Regex("hold\\s+([0-9.]+)").find(arg)?.groupValues?.get(1)?.toFloatOrNull()
        return ev(Ev.SweepStart(hold?.let { (it * 1000).toLong() }))
    }

    // ------------------------------------------------------------------------------------------------ markers

    /** [放一个标记], aim the stand-in ray at the point from above the head, then the trigger: the user's path. */
    private fun placeMarkerAt(p: Vec3): String {
        val sc = scene() ?: return "no scene"
        SpController.onEvent(Ev.PlaceMarker)
        if (!SpController.state.value.placing) return "not placing (max?)"
        sc.debugRay(p, 3000L)
        return sc.debugTrigger()
    }

    private fun markerByNo(n: String): io.embodit.sunpatch.domain.MarkerRec? {
        val no = n.toIntOrNull() ?: return null
        return SpController.state.value.markers.firstOrNull { it.no == no }
    }

    private fun marker(arg: String): String {
        val p = arg.split(Regex("\\s+"))
        if (p.firstOrNull() == "add") {
            val xz = floats(p.getOrNull(1))
            if (xz.size < 2) return "usage: marker add x,z [kind]"
            val room = SpController.state.value.room ?: return "no room"
            val probe = Vec3(xz[0], room.floorY + 3f, xz[1])
            val surf = room.surfaceUnder(probe, room.floorY + 3f)
            val r = placeMarkerAt(Vec3(xz[0], surf?.height ?: room.floorY, xz[1]))
            p.getOrNull(2)?.let { k -> MarkerKind.values().firstOrNull { it.name.equals(k, true) || it.en.equals(k, true) }?.let { kind -> SpController.state.value.selected?.let { SpController.onEvent(Ev.MarkerKindSet(it, kind)) } } }
            return r
        }
        val m = markerByNo(p.getOrNull(0) ?: "") ?: return "no marker ${p.getOrNull(0)}"
        return when (p.getOrNull(1)) {
            "select" -> ev(Ev.SelectMarker(m.id))
            "want" -> ev(Ev.MarkerWant(m.id, when (p.getOrNull(2)) { "more" -> Want.MORE; "less" -> Want.LESS; else -> Want.LOOK }))
            "kind" -> ev(Ev.MarkerKindSet(m.id, MarkerKind.values().firstOrNull { it.name.equals(p.getOrNull(2), true) || it.en.equals(p.getOrNull(2), true) } ?: MarkerKind.OTHER))
            "delete" -> {
                SpController.onEvent(Ev.MarkerDeleteAsk(m.id))
                ev(Ev.MarkerDelete(m.id))
            }
            "front" -> ev(Ev.MarkerFront(m.id))
            else -> "usage: marker n select|want more/less/look|kind k|delete|front"
        }
    }

    private fun drag(arg: String): String {
        val p = arg.split(Regex("\\s+"))
        val m = markerByNo(p.getOrNull(0) ?: "") ?: return "no marker"
        val dx = p.getOrNull(1)?.toFloatOrNull() ?: 0f
        val dz = p.getOrNull(2)?.toFloatOrNull() ?: 0f
        return scene()?.debugDrag(m.id, dx, dz, p.getOrNull(3) == "hold") ?: "no scene"
    }

    // ------------------------------------------------------------------------------------------------ dump

    private fun dump(): String {
        val lines = SpController.dumpLines() + listOf("DUMP scene ${scene()?.where()}", "DUMP fit ${TextFit.summary()}")
        lines.forEach { SpLog.i(it) }
        appContext?.let { c -> runCatching { File(c.filesDir, "dump.txt").writeText(lines.joinToString("\n") + "\n") } }
        return "dump ${lines.size} lines (files/dump.txt)"
    }
}
