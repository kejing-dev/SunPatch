package io.embodit.sunpatch.ui.window

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.sunpatch.platform.SpLog

/*
 * 主窗口模板（taste D8「合成一个窗口模板」+ D21 拖动交给系统 + D26 设置里退出应用），产线 prompts/snippets/
 * MainWindowTemplate.kt 的本应用版。和产线版的差别只有三处，结构不变：
 *  - 当前页由应用的状态机决定（[current] + [onSelect]），不是模板自己 rememberSaveable：调试广播和面板按钮走同一套事件；
 *  - 页面标题由页面自己画（本应用的标题行带计时 chip 和静音开关）；
 *  - 「设置」「退出应用」「取消」「退出」这些字由应用传进来（中英双语，D26）。退出的二次确认同样在这一页里展开，
 *    确认状态也在状态机里（S12）。
 * 规矩照旧：应用全部 2D 内容都在这一个系统窗口里；页面用系统 TabBar 切（系统挂在窗口左侧的独立小窗，不叠在窗口前后）；
 * 拖动 / 关闭 / 最小化是系统窗口控件（窗口下方的小圆点），这里不写任何拖动代码。
 */

/** One page of the main window. [icon]: a Material Symbols vector (taste D23); [toolbar]: this page's quick actions. */
class MainWindowPage(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val toolbar: (@Composable RowScope.() -> Unit)? = null,
    val content: @Composable () -> Unit,
)

/** The settings page: always last in the TabBar, the app's rows on top, the exit row at the bottom (D26). */
class MainWindowSettings(
    val label: String,
    @DrawableRes val icon: Int,
    val rows: @Composable ColumnScope.() -> Unit,
)

const val SETTINGS_PAGE = "settings"

/**
 * The app's single main window. [pages] are the app's own pages (at most 6: the TabBar holds 7 and the last one is
 * Settings). [current] is the page shown (a page id or [SETTINGS_PAGE]); a TabBar tap calls [onSelect].
 */
@Composable
fun MainWindow(pages: List<MainWindowPage>, settings: MainWindowSettings, current: String, onSelect: (String) -> Unit) {
    require(pages.size in 1..6) { "TabBar holds at most 7 items: 6 pages + Settings" }
    val page = pages.firstOrNull { it.id == current }

    Box(Modifier.fillMaxSize()) {
        if (page != null) {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) { page.content() }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.rows(this)
            }
        }
    }

    // system TabBar: its own small window, anchored to the left edge of this window (seen from the front and the left)
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        pages.forEach { p ->
            item(
                selected = current == p.id,
                onClick = { SpLog.i("TAB ${p.id}"); onSelect(p.id) },
                mainContent = { Icon(painter = painterResource(p.icon), contentDescription = p.label) },
                supportContent = { Text(p.label) },
            )
        }
        item(
            selected = current == SETTINGS_PAGE,
            onClick = { SpLog.i("TAB $SETTINGS_PAGE"); onSelect(SETTINGS_PAGE) },
            mainContent = { Icon(painter = painterResource(settings.icon), contentDescription = settings.label) },
            supportContent = { Text(settings.label) },
        )
    }

    // system Toolbar under the window, only for pages that have quick actions
    page?.toolbar?.let { actions -> Toolbar { actions() } }
}
