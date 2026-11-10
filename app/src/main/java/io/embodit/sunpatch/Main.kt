package io.embodit.sunpatch

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.sunpatch.ui.SpScreen
import io.embodit.sunpatch.ui.components.PanelSizes
import io.embodit.sunpatch.ui.theme.SpTheme
import io.embodit.sunpatch.ui.window.PANEL_ID
import io.embodit.sunpatch.ui.window.SpWindow

/**
 * App entry (design container STAGE_MIXED): the default Stage is Mixed (manifest pico.spatial.stage.style = 1), the sun
 * falls on your real floor; all 2D content is one system Planar window (560 x 480 dp, design `windowModel`) opened from
 * the Stage through the level gate; the system moves / closes / minimises it (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { SpTheme { SpScreen() } }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PanelSizes.WINDOW_W.dp, height = PanelSizes.WINDOW_H.dp),
        ) { SpWindow() }
    }
