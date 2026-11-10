package io.embodit.sunpatch.ui.theme

import androidx.compose.ui.graphics.Color

/** The design's colours (design-package `palette`). The only file with colour literals. */
object SpColors {
    // ColorScheme roles the design overrides (palette.colorScheme)
    val Interaction = Color(0xFFF2A93B) // design-style: fixed-figma-color main button / selected breathing (sun family)
    val Passable = Color(0xFF57C99A) // design-style: fixed-figma-color verdict ok
    val Alert = Color(0xFFF2B84B) // design-style: fixed-figma-color verdict meh / warning bars
    val Error = Color(0xFFF0645A) // design-style: fixed-figma-color verdict no / delete / quit

    // ink on the light fills (interaction, passable, alert, error): near-black slate
    val OnFill = Color(0xFF1F1A24) // design-style: fixed-figma-color ink on light fills
    val BarBg = Color(0xFF1E2A33) // design-style: fixed-figma-color inline bar background (opaque, readable on any glass)
    val BarInk = Color(0xFFE6F1F7) // design-style: fixed-figma-color inline bar text
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color reticle / dots

    // QA #1 q1-1 / q1-2: plain keys get opaque fills of their own (the system's default key is a dark translucent pill;
    // with labelPrimary ink it read 1.4-2.3:1). On the light window glass: slate key + white ink; on the dark inline bar
    // and the Stage plate: light key + slate ink.
    val KeyFill = Color(0xFF3B4652) // design-style: fixed-figma-color plain key on the glass (white ink 9.6:1)
    val KeyInk = Color(0xFFFFFFFF) // design-style: fixed-figma-color plain key ink on KeyFill
    val KeyFillLight = Color(0xFFE6ECF1) // design-style: fixed-figma-color plain key on the dark bar / plate (OnFill ink 14:1)
    val DangerKey = Color(0xFFF58A80) // design-style: fixed-figma-color danger key fill, a lighter error coral (OnFill ink 7.2:1; Error itself measured 4.1:1 on HD shots)
    val ErrorOnDark = Color(0xFFFF8A80) // design-style: fixed-figma-color reject text on the dark plate (6.5:1)
    val InkOnGlass = Color(0xFF1F1A24) // design-style: fixed-figma-color current-month number on its amber pill

    // 3D (palette.brandTokens)
    val SunPatch = Color(0xFFFFCF7A) // design-style: fixed-figma-color patch 45 %
    val PatchRim = Color(0xFFFFE9B8) // design-style: fixed-figma-color patch rim
    val WindowFrame = Color(0xFF58B8E6) // design-style: fixed-figma-color window frame lines / corner blocks
    val SunDisc = Color(0xFFFFD56A) // design-style: fixed-figma-color sun disc / sun ray dashes
    val Heat1 = Color(0xFF8FC9DE) // design-style: fixed-figma-color 1 h
    val Heat2 = Color(0xFFF2C96B) // design-style: fixed-figma-color 2 h
    val Heat3 = Color(0xFFF0963C) // design-style: fixed-figma-color 4 h+
    val MarkerOk = Color(0xFF57C99A) // design-style: fixed-figma-color ring ok (solid)
    val MarkerMeh = Color(0xFFF2B84B) // design-style: fixed-figma-color ring meh (dashed)
    val MarkerNo = Color(0xFFF0645A) // design-style: fixed-figma-color ring no (broken)
    val MarkerNeutral = Color(0xFFB8BFC7) // design-style: fixed-figma-color ring just looking
    val RoomLine = Color(0xFF8FC9DE) // design-style: fixed-figma-color virtual room edges at 55 %
    val PlateInk = Color(0xFF1E2A33) // design-style: fixed-figma-color 3D plate background
}
