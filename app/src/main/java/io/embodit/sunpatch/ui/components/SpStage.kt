package io.embodit.sunpatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.SpController
import io.embodit.sunpatch.ui.SpUiState
import io.embodit.sunpatch.ui.debug.LocalProbePanel
import io.embodit.sunpatch.ui.debug.Probe
import io.embodit.sunpatch.ui.scene.StageUi
import io.embodit.sunpatch.ui.scene.SunScene
import io.embodit.sunpatch.ui.theme.SpTheme

/**
 * The Stage content: one full-size SpatialView holding the light layers, windows, markers and the one attachment slot
 * `stagePlate` ([SunScene] parents and places it). Spatial taps / drags reach the scene: markers (their 12 cm colliders)
 * and, only in S04 / S11, the tap catcher; the room's planes have no colliders (D24). No panel drag on the Stage (D21).
 */
@Composable
internal fun SpStage(state: SpUiState, scene: SunScene) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(SunScene.PLATE_ID) {
                PlatePanel(scene) { inView -> SpTheme { StagePlateContent(StageUi.mode, if (inView) state else null, SpController::onEvent) } }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments, context) },
    )
}

/** Root of the plate: content composes once it is in view; a press tells the scene (that trigger is not a corner tap). */
@Composable
private fun PlatePanel(scene: SunScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = StageUi.plateShown
    val watcher =
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    if (event.type == PointerEventType.Press) scene.onPlatePointer()
                    if (Probe.enabled) {
                        val p = event.changes.firstOrNull()?.position
                        SpLog.i(String.format(java.util.Locale.US, "PTR panel=stagePlate ev=%s x=%.1f y=%.1f", event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides SunScene.PLATE_ID) {
        Box(modifier = watcher) { content(inView) }
    }
}
