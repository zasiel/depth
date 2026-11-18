package com.cyote3d.app

import android.content.res.AssetManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/** One photo folder (see BUILD_PLAN.md §3). M0 only has the bundled samples under assets/samples/. */
data class PhotoItem(
    val name: String,
    val width: Int,
    val height: Int,
    /** grading.subject from meta.json: the closeness that sits exactly on the window plane. */
    val subject: Float,
    /** grading.P: default depth strength. */
    val strength: Float,
) {
  val aspect: Float
    get() = width.toFloat() / height

  val dir: String
    get() = "samples/$name"
}

object PhotoCatalog {
  private const val TAG = "Cyote3D"

  /** Reads assets/samples/manifest.json (copied from docs/photos/manifest.json at build time). */
  fun loadSamples(assets: AssetManager): List<PhotoItem> =
      try {
        val text = assets.open("samples/manifest.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        List(arr.length()) { i ->
          val o = arr.getJSONObject(i)
          val g = o.optJSONObject("grading")
          PhotoItem(
              name = o.getString("name"),
              width = o.getInt("width"),
              height = o.getInt("height"),
              subject = g?.optDouble("subject", 0.5)?.toFloat() ?: 0.5f,
              strength = g?.optDouble("P", 0.8)?.toFloat() ?: 0.8f,
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "No bundled sample photos: ${e.message}")
        emptyList()
      }
}

/** UI-visible app state shared by the Compose panels and the scene code in [Cyote3DActivity]. */
object AppState {
  enum class Screen {
    Library,
    Viewer,
  }

  val photos = mutableStateListOf<PhotoItem>()
  var screen by mutableStateOf(Screen.Library)
  var current by mutableIntStateOf(-1)
  var loading by mutableStateOf(false)
  var swayOn by mutableStateOf(true)
  var depth by mutableFloatStateOf(0.8f)
  var notice by mutableStateOf<String?>(null)

  // Wired up by the activity.
  var onOpen: (Int) -> Unit = {}
  var onClose: () -> Unit = {}
  var onStep: (Int) -> Unit = {}
}
