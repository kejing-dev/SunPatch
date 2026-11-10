package io.embodit.sunpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.sunpatch.R
import io.embodit.sunpatch.domain.Cities
import io.embodit.sunpatch.domain.Dirs
import io.embodit.sunpatch.domain.MarkerKind
import io.embodit.sunpatch.domain.MarkerRec
import io.embodit.sunpatch.domain.RoomKind
import io.embodit.sunpatch.domain.Solar
import io.embodit.sunpatch.domain.Verdict
import io.embodit.sunpatch.domain.Verdicts
import io.embodit.sunpatch.domain.VirtualRoom
import io.embodit.sunpatch.domain.Want
import io.embodit.sunpatch.platform.Brightness
import io.embodit.sunpatch.platform.Lang
import io.embodit.sunpatch.ui.BarKind
import io.embodit.sunpatch.ui.Conclusion
import io.embodit.sunpatch.ui.Ev
import io.embodit.sunpatch.ui.Reject
import io.embodit.sunpatch.ui.SpUiState
import io.embodit.sunpatch.ui.debug.probe
import io.embodit.sunpatch.ui.scene.PlateMode
import io.embodit.sunpatch.ui.theme.SpColors
import io.embodit.sunpatch.ui.theme.SpType
import io.embodit.sunpatch.ui.window.WindowCtl
import java.util.Locale
import kotlin.math.roundToInt

// ================================================================================================= building blocks

enum class KeyKind { PRIMARY, NORMAL, DANGER }

/** True inside a dark surface (the inline bar, the Stage plate): plain keys turn light there (QA #1 q1-1). */
internal val LocalOnDark = staticCompositionLocalOf { false }

/**
 * A text key (>= 48 dp). Primary = interaction amber + ink; danger = error coral + ink; normal = an opaque slate key with
 * white ink on the glass, a light key with slate ink on a dark bar (QA #1 q1-1 / q1-2: never the system's translucent
 * default under labelPrimary).
 */
@Composable
internal fun Key(text: String, name: String, kind: KeyKind = KeyKind.NORMAL, icon: Int? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val onDark = LocalOnDark.current
    val (fill, ink) = when (kind) {
        KeyKind.PRIMARY -> SpColors.Interaction to SpColors.OnFill
        KeyKind.DANGER -> SpColors.DangerKey to SpColors.OnFill
        KeyKind.NORMAL -> if (onDark) SpColors.KeyFillLight to SpColors.OnFill else SpColors.KeyFill to SpColors.KeyInk
    }
    val colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink)
    Button(
        onClick = onClick,
        modifier = modifier.probe(name).height(48.dp),
        enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 48.dp),
        shape = CircleShape,
        colors = colors,
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, maxLines = 1, color = ink, style = SpType.button, overflow = TextOverflow.Ellipsis, onTextLayout = fits("key/$name"))
    }
}

@Composable
internal fun IconKey(icon: Int, description: String, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        size = IconButtonDefaults.iconButtonSize(width = 52.dp, height = 48.dp),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = SpColors.OnFill,
        selectedItemBackgroundColor = SpColors.Interaction,
        selectedItemContentColor = SpColors.OnFill,
    )

/**
 * Options inside a page (D8: never to switch pages; always called inside a labelled Row). Every item takes the widest
 * label + 24 dp, at least [itemWidth].
 */
@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T?, name: String, itemWidth: Int, enabled: Boolean = true, onPick: (T) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = SpType.label
    val w = remember(options.map { it.second }, itemWidth, style, density) {
        options.maxOf { (_, label) -> maxOf(itemWidth.dp, with(density) { (measurer.measure(label, style, maxLines = 1).size.width * 1.1f).toDp() } + 24.dp) }
    }
    // gate:allow tabs-segment in-page option wrapper, only ever called inside a labelled Row (never to switch pages)
    // QA #1 q1-2: an opaque light track, so unselected labels do not depend on what the glass picks up from the room
    SegmentControl(modifier = Modifier.width(w * options.size + 8.dp).height(48.dp), backgroundColor = SpColors.KeyFillLight) {
        options.forEach { (v, label) ->
            val sel = v == selected
            SegmentItem(
                selected = sel,
                onClick = { if (enabled) onPick(v) },
                modifier = Modifier.width(w).height(40.dp).probe("${name}_$v"),
                textStyle = SpType.label,
                colors = segColors(),
                title = { Text(text = label, maxLines = 1, color = SpColors.OnFill.copy(alpha = if (sel || enabled) 1f else 0.45f), style = SpType.label, onTextLayout = fits("seg/${name}_$v")) },
            )
        }
    }
}

@Composable
internal fun Line(text: String, name: String, style: androidx.compose.ui.text.TextStyle = SpType.bodySmall, lines: Int = 1, color: Color = PicoTheme.colorScheme.labelPrimary, modifier: Modifier = Modifier) {
    Text(text = text, style = style, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = modifier.probe(name), onTextLayout = fits("text/$name"))
}

/** An opaque inline bar (design M1): readable on any glass. [ok] = a passable-green bar (QA #1 q1-2: no coloured text on glass). */
@Composable
internal fun InlineBar(text: String, name: String, warn: Boolean = false, ok: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val light = warn || ok
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(if (warn) SpColors.Alert else if (ok) SpColors.Passable else SpColors.BarBg).padding(horizontal = 14.dp, vertical = 6.dp).probe(name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = SpType.bodySmall, color = if (light) SpColors.OnFill else SpColors.BarInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), onTextLayout = fits("bar/$name"))
        if (trailing != null) CompositionLocalProvider(LocalOnDark provides !light) { trailing(this) }
    }
}

@Composable
private fun TitleRow(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = SpType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f).probe("title"), onTextLayout = fits("control/title"))
        trailing?.invoke()
    }
}

/** A page: title row, then a scrolling column (D16: nothing cut by the window edge), [bottom] fixed under it. */
@Composable
private fun PageFrame(title: String, trailing: (@Composable () -> Unit)? = null, bottomSpace: Dp = 24.dp, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TitleRow(title, trailing)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            Spacer(Modifier.height(8.dp))
        }
        bottom?.invoke()
        Spacer(Modifier.height(bottomSpace))
    }
}

@Composable
private fun LabelRow(label: String, name: String, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = SpType.bodySmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).probe(name), onTextLayout = fits("label/$name"))
        control()
    }
}

@Composable
private fun MuteKey(state: SpUiState, onEvent: (Ev) -> Unit) {
    IconKey(if (state.prefs.sound) R.drawable.ic_volume_up else R.drawable.ic_volume_off, rs(R.string.mute), "mute") { onEvent(Ev.SetSound(!state.prefs.sound)) }
}

private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

@Composable
internal fun kindName(k: MarkerKind): String = if (zhUi()) k.zh else k.en

@Composable
internal fun wantName(w: Want): String = rs(when (w) { Want.MORE -> R.string.want_more; Want.LESS -> R.string.want_less; Want.LOOK -> R.string.want_look })

@Composable
internal fun verdictName(v: Verdict?): String = rs(when (v) { Verdict.OK -> R.string.v_ok; Verdict.MEH -> R.string.v_meh; Verdict.NO -> R.string.v_no; else -> R.string.v_neutral })

internal fun verdictColor(v: Verdict?): Color = when (v) { Verdict.OK -> SpColors.MarkerOk; Verdict.MEH -> SpColors.MarkerMeh; Verdict.NO -> SpColors.MarkerNo; else -> SpColors.MarkerNeutral }

@Composable
private fun dateLabel(state: SpUiState): String {
    val d = state.date
    val day = if (zhUi()) "${d.monthValue} 月 ${d.dayOfMonth} 日" else "${MONTHS_EN[d.monthValue - 1]} ${d.dayOfMonth}"
    return if (state.isToday) rs(R.string.date_today, day) else day
}

private val MONTHS_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

@Composable
private fun barText(state: SpUiState): String? {
    val b = state.bar ?: return null
    return when (b.kind) {
        BarKind.MAX_WINDOWS -> rs(R.string.max_windows)
        BarKind.MAX_MARKERS -> rs(R.string.max_markers)
        BarKind.RESTORED -> rs(R.string.restored, b.a, b.b)
        BarKind.PARTIAL -> rs(R.string.partial, b.a)
        BarKind.HEAD_TIMEOUT -> rs(R.string.head_timeout)
    }
}

@Composable
private fun CommonBars(state: SpUiState) {
    if (state.lost) InlineBar(rs(R.string.plate_lost), "bar_lost", warn = true)
    barText(state)?.let { InlineBar(it, "bar_${state.bar!!.kind.name.lowercase(Locale.US)}", warn = state.bar.kind == BarKind.MAX_MARKERS || state.bar.kind == BarKind.MAX_WINDOWS) }
}

// ================================================================================================= 窗 Window page

@Composable
internal fun WindowPage(state: SpUiState, onEvent: (Ev) -> Unit) {
    PageFrame(rs(R.string.win_title)) {
        if (state.welcome) {
            Welcome(onEvent)
            return@PageFrame
        }
        if (state.mismatch) Mismatch(state, onEvent)
        CommonBars(state)
        RoomLine(state, onEvent)
        val virtualWalls = state.room?.walls?.any { VirtualRoom.isVirtual(it.id) } == true
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.mark_window), "mark_window", KeyKind.PRIMARY, R.drawable.ic_window, enabled = !state.finding && state.room != null) { onEvent(Ev.MarkWindow) }
            if (virtualWalls) Key(rs(R.string.std_window), "std_window", KeyKind.NORMAL, enabled = !state.finding) { onEvent(Ev.StdWindow) }
        }
        CityRow(state, onEvent)
        FacingRows(state, onEvent)
        state.windows.forEachIndexed { i, w ->
            val zh = zhUi()
            val size = String.format(Locale.US, "%.1f × %.1f m", w.width, w.height)
            val text = if (state.project.northSet) {
                val b = state.project.bearingOf(w)
                "${Dirs.windowName(b, zh)} · $size · " + rs(R.string.faces_deg, Math.round(b)) + if (i == 0) " · " + rs(R.string.facing_ref) else ""
            } else rs(R.string.win_n, w.no) + " · $size · " + rs(R.string.facing_unset)
            Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(SpColors.WindowFrame))
                Line(text, "win_card_${w.no}", SpType.bodySmall, 2, modifier = Modifier.weight(1f))
                Key(rs(R.string.redo), "win_redo_${w.no}") { onEvent(Ev.RedoWindow(w.id)) }
                Key(rs(R.string.delete_short), "win_del_${w.no}", KeyKind.DANGER) { onEvent(Ev.DeleteWindow(w.id)) }
            }
        }
    }
}

@Composable
private fun Welcome(onEvent: (Ev) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            WelcomeStep(R.drawable.ic_window, rs(R.string.w_mark), "w_mark")
            WelcomeStep(R.drawable.ic_explore, rs(R.string.w_facing), "w_facing")
            WelcomeStep(R.drawable.ic_sunny, rs(R.string.w_time), "w_time")
        }
        Line(rs(R.string.welcome_1), "welcome_1", SpType.titleLarge, 2)
        Line(rs(R.string.welcome_2), "welcome_2", SpType.bodySmall, 2)
        Key(rs(R.string.start), "start", KeyKind.PRIMARY) { onEvent(Ev.WelcomeOk) }
    }
}

@Composable
private fun WelcomeStep(icon: Int, text: String, name: String) {
    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(SpColors.BarBg), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = SpColors.SunPatch)
        }
        Line(text, name, SpType.bodySmall)
    }
}

@Composable
private fun Mismatch(state: SpUiState, onEvent: (Ev) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(14.dp).probe("mismatch"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Line(rs(R.string.mismatch_title), "mismatch_title", SpType.titleLarge)
        Line(rs(R.string.mismatch_body), "mismatch_body", SpType.bodySmall, 2)
        if (state.confirmReset) ResetConfirm(state, onEvent)
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key(if (state.searching) rs(R.string.searching) else rs(R.string.look_again), "look_again", KeyKind.PRIMARY, enabled = !state.searching) { onEvent(Ev.LookAgain) }
            Key(rs(R.string.mark_again), "mark_again") { onEvent(Ev.ResetAsk) }
        }
    }
}

@Composable
private fun RoomLine(state: SpUiState, onEvent: (Ev) -> Unit) {
    val room = state.room
    when {
        state.noPermission -> InlineBar(rs(R.string.room_noperm), "room_line", warn = true) { Key(rs(R.string.grant), "grant") { onEvent(Ev.Grant) } }
        state.finding -> {
            InlineBar(rs(R.string.room_finding, if (state.floorSeen) "✓" else "…", state.wallsSeen), "room_line")
        }
        room?.kind == RoomKind.VIRTUAL -> {
            val v = room.virtual
            val size = if (v != null) String.format(Locale.US, "%.1f × %.1f × %.1f m", v.width, v.depth, v.height) else ""
            InlineBar(rs(R.string.room_virtual, size), "room_line") { Key(rs(R.string.align), "align") { onEvent(Ev.GoAlign) } }
        }
        room != null -> InlineBar(if (room.walls.any { VirtualRoom.isVirtual(it.id) }) rs(R.string.room_real_part, room.realWalls) else rs(R.string.room_real_all, room.realWalls), "room_line")
    }
}

@Composable
private fun CityRow(state: SpUiState, onEvent: (Ev) -> Unit) {
    val c = state.project.city
    val zh = zhUi()
    LabelRow(rs(R.string.city_line, c.name(zh), c.coords), "city_line") {
        Key(rs(R.string.change), "city_change") { onEvent(Ev.ToggleCity) }
    }
    if (!state.cityOpen) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchField(state.cityQuery, { onEvent(Ev.CityQuery(it)) }, { onEvent(Ev.CityQuery(it)) }, Modifier.fillMaxWidth().height(48.dp).probe("city_search"))
        val list = if (state.cityQuery.isBlank()) Cities.all else Cities.search(state.cityQuery)
        list.forEach { city ->
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (city.key == c.key) SpColors.Interaction.copy(alpha = 0.35f) else Color.Transparent).clickable { onEvent(Ev.PickCity(city.key)) }.padding(horizontal = 12.dp).probe("city_${city.key}"), verticalAlignment = Alignment.CenterVertically) {
                Line(city.name(zh), "city_name_${city.key}", SpType.body, modifier = Modifier.weight(1f))
                Line(city.coords, "city_coords_${city.key}", SpType.label)
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).clickable { onEvent(Ev.ToggleCoords) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Line(rs(R.string.use_coords), "use_coords", SpType.body)
        }
        if (state.coordsOpen) {
            LabelRow(rs(R.string.lat) + " " + String.format(Locale.US, "%.1f", c.lat), "lat") {
                IconKey(R.drawable.ic_remove, "-", "lat_minus") { onEvent(Ev.NudgeCoords(-0.1, 0.0)) }
                IconKey(R.drawable.ic_add, "+", "lat_plus") { onEvent(Ev.NudgeCoords(0.1, 0.0)) }
            }
            LabelRow(rs(R.string.lon) + " " + String.format(Locale.US, "%.1f", c.lon), "lon") {
                IconKey(R.drawable.ic_remove, "-", "lon_minus") { onEvent(Ev.NudgeCoords(0.0, -0.1)) }
                IconKey(R.drawable.ic_add, "+", "lon_plus") { onEvent(Ev.NudgeCoords(0.0, 0.1)) }
            }
            Line(rs(R.string.zone_note, c.zoneId), "zone_note", SpType.label)
        }
    }
}

@Composable
private fun FacingRows(state: SpUiState, onEvent: (Ev) -> Unit) {
    val has = state.windows.isNotEmpty()
    val facing = state.project.facingDeg()
    val zh = zhUi()
    Line(rs(R.string.facing_label), "facing_label", SpType.bodySmall)
    if (has && !state.project.northSet) InlineBar(rs(R.string.facing_fill), "facing_fill", warn = true)
    val names = if (zh) Dirs.ZH else Dirs.EN
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Segments(names.mapIndexed { i, n -> (i * 45) to n }, if (state.project.northSet && facing != null) Dirs.index8(facing) * 45 else null, "facing", 56, enabled = has) { deg -> onEvent(Ev.SetFacing(deg.toFloat())) }
    }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("−5°", "facing_minus", enabled = has) { onEvent(Ev.NudgeFacing(-5f)) }
        Key("+5°", "facing_plus", enabled = has) { onEvent(Ev.NudgeFacing(5f)) }
        val read = when {
            !has -> rs(R.string.facing_first)
            state.project.northSet && facing != null -> rs(R.string.facing_manual, Dirs.name(facing, zh) + " " + Math.round(facing) + "°")
            else -> rs(R.string.facing_unset)
        }
        Line(read, "facing_read", SpType.body, modifier = Modifier.weight(1f))
    }
    Line(rs(R.string.facing_hint), "facing_hint", SpType.label, 2)
    if (has && state.project.northSet) InlineBar(rs(R.string.facing_done), "facing_done", ok = true)
}

// ================================================================================================= 看光 Sun page

@Composable
internal fun SunPage(state: SpUiState, onEvent: (Ev) -> Unit) {
    PageFrame(rs(R.string.sun_title), trailing = { MuteKey(state, onEvent) }, bottomSpace = 56.dp, bottom = {
        Line(rs(R.string.disclaimer), "disclaimer", SpType.label, modifier = Modifier.padding(top = 4.dp))
    }) {
        CommonBars(state)
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Line(dateLabel(state), "date", SpType.body, modifier = Modifier.weight(1f))
            Key(rs(R.string.today), "today") { onEvent(Ev.Today) }
        }
        MonthSlider(state, onEvent)
        TimeSlider(state, onEvent)
        Readout(state)
        Line(conclusionText(state), "conclusion", SpType.body, 2)
        LabelRow(rs(R.string.follow), "follow") {
            Switch(checked = state.prefs.followNow, onCheckedChange = { onEvent(Ev.Follow(it)) }, modifier = Modifier.probe("follow_switch"))
        }
    }
}

@Composable
private fun MonthSlider(state: SpUiState, onEvent: (Ev) -> Unit) {
    var v by remember(state.date.monthValue) { mutableFloatStateOf(state.date.monthValue.toFloat()) }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Line(rs(R.string.month) + " " + v.roundToInt(), "month_label", SpType.bodySmall, modifier = Modifier.width(84.dp))
        Slider(value = v, onValueChange = {
            v = it
            val m = it.roundToInt()
            if (m != state.date.monthValue || state.isToday) onEvent(Ev.Month(m))
        }, modifier = Modifier.weight(1f).height(48.dp).probe("month_slider"), valueRange = 1f..12f)
    }
}

@Composable
private fun TimeSlider(state: SpUiState, onEvent: (Ev) -> Unit) {
    var v by remember(state.minute) { mutableFloatStateOf(state.minute.toFloat()) }
    LaunchedEffect(state.minute) { v = state.minute.toFloat() }
    val r = state.light?.readout
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Line(Solar.fmt(v.toDouble()), "time_label", SpType.body, modifier = Modifier.width(84.dp))
            Slider(value = v, onValueChange = {
                v = it
                val m = ((it / Solar.STEP_MIN).roundToInt() * Solar.STEP_MIN)
                if (m != state.minute) onEvent(Ev.Minute(m))
            }, modifier = Modifier.weight(1f).height(48.dp).probe("time_slider"), valueRange = 0f..Solar.DAY_MIN.toFloat())
        }
        if (r?.rise != null && r.set != null) Line(rs(R.string.ticks, Solar.fmt(r.rise), Solar.fmt(r.set)), "ticks", SpType.label, modifier = Modifier.padding(start = 92.dp))
    }
}

@Composable
private fun Readout(state: SpUiState) {
    val r = state.light?.readout ?: return
    val zh = zhUi()
    val text = if (r.rise == null && r.set == null && r.altDeg <= 0) rs(R.string.readout_nosun)
    else rs(R.string.readout, Math.max(0, Math.round(r.altDeg).toInt()), Dirs.name16(r.azDeg, zh), Math.round(r.azDeg).toInt(), Solar.fmt(r.rise), Solar.fmt(r.set))
    Line(text, "readout", SpType.bodySmall, 2)
}

@Composable
internal fun conclusionText(state: SpUiState): String {
    if (state.sweep?.running == true) return rs(R.string.concl_sweep)
    return when (val c = state.light?.conclusion) {
        null -> rs(R.string.computing)
        Conclusion.NoWindow -> rs(R.string.facing_first)
        Conclusion.NoFacing -> rs(R.string.facing_fill)
        is Conclusion.BeforeRise -> rs(R.string.concl_before, Solar.fmt(c.rise))
        Conclusion.AfterSet -> rs(R.string.concl_set)
        Conclusion.NotToday -> rs(R.string.concl_not_today)
        is Conclusion.Behind -> if (c.many) rs(R.string.concl_behind_n, rs(R.string.win_n, c.windowNo), Solar.fmt(c.minute.toDouble())) else rs(R.string.concl_behind, Solar.fmt(c.minute.toDouble()))
        is Conclusion.Lands -> {
            val parts = ArrayList<String>()
            parts += rs(R.string.floor_m2, f1(c.floorM2))
            if (c.onUp) parts += rs(R.string.desk)
            if (c.onWall) parts += rs(R.string.wall)
            for (id in c.markers) state.marker(id)?.let { parts += kindName(it.kind) + " ✓" }
            rs(R.string.concl_lands, parts.joinToString(" · "))
        }
    }
}

/** The Sun page's system Toolbar: [现在] [▶ 扫一天] / [■ 停]. */
@Composable
internal fun SunToolbar(state: SpUiState, onEvent: (Ev) -> Unit) {
    Button(onClick = { onEvent(Ev.Now) }, modifier = Modifier.probe("now")) {
        Icon(painter = painterResource(R.drawable.ic_my_location), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = rs(R.string.now), maxLines = 1, style = SpType.button, onTextLayout = fits("toolbar/now"))
    }
    val running = state.sweep?.running == true
    Button(onClick = { onEvent(if (running) Ev.SweepStop else Ev.SweepStart()) }, modifier = Modifier.probe("sweep")) {
        Icon(painter = painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = rs(if (running) R.string.stop else R.string.sweep), maxLines = 1, style = SpType.button, onTextLayout = fits("toolbar/sweep"))
    }
}

// ================================================================================================= 日照图 Hours page

@Composable
internal fun HoursPage(state: SpUiState, onEvent: (Ev) -> Unit) {
    val sel = state.marker(state.selected)
    PageFrame(rs(R.string.hours_title)) {
        CommonBars(state)
        if (sel != null) {
            MarkerDetail(state, sel, onEvent)
            return@PageFrame
        }
        LabelRow(rs(R.string.draw_floor), "draw_floor") {
            Switch(checked = state.heat, onCheckedChange = { onEvent(Ev.Heat(it)) }, modifier = Modifier.probe("heat_switch"))
        }
        Legend()
        val h = state.hours
        val stat = when {
            state.windows.isEmpty() -> rs(R.string.facing_first)
            !state.project.northSet -> rs(R.string.facing_fill)
            h == null -> rs(R.string.computing)
            else -> rs(R.string.area4, f1(h.area4))
        }
        Line(dateLabel(state) + " · " + stat, "area4", SpType.bodySmall, 2)
        if (state.placing) InlineBar(rs(R.string.placing_hint), "placing") { Key(rs(R.string.cancel), "place_cancel") { onEvent(Ev.PlaceCancel) } }
        else Row { Key(rs(R.string.drop_marker), "drop_marker", KeyKind.PRIMARY, enabled = state.room != null) { onEvent(Ev.PlaceMarker) } }
        state.markers.sortedBy { it.no }.forEach { m -> MarkerRow(state, m, onEvent) }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("0 h" to Color.Transparent, "1 h" to SpColors.Heat1, "2 h" to SpColors.Heat2, "4 h+" to SpColors.Heat3).forEach { (t, c) ->
            Box(Modifier.width(64.dp).height(28.dp).clip(RoundedCornerShape(6.dp)).background(if (c == Color.Transparent) PicoTheme.colorScheme.fillTertiary else c), contentAlignment = Alignment.Center) {
                Text(text = t, style = SpType.label, color = if (c == Color.Transparent) PicoTheme.colorScheme.labelPrimary else SpColors.OnFill, maxLines = 1, onTextLayout = fits("legend/$t"))
            }
        }
    }
    Line(rs(R.string.heat_legend), "heat_legend", SpType.label, 2)
}

@Composable
private fun MarkerRow(state: SpUiState, m: MarkerRec, onEvent: (Ev) -> Unit) {
    val st = state.hours?.stats?.get(m.id)
    val v = state.verdicts[m.id]
    val text = kindName(m.kind) + " · " + wantName(m.want) + " · " + if (st != null) rs(R.string.today_h, f1(st.hours)) else rs(R.string.computing)
    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).clickable { onEvent(Ev.SelectMarker(m.id)) }.padding(horizontal = 12.dp).probe("marker_row_${m.no}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(verdictColor(v)))
        Line(text, "marker_text_${m.no}", SpType.bodySmall, modifier = Modifier.weight(1f))
        Key(rs(R.string.bring), "marker_front_${m.no}") { onEvent(Ev.MarkerFront(m.id)) }
        Key(rs(R.string.delete_short), "marker_del_${m.no}", KeyKind.DANGER) { onEvent(Ev.MarkerDelete(m.id)) }
    }
}

@Composable
private fun MarkerDetail(state: SpUiState, m: MarkerRec, onEvent: (Ev) -> Unit) {
    val st = state.hours?.stats?.get(m.id)
    val v = state.verdicts[m.id] ?: st?.let { Verdicts.of(m.want, it.hours) }
    Row { Key(rs(R.string.all_markers), "back", icon = R.drawable.ic_chevron_left) { onEvent(Ev.SelectMarker(null)) } }
    Line(rs(R.string.what_is_it), "what_is_it", SpType.label)
    Row(Modifier.fillMaxWidth()) {
        Segments(MarkerKind.values().map { it to kindName(it) }, m.kind, "kind", 72) { k -> onEvent(Ev.MarkerKindSet(m.id, k)) }
    }
    Line(rs(R.string.want), "want_label", SpType.label)
    Row(Modifier.fillMaxWidth()) {
        Segments(Want.values().map { it to wantName(it) }, m.want, "want", 96) { w -> onEvent(Ev.MarkerWant(m.id, w)) }
    }
    Line(rs(R.string.months_label), "months_label", SpType.label)
    MonthBars(st?.months, state.date.monthValue)
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(verdictColor(v)))
        val line = when {
            st == null -> rs(R.string.computing)
            st.hours > 0f -> rs(R.string.today_line, f1(st.hours), Solar.fmt(st.first?.toDouble()), Solar.fmt(st.last?.toDouble())) + " · " + verdictName(if (m.want == Want.LOOK) null else v)
            else -> rs(R.string.no_sun_today) + " · " + verdictName(if (m.want == Want.LOOK) null else v)
        }
        Line(line, "today_line", SpType.body, modifier = Modifier.weight(1f))
    }
    if (st != null) Line(tip(m, st.hours, st.first, st.last, v), "tip", SpType.bodySmall, 2)
    if (state.confirmDelete) {
        InlineBar(rs(R.string.delete_q), "delete_confirm", warn = true) {
            Key(rs(R.string.cancel), "delete_cancel") { onEvent(Ev.MarkerDeleteCancel) }
            Key(rs(R.string.delete), "delete_yes", KeyKind.DANGER) { onEvent(Ev.MarkerDelete(m.id)) }
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Key(rs(R.string.bring), "front", icon = R.drawable.ic_recenter) { onEvent(Ev.MarkerFront(m.id)) }
        Key(rs(R.string.delete), "delete", KeyKind.DANGER, R.drawable.ic_delete) { onEvent(Ev.MarkerDeleteAsk(m.id)) }
    }
}

@Composable
private fun tip(m: MarkerRec, hours: Float, first: Int?, last: Int?, v: Verdict?): String = when {
    m.want == Want.LOOK -> rs(R.string.tip_look)
    v == Verdict.OK -> rs(R.string.tip_ok)
    m.want == Want.MORE -> if (hours < 1f) rs(R.string.tip_more_low, Math.round(hours * 60)) else rs(R.string.tip_more_almost)
    else -> rs(R.string.tip_less, Solar.fmt(first?.toDouble()), Solar.fmt(last?.toDouble()))
}

/** 12 monthly bars (the 15th of each month), coloured like the heat ramp, the current month's number in amber. */
@Composable
private fun MonthBars(months: FloatArray?, current: Int) {
    Row(Modifier.fillMaxWidth().height(96.dp).probe("month_bars"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 12) {
            val h = months?.getOrNull(i) ?: 0f
            val frac = (h / 8f).coerceIn(0.04f, 1f)
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(frac).clip(RoundedCornerShape(4.dp)).background(if (h >= 4f) SpColors.Heat3 else if (h >= 2f) SpColors.Heat2 else SpColors.Heat1))
                }
                val now = i + 1 == current
                Text(text = "${i + 1}", style = SpType.label, color = if (now) SpColors.InkOnGlass else PicoTheme.colorScheme.labelPrimary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (now) SpColors.Interaction else Color.Transparent), onTextLayout = fits("months/$i"))
            }
        }
    }
}

// ================================================================================================= 设置 Settings page

@Composable
internal fun SettingsPage(state: SpUiState, onEvent: (Ev) -> Unit) {
    val p = state.prefs
    val scroll = rememberScrollState()
    LaunchedEffect(state.confirmReset) {
        // the reset confirm sits near the end of the page: bring the whole bar with both keys into view
        if (state.confirmReset) {
            kotlinx.coroutines.delay(100)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleRow(rs(R.string.tab_settings)) { MuteKey(state, onEvent) }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelRow(rs(R.string.lang), "lang") {
                Segments(listOf(Lang.SYSTEM to rs(R.string.lang_system), Lang.ZH to "中文", Lang.EN to "English"), p.lang, "lang", 72) { onEvent(Ev.SetLang(it)) }
            }
            LabelRow(rs(R.string.brightness), "brightness") {
                Segments(listOf(Brightness.SOFT to rs(R.string.b_soft), Brightness.MEDIUM to rs(R.string.b_medium), Brightness.BRIGHT to rs(R.string.b_bright)), p.brightness, "brightness", 64) { onEvent(Ev.SetBrightness(it)) }
            }
            LabelRow(rs(R.string.sound), "sound") { Switch(checked = p.sound, onCheckedChange = { onEvent(Ev.SetSound(it)) }, modifier = Modifier.probe("sound_switch")) }
            LabelRow(rs(R.string.panel_far), "panel_far") { Key(rs(R.string.recall), "recall", icon = R.drawable.ic_recenter) { onEvent(Ev.Recall) } }
            // [叫回面前] and [退出应用] stay on the first screen (D16 / D26, ui_gates settings-shot)
            if (state.confirmExit) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).probe("exit_confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Line(rs(R.string.quit_q), "quit_q", SpType.title)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Key(rs(R.string.cancel), "quit_cancel", modifier = Modifier.weight(1f)) { onEvent(Ev.QuitCancel) }
                        Key(rs(R.string.quit_yes), "quit_yes", KeyKind.DANGER, R.drawable.ic_logout, modifier = Modifier.weight(1f)) { onEvent(Ev.QuitYes) }
                    }
                }
            } else Key(rs(R.string.quit), "quit", KeyKind.DANGER, R.drawable.ic_logout, modifier = Modifier.fillMaxWidth()) { onEvent(Ev.QuitAsk) }
            VirtualRoomRows(state, onEvent)
            if (state.confirmReset && !state.mismatch) ResetConfirm(state, onEvent)
            else LabelRow(rs(R.string.reset_row), "reset_row") { Key(rs(R.string.reset_btn), "reset", icon = R.drawable.ic_restart_alt) { onEvent(Ev.ResetAsk) } }
            Line(rs(R.string.about), "about", SpType.body)
            Line(rs(R.string.about_body), "about_body", SpType.label, 4)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResetConfirm(state: SpUiState, onEvent: (Ev) -> Unit) {
    InlineBar(rs(R.string.reset_q, state.windows.size, state.markers.size), "reset_confirm", warn = true) {
        Key(rs(R.string.cancel), "reset_cancel") { onEvent(Ev.ResetCancel) }
        Key(rs(R.string.clear), "reset_yes", KeyKind.DANGER) { onEvent(Ev.ResetYes) }
    }
}

@Composable
private fun VirtualRoomRows(state: SpUiState, onEvent: (Ev) -> Unit) {
    val virtual = state.room?.kind == RoomKind.VIRTUAL
    if (!virtual && !state.vroomOpen) {
        LabelRow(rs(R.string.vroom), "vroom") { Key(rs(R.string.use_virtual), "use_virtual") { onEvent(Ev.UseVirtual) } }
        return
    }
    Line(rs(R.string.vroom), "vroom_title", SpType.body)
    val p = state.prefs
    LabelRow(rs(R.string.width) + " " + f1(p.roomW) + " m", "room_w") {
        IconKey(R.drawable.ic_remove, "-", "room_w_minus") { onEvent(Ev.RoomSize(-VirtualRoom.STEP_M, 0f, 0f)) }
        IconKey(R.drawable.ic_add, "+", "room_w_plus") { onEvent(Ev.RoomSize(VirtualRoom.STEP_M, 0f, 0f)) }
    }
    LabelRow(rs(R.string.depth) + " " + f1(p.roomD) + " m", "room_d") {
        IconKey(R.drawable.ic_remove, "-", "room_d_minus") { onEvent(Ev.RoomSize(0f, -VirtualRoom.STEP_M, 0f)) }
        IconKey(R.drawable.ic_add, "+", "room_d_plus") { onEvent(Ev.RoomSize(0f, VirtualRoom.STEP_M, 0f)) }
    }
    LabelRow(rs(R.string.height) + " " + f1(p.roomH) + " m", "room_h") {
        IconKey(R.drawable.ic_remove, "-", "room_h_minus") { onEvent(Ev.RoomSize(0f, 0f, -VirtualRoom.HEIGHT_STEP_M)) }
        IconKey(R.drawable.ic_add, "+", "room_h_plus") { onEvent(Ev.RoomSize(0f, 0f, VirtualRoom.HEIGHT_STEP_M)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key(rs(R.string.move_to_me), "recenter", icon = R.drawable.ic_recenter, enabled = virtual) { onEvent(Ev.RoomRecenter) }
        Key(rs(R.string.turn_left), "turn_left", icon = R.drawable.ic_rotate_left, enabled = virtual) { onEvent(Ev.RoomTurn(-5f)) }
        Key(rs(R.string.turn_right), "turn_right", icon = R.drawable.ic_rotate_right, enabled = virtual) { onEvent(Ev.RoomTurn(5f)) }
    }
    val walls = state.wallsSeen
    if (virtual) {
        LabelRow(if (walls > 0) rs(R.string.real_wall_found) else rs(R.string.vroom), "back_to_scan") {
            Key(rs(R.string.back_to_scan), "back_to_scan_key", enabled = !state.noPermission) { onEvent(Ev.BackToScan) }
        }
    } else LabelRow(rs(R.string.vroom), "use_virtual_row") { Key(rs(R.string.use_virtual), "use_virtual") { onEvent(Ev.UseVirtual) } }
}

// ================================================================================================= Stage plate

/** The Stage plate's content (design `spatialLayout.stagePlate`): one opaque card, 360 x 160 dp. */
@Composable
internal fun StagePlateContent(mode: PlateMode, state: SpUiState?, onEvent: (Ev) -> Unit) {
    Box(Modifier.size(PanelSizes.PLATE_W.dp, PanelSizes.PLATE_H.dp), contentAlignment = Alignment.Center) {
        if (state == null || mode == PlateMode.NONE) return@Box
        if (mode == PlateMode.OPEN) {
            Key(rs(R.string.open_panel), "open_panel", KeyKind.PRIMARY, R.drawable.ic_open_with) { WindowCtl.reopen("chip") }
            return@Box
        }
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(SpColors.BarBg).padding(horizontal = 16.dp, vertical = 10.dp).probe("plate_$mode"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            CompositionLocalProvider(LocalOnDark provides true) { PlateBody(mode, state, onEvent) }
        }
    }
}

@Composable
private fun PlateBody(mode: PlateMode, state: SpUiState, onEvent: (Ev) -> Unit) {
    when (mode) {
        PlateMode.LOADING -> {
            CircularProgressIndicator(modifier = Modifier.size(36.dp), color = SpColors.BarInk, progressSize = CircularProgressDefaults.circleProgressSize(36.dp))
            PlateText(rs(R.string.loading), "loading")
        }
        PlateMode.MARKING -> {
            val m = state.marking
            PlateText(rs(if (m?.first == null) R.string.plate_corner1 else R.string.plate_corner2), "corner")
            m?.reject?.let { r ->
                Text(text = rs(when (r) { Reject.NOT_WALL -> R.string.reject_wall; Reject.OTHER_WALL -> R.string.reject_other; Reject.TOO_SMALL -> R.string.reject_small }), style = SpType.bodySmall, color = SpColors.ErrorOnDark, maxLines = 1, onTextLayout = fits("plate/reject"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Key(rs(R.string.redo_corners), "corner_redo") { onEvent(Ev.CornerRedo) }
                Key(rs(R.string.cancel), "corner_cancel") { onEvent(Ev.CornerCancel) }
            }
        }
        PlateMode.LEVEL -> PlateText(rs(R.string.plate_level), "level")
        PlateMode.ASIDE -> PlateText(rs(if (WindowCtl.asideTurn > 0) R.string.plate_aside_left else R.string.plate_aside_right), "aside")
        PlateMode.LOOK_AHEAD -> PlateText(rs(R.string.plate_look_ahead), "look_ahead")
        PlateMode.LOST -> PlateText(rs(R.string.plate_lost), "lost")
        PlateMode.BACK -> PlateText(rs(R.string.plate_back), "back")
        else -> Unit
    }
}

@Composable
private fun PlateText(text: String, name: String) {
    Text(text = text, style = SpType.titleLarge, color = SpColors.BarInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("plate_text_$name"), onTextLayout = fits("plate/$name"))
}
