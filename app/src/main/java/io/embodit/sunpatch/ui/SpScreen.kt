package io.embodit.sunpatch.ui

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.sunpatch.platform.SpLog
import io.embodit.sunpatch.ui.components.LocalSpResources
import io.embodit.sunpatch.ui.components.SpStage
import io.embodit.sunpatch.ui.components.localizedResources
import io.embodit.sunpatch.ui.debug.Probe
import io.embodit.sunpatch.ui.scene.SunFrameSystem
import io.embodit.sunpatch.ui.scene.SunScene
import io.embodit.sunpatch.ui.window.WindowCtl

/**
 * The Stage screen: wires [SpController] (state), [SunScene] (entities, per-frame work) and [SpStage] (the plate), and
 * asks for the main window ([io.embodit.sunpatch.ui.window.SpWindow]) through the level gate.
 */
@Composable
fun SpScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        SpController.start(context)
        true
    }
    val state by SpController.state.collectAsStateWithLifecycle()
    val scene = remember { SunScene() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<SunFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): SunScene = scene }
        val observer =
            LifecycleEventObserver { _, event ->
                SpLog.i("STATE lifecycle event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        SpController.onEvent(Ev.Background(true))
                        scene.pause()
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.resume()
                        SpController.onEvent(Ev.Background(false))
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        SpLog.i("STATE stage enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<SunFrameSystem>()
            scene.detach()
            SpLog.i("STATE stage disposed")
        }
    }

    val navigator = LocalSpatialNavigator.current
    DisposableEffect(navigator) {
        WindowCtl.navigator = navigator
        onDispose { if (WindowCtl.navigator === navigator) WindowCtl.navigator = null }
    }
    LaunchedEffect(Unit) { WindowCtl.openWindow("stage") }

    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    CompositionLocalProvider(LocalSpResources provides resources) {
        SpStage(state = state, scene = scene)
    }
}
