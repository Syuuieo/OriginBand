// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider as CouiDivider
import com.itos.coui.layout.Item as CouiItem
import com.itos.coui.layout.Scaffold as CouiScaffold
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.LargeTopAppBar as CouiLargeTopAppBar
import com.itos.coui.topappbar.TopAppBar as CouiTopAppBar
import com.itos.coui.topappbar.TopAppBarButton as CouiTopAppBarButton

/** Colour role of a chip, notice card, or status line. */
enum class Tone { POSITIVE, ACTIVE, NEUTRAL, ATTENTION }

/** Position of a setup step relative to the current one. */
enum class StepState { DONE, CURRENT, LATER }

@Composable
fun OriginBandScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    largeTopBar: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val overscrollEffect = rememberCouiOverscrollEffect()
    val behavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        canScroll = { scroll.maxValue > 0 },
    )
    CouiScaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (largeTopBar) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier,
            ),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
        topBar = {
            val navIcon: (@Composable () -> Unit)? = if (onBack != null) {
                {
                    CouiTopAppBarButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                }
            } else {
                null
            }
            if (largeTopBar) {
                CouiLargeTopAppBar(
                    title = { Text(text = title) },
                    navigationIcon = navIcon ?: {},
                    scrollBehavior = behavior,
                )
            } else {
                CouiTopAppBar(
                    title = { Text(text = title) },
                    navigationIcon = navIcon ?: {},
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = if (largeTopBar) {
            remember(behavior.state, innerPadding, density) {
                behavior.state.contentPadding(innerPadding, density)
            }
        } else {
            innerPadding
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .couiVerticalScroll(scroll, overscrollEffect = overscrollEffect)
                .padding(contentPadding)
                .consumeWindowInsets(innerPadding)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    CouiText(
        text = text,
        kind = CouiTextKind.GroupTitle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!title.isNullOrBlank()) {
                CouiText(
                    text = title,
                    kind = CouiTextKind.GroupTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun NoticeCard(
    tone: Tone,
    message: String,
    actionLabel: String,
    filled: Boolean = false,
    onAction: () -> Unit,
) {
    val container = toneContainerColor(tone)
    val onContainer = toneOnContainerColor(tone)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer,
            )
            if (filled) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = CircleShape,
                ) {
                    Text(text = actionLabel)
                }
            } else {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, onContainer.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onContainer),
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val container = toneContainerColor(tone)
    val content = toneOnContainerColor(tone)
    val dot = toneForegroundColor(tone)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

@Composable
fun StepRow(index: Int, title: String, state: StepState) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            StepState.DONE -> Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(15.dp),
                )
            }
            StepState.CURRENT -> Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onPrimary,
                )
            }
            StepState.LATER -> Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.dp, colors.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (state == StepState.CURRENT) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (state == StepState.CURRENT) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
fun NavRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    CouiItem(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
        summary = if (!subtitle.isNullOrBlank()) {
            {
                CouiText(
                    text = subtitle,
                    kind = CouiTextKind.ItemSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        CouiText(
            text = title,
            kind = CouiTextKind.ItemTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CouiText(
                text = title,
                kind = CouiTextKind.ItemTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CouiText(
                text = subtitle,
                kind = CouiTextKind.ItemSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CouiSwitch(
            checked = checked,
            onCheckedChange = null,
            interactionSource = source,
            onDragCheckedChange = onCheckedChange,
        )
    }
}

@Composable
fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label)
    }
}

@Composable
fun SecondaryButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label)
    }
}

@Composable
fun QuietTextButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        shape = CircleShape,
    ) {
        Text(text = label)
    }
}

@Composable
fun DangerOutlinedButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = CircleShape,
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) errorColor else errorColor.copy(alpha = 0.35f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = errorColor),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label)
    }
}

@Composable
fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
fun CardDivider() {
    CouiDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissLabel)
            }
        },
    )
}

@Composable
fun InfoDialog(
    title: String,
    message: String,
    confirmLabel: String = "好的",
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = confirmLabel)
            }
        },
    )
}

@Composable
fun toneContainerColor(tone: Tone): Color = when (tone) {
    Tone.POSITIVE -> MaterialTheme.colorScheme.primaryContainer
    Tone.ACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    Tone.ATTENTION -> MaterialTheme.colorScheme.errorContainer
}

@Composable
fun toneOnContainerColor(tone: Tone): Color = when (tone) {
    Tone.POSITIVE -> MaterialTheme.colorScheme.onPrimaryContainer
    Tone.ACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.ATTENTION -> MaterialTheme.colorScheme.onErrorContainer
}

@Composable
fun toneForegroundColor(tone: Tone): Color = when (tone) {
    Tone.POSITIVE -> MaterialTheme.colorScheme.primary
    Tone.ACTIVE -> MaterialTheme.colorScheme.tertiary
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.ATTENTION -> MaterialTheme.colorScheme.error
}
