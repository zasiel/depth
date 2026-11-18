package com.cyote3d.app

import android.os.Bundle
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import com.cyote3d.app.ui.LibraryPanel
import com.cyote3d.app.ui.ViewerBar
import com.cyote3d.app.viewer.PhotoBuilder
import com.cyote3d.app.viewer.PhotoSystem
import com.cyote3d.app.viewer.PhotoWindow
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cyote3D, Milestone 0: Library panel -> tap a bundled sample -> 3D window with the custom shader. */
class Cyote3DActivity : AppSystemActivity() {
  private companion object {
    const val TAG = "Cyote3D"

    // Library panel: 1200 x 760 dp at 800 dp per metre = 1.5 x 0.95 m.
    const val DP_PER_METER = 800f
    const val LIBRARY_W = 1.5f
    const val LIBRARY_H = 0.95f

    // Viewer pill: 640 x 88 dp.
    const val BAR_W = 0.8f
    const val BAR_H = 0.11f

    val LIBRARY_POS = Vector3(0f, 1.35f, -1.6f)
    val BAR_POS = Vector3(0f, 0.72f, -1.35f)
  }

  private lateinit var window: PhotoWindow
  private var libraryEntity: Entity? = null
  private var barEntity: Entity? = null
  private var loadJob: Job? = null
  private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window = PhotoWindow(this)
    systemManager.registerSystem(PhotoSystem(window))
    AppState.photos.clear()
    AppState.photos.addAll(PhotoCatalog.loadSamples(assets))
    AppState.onOpen = ::openPhoto
    AppState.onClose = ::closeViewer
    AppState.onStep = ::stepPhoto
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0f, 0f, 0f, 0f)
    showLibrary()
  }

  override fun onDestroy() {
    activityScope.cancel()
    super.onDestroy()
  }

  override fun onSceneTick() {
    super.onSceneTick()
    window.swayOn = AppState.swayOn
    window.strength = AppState.depth
  }

  private fun showLibrary() {
    barEntity?.destroy()
    barEntity = null
    window.hide()
    if (libraryEntity == null) {
      libraryEntity =
          Entity.create(Panel(R.id.library_panel), Transform(Pose(LIBRARY_POS)))
    }
    AppState.screen = AppState.Screen.Library
  }

  private fun showViewer() {
    libraryEntity?.destroy()
    libraryEntity = null
    if (barEntity == null) {
      barEntity = Entity.create(Panel(R.id.viewer_bar_panel), Transform(Pose(BAR_POS)))
    }
    AppState.screen = AppState.Screen.Viewer
  }

  private fun openPhoto(index: Int) {
    if (index !in AppState.photos.indices || AppState.loading) return
    val item = AppState.photos[index]
    AppState.loading = true
    loadJob?.cancel()
    loadJob =
        activityScope.launch {
          try {
            val built =
                withContext(Dispatchers.Default) {
                  PhotoBuilder.build(
                      this@Cyote3DActivity,
                      item,
                      window.windowWidthFor(item.aspect),
                  )
                }
            AppState.current = index
            AppState.depth = item.strength
            window.show(built)
            showViewer()
          } catch (e: Throwable) {
            Log.e(TAG, "could not open ${item.name}", e)
            AppState.notice = "Could not open ${item.name}"
          } finally {
            AppState.loading = false
          }
        }
  }

  private fun stepPhoto(delta: Int) {
    val n = AppState.photos.size
    if (n == 0) return
    openPhoto((AppState.current + delta + n) % n)
  }

  private fun closeViewer() {
    loadJob?.cancel()
    AppState.loading = false
    AppState.current = -1
    showLibrary()
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          ComposeViewPanelRegistration(
              R.id.library_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { LibraryPanel() } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = LIBRARY_W, height = LIBRARY_H),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(dpPerMeter = DP_PER_METER),
                )
              },
          ),
          ComposeViewPanelRegistration(
              R.id.viewer_bar_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { ViewerBar() } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = BAR_W, height = BAR_H),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(dpPerMeter = DP_PER_METER),
                )
              },
          ),
      )
}
