package com.cyote3d.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyote3d.app.AppState
import com.cyote3d.app.PhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Library panel: 1200 x 760 dp (BUILD_PLAN.md §4, screen 1). */
@Composable
fun LibraryPanel() {
  val shape = RoundedCornerShape(Cyote.RadiusPanel)
  Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .clip(shape)
            .background(Cyote.PanelBackground)
            .border(BorderStroke(1.dp, Cyote.Stroke), shape)
            .padding(Cyote.S24),
    ) {
      TopBar()
      Spacer(Modifier.height(Cyote.S16))
      if (AppState.photos.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { EmptyState() }
      } else {
        PhotoGrid(Modifier.weight(1f).fillMaxWidth())
      }
      Spacer(Modifier.height(Cyote.S12))
      Text(
          "Processed on this headset. Nothing is uploaded.",
          style = Cyote.Caption,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
      )
    }
    NoticePill(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
    if (AppState.loading) {
      Box(
          Modifier.fillMaxSize().clip(shape).background(Color(0x990E0F13)),
          contentAlignment = Alignment.Center,
      ) {
        Text("Opening photo…", style = Cyote.Title.copy(color = Cyote.TextPrimary))
      }
    }
  }
}

@Composable
private fun TopBar() {
  Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
    LogoMark()
    Spacer(Modifier.width(Cyote.S12))
    Text("Cyote3D", style = Cyote.Title)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Import", IconKind.Import, { AppState.notice = "Importing from this headset arrives in the next milestone" })
    Spacer(Modifier.width(Cyote.S12))
    GhostButton("From phone", IconKind.Phone, { AppState.notice = "Phone upload arrives in a later milestone" })
  }
}

/** Small cube mark in the accent colour. */
@Composable
private fun LogoMark() {
  androidx.compose.foundation.Canvas(Modifier.size(32.dp)) {
    val s = size.width
    fun poly(color: Color, vararg p: Pair<Float, Float>) {
      val path = androidx.compose.ui.graphics.Path()
      p.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
      path.close()
      drawPath(path, color)
    }
    poly(Cyote.Accent, 0.5f to 0.06f, 0.92f to 0.28f, 0.92f to 0.72f, 0.5f to 0.94f, 0.08f to 0.72f, 0.08f to 0.28f)
    poly(Cyote.AccentPressed, 0.5f to 0.5f, 0.92f to 0.28f, 0.92f to 0.72f, 0.5f to 0.94f)
    poly(Cyote.TextPrimary.copy(alpha = 0.9f), 0.5f to 0.06f, 0.92f to 0.28f, 0.5f to 0.5f, 0.08f to 0.28f)
  }
}

@Composable
private fun PhotoGrid(modifier: Modifier) {
  LazyVerticalGrid(
      columns = GridCells.Fixed(4),
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(Cyote.S12),
      verticalArrangement = Arrangement.spacedBy(Cyote.S12),
  ) {
    itemsIndexed(AppState.photos, key = { _, p -> p.name }) { index, photo ->
      PhotoTile(photo) { AppState.onOpen(index) }
    }
  }
}

@Composable
private fun PhotoTile(photo: PhotoItem, onClick: () -> Unit) {
  val thumb = rememberAssetBitmap("${photo.dir}/thumb.jpg")
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val shape = RoundedCornerShape(Cyote.RadiusThumb)
  Box(
      Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .clip(shape)
          .background(Cyote.Surface)
          .border(BorderStroke(1.dp, if (hovered) Cyote.Accent else Cyote.Stroke), shape)
          .hoverable(source)
          .clickable(interactionSource = source, indication = null, onClick = onClick),
  ) {
    thumb?.let {
      Image(
          bitmap = it,
          contentDescription = photo.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    }
    Box(
        Modifier.align(Alignment.TopStart)
            .padding(Cyote.S8)
            .clip(RoundedCornerShape(Cyote.RadiusPill))
            .background(Color(0xCC0E0F13))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
      Text("3D", style = Cyote.Label.copy(color = Cyote.Accent))
    }
  }
}

@Composable
private fun EmptyState() {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Bring in a photo to make it 3D", style = Cyote.Body.copy(color = Cyote.TextSecondary))
    Spacer(Modifier.height(Cyote.S24))
    Row(horizontalArrangement = Arrangement.spacedBy(Cyote.S12)) {
      PrimaryButton("Import", IconKind.Import, { AppState.notice = "Importing from this headset arrives in the next milestone" })
      GhostButton("From phone", IconKind.Phone, { AppState.notice = "Phone upload arrives in a later milestone" })
    }
  }
}

@Composable
private fun NoticePill(modifier: Modifier) {
  val text = AppState.notice
  LaunchedEffect(text) {
    if (text != null) {
      delay(3000)
      if (AppState.notice == text) AppState.notice = null
    }
  }
  AnimatedVisibility(
      visible = text != null,
      modifier = modifier,
      enter = fadeIn(tween(Cyote.PanelMs)),
      exit = fadeOut(tween(Cyote.PanelMs)),
  ) {
    Box(
        Modifier.clip(RoundedCornerShape(Cyote.RadiusPill))
            .background(Cyote.Elevated)
            .border(BorderStroke(1.dp, Cyote.Stroke), RoundedCornerShape(Cyote.RadiusPill))
            .padding(horizontal = Cyote.S24, vertical = Cyote.S12),
    ) {
      Text(text ?: "", style = Cyote.Label)
    }
  }
}

@Composable
private fun rememberAssetBitmap(path: String): ImageBitmap? {
  val context = LocalContext.current
  return produceState<ImageBitmap?>(initialValue = null, path) {
        value =
            withContext(Dispatchers.IO) {
              runCatching {
                    context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                  }
                  .getOrNull()
            }
      }
      .value
}
