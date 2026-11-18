package com.cyote3d.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ease = FastOutSlowInEasing

/** Hover = surface lightens + 1 dp accent stroke; press = accent-soft. 160 ms ease-out. */
@Composable
private fun interactionColors(
    source: MutableInteractionSource,
    rest: Color,
    hover: Color,
    pressed: Color,
): Pair<Color, Boolean> {
  val isHovered by source.collectIsHoveredAsState()
  val isPressed by source.collectIsPressedAsState()
  val target = if (isPressed) pressed else if (isHovered) hover else rest
  val animated by animateColorAsState(target, tween(Cyote.PressMs, easing = ease), label = "bg")
  return animated to (isHovered || isPressed)
}

@Composable
fun PrimaryButton(text: String, icon: IconKind?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val source = remember { MutableInteractionSource() }
  val (bg, _) = interactionColors(source, Cyote.Accent, Cyote.Accent, Cyote.AccentPressed)
  Row(
      modifier
          .height(Cyote.HitTarget)
          .clip(RoundedCornerShape(Cyote.RadiusButton))
          .background(bg)
          .hoverable(source)
          .clickable(interactionSource = source, indication = null, onClick = onClick)
          .padding(horizontal = Cyote.S16),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Cyote.S8),
  ) {
    if (icon != null) LineIcon(icon, Cyote.TextPrimary)
    Text(text, style = Cyote.Label.copy(color = Cyote.TextPrimary))
  }
}

@Composable
fun GhostButton(text: String, icon: IconKind?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val source = remember { MutableInteractionSource() }
  val (bg, highlighted) = interactionColors(source, Color.Transparent, Cyote.Surface, Cyote.AccentSoft)
  val stroke = if (highlighted) Cyote.Accent else Cyote.Stroke
  Row(
      modifier
          .height(Cyote.HitTarget)
          .clip(RoundedCornerShape(Cyote.RadiusButton))
          .background(bg)
          .border(BorderStroke(1.dp, stroke), RoundedCornerShape(Cyote.RadiusButton))
          .hoverable(source)
          .clickable(interactionSource = source, indication = null, onClick = onClick)
          .padding(horizontal = Cyote.S16),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Cyote.S8),
  ) {
    if (icon != null) LineIcon(icon, Cyote.TextSecondary)
    Text(text, style = Cyote.Label.copy(color = Cyote.TextPrimary))
  }
}

/** Square icon button used in the viewer's pill bar. 56 dp (>= 48 dp hit target). */
@Composable
fun IconButton(
    icon: IconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
  val source = remember { MutableInteractionSource() }
  val rest = if (active) Cyote.AccentSoft else Color.Transparent
  val (bg, highlighted) = interactionColors(source, rest, Cyote.Elevated, Cyote.AccentSoft)
  val shape = RoundedCornerShape(Cyote.RadiusButton)
  val tint = if (!enabled) Cyote.TextMuted else if (active) Cyote.Accent else Cyote.TextPrimary
  Box(
      modifier
          .size(56.dp)
          .clip(shape)
          .background(bg)
          .then(if (highlighted && enabled) Modifier.border(BorderStroke(1.dp, Cyote.Accent), shape) else Modifier)
          .hoverable(source)
          .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    LineIcon(icon, tint, size = 24.dp)
  }
}

@Composable
fun VerticalDivider(height: Dp = 28.dp) {
  Spacer(Modifier.width(1.dp).height(height).background(Cyote.Stroke))
}
