package com.cyote3d.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyote3d.app.AppState

/** Floating pill under the photo (M0 subset of BUILD_PLAN.md §4, screen 3): Back, prev/next, Depth, Sway. */
@Composable
fun ViewerBar() {
  val shape = RoundedCornerShape(Cyote.RadiusPill)
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Row(
        Modifier.clip(shape)
            .background(Cyote.PanelBackground)
            .border(BorderStroke(1.dp, Cyote.Stroke), shape)
            .padding(horizontal = Cyote.S12, vertical = Cyote.S8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Cyote.S4),
    ) {
      IconButton(IconKind.Back, { AppState.onClose() })
      Spacer(Modifier.width(Cyote.S4))
      VerticalDivider()
      Spacer(Modifier.width(Cyote.S4))
      IconButton(IconKind.Prev, { AppState.onStep(-1) }, enabled = !AppState.loading)
      IconButton(IconKind.Next, { AppState.onStep(+1) }, enabled = !AppState.loading)
      Spacer(Modifier.width(Cyote.S4))
      VerticalDivider()
      Spacer(Modifier.width(Cyote.S4))
      IconButton(IconKind.Minus, { AppState.depth = (AppState.depth - 0.05f).coerceAtLeast(0.1f) })
      Text(
          "Depth ${"%.2f".format(AppState.depth)}",
          style = Cyote.Label,
          textAlign = TextAlign.Center,
          modifier = Modifier.width(88.dp),
      )
      IconButton(IconKind.Plus, { AppState.depth = (AppState.depth + 0.05f).coerceAtMost(0.95f) })
      Spacer(Modifier.width(Cyote.S4))
      VerticalDivider()
      Spacer(Modifier.width(Cyote.S4))
      IconButton(IconKind.Sway, { AppState.swayOn = !AppState.swayOn }, active = AppState.swayOn)
    }
  }
}
