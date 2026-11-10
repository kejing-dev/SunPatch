package io.embodit.sunpatch.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.sunpatch.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
