package io.embodit.sunpatch.ui

import io.embodit.sunpatch.domain.HitRole
import io.embodit.sunpatch.domain.HoursMap
import io.embodit.sunpatch.domain.MarkerKind
import io.embodit.sunpatch.domain.MarkerRec
import io.embodit.sunpatch.domain.MarkerStats
import io.embodit.sunpatch.domain.NextEntry
import io.embodit.sunpatch.domain.Raster
import io.embodit.sunpatch.domain.Room
import io.embodit.sunpatch.domain.SunFrame
import io.embodit.sunpatch.domain.SunProject
import io.embodit.sunpatch.domain.Vec3
import io.embodit.sunpatch.domain.Verdict
import io.embodit.sunpatch.domain.Want
import io.embodit.sunpatch.platform.Brightness
import io.embodit.sunpatch.platform.Lang
import io.embodit.sunpatch.platform.Prefs
import java.time.LocalDate

/** The four TabBar pages of the main window (design `panels.main.tabBar`); settings is always last. */
enum class Page(val id: String) { WINDOW("window"), SUN("sun"), HOURS("hours"), SETTINGS("settings") }

/** S04: the corners marked so far. [redoId] is the window being marked again (窗卡片 [重标]). */
data class Marking(val first: Vec3? = null, val wallId: String? = null, val redoId: String? = null, val reject: Reject? = null, val rejectSeq: Int = 0)

enum class Reject { NOT_WALL, OTHER_WALL, TOO_SMALL }

/** What a head sample (design `virtualRoom.headSample`) is for: the virtual room, or a marker's id. */
sealed interface HeadTarget {
    data object Room : HeadTarget
    data class Marker(val id: String) : HeadTarget
}

/** Inline bars (design S07 modifiers M1, S18, S14). */
enum class BarKind { MAX_WINDOWS, MAX_MARKERS, RESTORED, PARTIAL, HEAD_TIMEOUT }

data class Bar(val kind: BarKind, val a: Int = 0, val b: Int = 0, val untilMs: Long = 0L)

/** The sun right now (readout row). [rise] / [set] are local minutes (alt -0.833 deg), null when the sun never crosses. */
data class Readout(val altDeg: Double, val azDeg: Double, val rise: Double?, val set: Double?)

/** The conclusion row of the Sun page (design `panels.main.pages.sun`). */
sealed interface Conclusion {
    data class Lands(val floorM2: Float, val onUp: Boolean, val onWall: Boolean, val markers: List<String>) : Conclusion
    data class BeforeRise(val rise: Double?) : Conclusion
    data object AfterSet : Conclusion
    data class Behind(val minute: Int, val windowNo: Int, val many: Boolean) : Conclusion
    data object NotToday : Conclusion
    data object NoWindow : Conclusion
    data object NoFacing : Conclusion
}

/** One light computation (worker thread, <= 10 Hz): the frame, the per-surface coverage rasters and the readings. */
class LightResult(
    val key: String,
    val date: LocalDate,
    val minute: Int,
    val frame: SunFrame,
    val rasters: Map<String, Raster>,
    val litArea: Map<String, Float>,
    val readout: Readout,
    val conclusion: Conclusion,
    val next: NextEntry.Result?,
    val ms: Long,
)

/** The day's hours map and every marker's stats (worker thread, design `sunModel.hours`): one source for all readouts. */
class HoursResult(
    val key: String,
    val date: LocalDate,
    val map: HoursMap,
    val area4: Float,
    val cells4: Int,
    val stats: Map<String, MarkerStats>,
    val ms: Long,
)

/** The day sweep in progress (design `sunModel.sweep`). [trail] is the accumulated map so far, [soFar] each marker's hours. */
class SweepView(val running: Boolean, val minute: Int, val done: Int, val total: Int, val trail: HoursMap?, val soFar: Map<String, Float>)

/** Where the ray points this frame, reported by the scene for the reticle and the dump. */
data class Aim(val point: Vec3, val role: HitRole?, val surfaceId: String?)

data class SpUiState(
    val started: Boolean = false,
    val loading: Boolean = true,
    val page: Page = Page.WINDOW,
    val prefs: Prefs = Prefs(),
    val project: SunProject = SunProject(),
    val room: Room? = null,
    val roomVersion: Int = 0,
    val finding: Boolean = true,
    val floorSeen: Boolean = false,
    val wallsSeen: Int = 0,
    val useVirtual: Boolean = false,
    val welcome: Boolean = false,
    val date: LocalDate = LocalDate.of(2026, 6, 15),
    val minute: Int = 12 * 60,
    val isToday: Boolean = true,
    val heat: Boolean = false,
    val sweep: SweepView? = null,
    val selected: String? = null,
    val marking: Marking? = null,
    val placing: Boolean = false,
    val dragging: String? = null,
    val cityOpen: Boolean = false,
    val cityQuery: String = "",
    val coordsOpen: Boolean = false,
    val confirmReset: Boolean = false,
    val confirmExit: Boolean = false,
    val confirmDelete: Boolean = false,
    val bar: Bar? = null,
    val lost: Boolean = false,
    val noPermission: Boolean = false,
    val mismatch: Boolean = false,
    val searching: Boolean = false,
    val restoring: Boolean = false,
    val headSample: HeadTarget? = null,
    val panelBack: Boolean = false,
    val light: LightResult? = null,
    val hours: HoursResult? = null,
    val verdicts: Map<String, Verdict> = emptyMap(),
    val vroomOpen: Boolean = false,
) {
    val windows get() = project.windows
    val markers: List<MarkerRec> get() = project.markers

    fun marker(id: String?): MarkerRec? = markers.firstOrNull { it.id == id }

    val brightness: Brightness get() = prefs.brightness
    val lang: Lang get() = prefs.lang

    /** The design state this is (design `states`), for logs, the dump and QA. */
    val designState: String
        get() = when {
            loading -> "S00"
            confirmExit -> "S15"
            confirmReset -> "S13"
            marking != null -> "S04"
            lost -> "S14"
            dragging != null -> "S11b"
            placing -> "S11"
            page == Page.SETTINGS -> "S12"
            mismatch -> "S17"
            noPermission && page == Page.WINDOW -> "S16"
            bar?.kind == BarKind.RESTORED || bar?.kind == BarKind.PARTIAL -> "S18"
            sweep?.running == true -> "S08"
            selected != null && page == Page.HOURS -> "S10"
            heat -> "S09"
            page == Page.WINDOW -> when {
                welcome -> "S01"
                finding -> "S02"
                windows.isEmpty() && room?.kind == io.embodit.sunpatch.domain.RoomKind.VIRTUAL -> "S03"
                windows.isEmpty() -> "S05"
                !project.northSet -> "S05"
                else -> "S06"
            }
            else -> if (light?.conclusion is Conclusion.Lands) "S07" else "S07b"
        }

    fun stateLine(): String =
        "STATE ${designState} page=${page.id} room=${room?.kind ?: "-"} finding=$finding windows=${windows.size} markers=${markers.size} north=${"%.1f".format(project.northYawDeg)}${if (project.northSet) "" else "(unset)"} " +
            "date=$date minute=$minute today=$isToday heat=$heat sweep=${sweep?.running} selected=$selected marking=${marking != null} placing=$placing lost=$lost"
}

/** Everything the user (or a debug hook, through the same events) can do. */
sealed interface Ev {
    data class Tab(val page: Page) : Ev
    data object WelcomeOk : Ev

    // window page
    data object MarkWindow : Ev
    data class RedoWindow(val id: String) : Ev
    data class DeleteWindow(val id: String) : Ev
    data object StdWindow : Ev
    data object CornerRedo : Ev
    data object CornerCancel : Ev
    data class SetFacing(val deg: Float) : Ev
    data class NudgeFacing(val delta: Float) : Ev
    data object ToggleCity : Ev
    data class CityQuery(val q: String) : Ev
    data class PickCity(val key: String) : Ev
    data object ToggleCoords : Ev
    data class NudgeCoords(val dLat: Double, val dLon: Double) : Ev
    data object GoAlign : Ev
    data object Grant : Ev
    data object LookAgain : Ev

    // sun page
    data class Minute(val minute: Int) : Ev
    data class Month(val month: Int) : Ev
    data object Today : Ev
    data object Now : Ev
    data class Follow(val on: Boolean) : Ev
    data class SweepStart(val holdMs: Long? = null) : Ev
    data object SweepStop : Ev

    // hours page
    data class Heat(val on: Boolean) : Ev
    data object PlaceMarker : Ev
    data object PlaceCancel : Ev
    data class SelectMarker(val id: String?) : Ev
    data class MarkerKindSet(val id: String, val kind: MarkerKind) : Ev
    data class MarkerWant(val id: String, val want: Want) : Ev
    data class MarkerFront(val id: String) : Ev
    data class MarkerDeleteAsk(val id: String) : Ev
    data object MarkerDeleteCancel : Ev
    data class MarkerDelete(val id: String) : Ev

    // settings
    data class SetLang(val lang: Lang) : Ev
    data class SetBrightness(val b: Brightness) : Ev
    data class SetSound(val on: Boolean) : Ev
    data object Recall : Ev
    data object UseVirtual : Ev
    data object BackToScan : Ev
    data class RoomSize(val dw: Float, val dd: Float, val dh: Float) : Ev
    data class RoomTurn(val deg: Float) : Ev
    data object RoomRecenter : Ev
    data object ResetAsk : Ev
    data object ResetCancel : Ev
    data object ResetYes : Ev
    data object QuitAsk : Ev
    data object QuitCancel : Ev
    data object QuitYes : Ev

    data class Background(val on: Boolean) : Ev
}
