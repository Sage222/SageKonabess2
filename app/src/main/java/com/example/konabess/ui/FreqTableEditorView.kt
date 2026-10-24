package com.example.konabess.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.konabess.core.VoltageStepShifter
import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuPowerBin
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.LockedIndigo
import com.example.ui.theme.LockedIndigoDim
import com.example.ui.theme.NominalAmber
import com.example.ui.theme.NominalAmberDim
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateCardHighlight
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryCyanDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TurboCoral
import com.example.ui.theme.TurboCoralDim
import com.example.ui.theme.UndervoltEmerald
import com.example.ui.theme.UndervoltEmeraldDim

@Composable
fun FreqTableEditorView(
    bin: GpuPowerBin,
    chipProfile: DynamicChipProfile,
    netStepDrops: Int,
    onDropVoltageStep: () -> Unit,
    onRaiseVoltageStep: () -> Unit,
    onResetToStock: () -> Unit,
    onAddNewFreqStep: () -> Unit,
    onSelectFreqRow: (GpuFreqRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalRows = bin.levels.size
    val firstLockedIndex = (totalRows - VoltageStepShifter.PROTECTED_BOTTOM_ROW_COUNT).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // One-Tap Voltage Step Quick-Tuner Bar
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SlateSurface,
            border = BorderStroke(
                1.dp,
                if (netStepDrops > 0) UndervoltEmerald.copy(alpha = 0.65f) else BorderHairline
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ONE-TAP VOLTAGE STEP DROPPER",
                            style = MaterialTheme.typography.labelLarge,
                            color = UndervoltEmerald
                        )
                        Text(
                            text = "Drops each frequency to the next lower frequency's voltage level • Bottom 2 unchanged",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    if (netStepDrops > 0) {
                        Surface(
                            color = UndervoltEmeraldDim,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, UndervoltEmerald)
                        ) {
                            Text(
                                text = "-$netStepDrops STEP${if (netStepDrops > 1) "S" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = UndervoltEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Primary One-Tap Drop Button
                Button(
                    onClick = onDropVoltageStep,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UndervoltEmerald,
                        contentColor = Color(0xFF002411)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("drop_voltage_step_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = "Drop Voltage Steps by 1 Level",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Drop Voltage Steps (-1 Step)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Companion Quick Actions Row: Step Up (+1), Reset to Stock, + New Freq
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRaiseVoltageStep,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderHairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("raise_voltage_step_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Step Up +1",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Step Up (+1)",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onResetToStock,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderHairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("reset_stock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Reset to Stock Table",
                            tint = NominalAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Reset Stock",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onAddNewFreqStep,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderHairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = TelemetryCyan
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier
                            .weight(0.95f)
                            .height(48.dp)
                            .testTag("new_freq_step_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Frequency Step",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "New Step",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Frequency & Voltage Table Rows
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("freq_table_lazy_column"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(
                items = bin.levels,
                key = { _, row -> row.id }
            ) { index, row ->
                val isBottomTwoLocked = index >= firstLockedIndex
                val stockRow = bin.stockLevels.getOrNull(index)
                    ?: bin.stockLevels.firstOrNull { it.frequencyHz == row.frequencyHz }
                val isInitialLevel = index == bin.initialPwrLevel
                val nextRowDown = bin.levels.getOrNull(index + 1)

                FreqTableRowCard(
                    row = row,
                    stockRow = stockRow,
                    nextLowerFreqRow = nextRowDown,
                    chipProfile = chipProfile,
                    isBottomTwoLocked = isBottomTwoLocked,
                    isInitialPwrLevel = isInitialLevel,
                    onClick = { onSelectFreqRow(row) }
                )
            }
        }
    }
}

@Composable
private fun FreqTableRowCard(
    row: GpuFreqRow,
    stockRow: GpuFreqRow?,
    nextLowerFreqRow: GpuFreqRow?,
    chipProfile: DynamicChipProfile,
    isBottomTwoLocked: Boolean,
    isInitialPwrLevel: Boolean,
    onClick: () -> Unit
) {
    val currentLabel = chipProfile.resolveLabel(row.voltageLevelCode)
    val stockCode = stockRow?.voltageLevelCode
    val stockLabel = stockCode?.let { chipProfile.resolveLabel(it) }
    val isVoltageModified = stockCode != null && stockCode != row.voltageLevelCode
    val isUndervolted = stockCode != null && row.voltageLevelCode < stockCode

    val borderColor by animateColorAsState(
        targetValue = when {
            isVoltageModified && isUndervolted -> UndervoltEmerald.copy(alpha = 0.7f)
            isVoltageModified -> NominalAmber.copy(alpha = 0.7f)
            isBottomTwoLocked -> LockedIndigo.copy(alpha = 0.45f)
            else -> BorderHairline
        },
        label = "rowBorderColor"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isBottomTwoLocked) ObsidianBackground else SlateSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("freq_row_${row.levelIndex}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Index badge + Frequency MHz & Hz
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isBottomTwoLocked) LockedIndigoDim else SlateCardHighlight
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "@${row.levelIndex}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isBottomTwoLocked) LockedIndigo else TelemetryCyan
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = row.frequencyFormattedMHz,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            if (isInitialPwrLevel) {
                                Surface(
                                    color = TelemetryCyanDim,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "INIT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TelemetryCyan,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            if (isBottomTwoLocked) {
                                Surface(
                                    color = LockedIndigoDim,
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, LockedIndigo.copy(alpha = 0.5f)),
                                    modifier = Modifier.testTag("locked_badge_${row.levelIndex}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Bottom 2 Frequency Protected",
                                            tint = LockedIndigo,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            text = "BOTTOM 2 LOCKED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LockedIndigo
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "${row.frequencyHz} Hz • Bus ${row.busFreq} [${row.busMin}..${row.busMax}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                // Right side: Active Voltage Level Pill + Edit affordance
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val (pillBg, pillText) = resolveVoltageBadgeColors(currentLabel, isUndervolted)
                    Surface(
                        color = pillBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, pillText.copy(alpha = 0.55f)),
                        modifier = Modifier.testTag("voltage_pill_${row.levelIndex}")
                    ) {
                        Text(
                            text = currentLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = pillText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit ${row.frequencyFormattedMHz}",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Bottom telemetry row: Visual Diff Badge (when modified in session) & Next Step Preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isVoltageModified && stockLabel != null) {
                    Surface(
                        color = if (isUndervolted) UndervoltEmeraldDim else NominalAmberDim,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag("diff_badge_${row.levelIndex}")
                    ) {
                        Text(
                            text = "DIFF: $stockLabel → $currentLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUndervolted) UndervoltEmerald else NominalAmber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (!isBottomTwoLocked && nextLowerFreqRow != null) {
                    val nextLabel = chipProfile.resolveLabel(nextLowerFreqRow.voltageLevelCode)
                    Text(
                        text = "Next ↓ ${nextLowerFreqRow.frequencyMHz}MHz ($nextLabel)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else if (isBottomTwoLocked) {
                    Text(
                        text = "Unchanged on Step Drop",
                        style = MaterialTheme.typography.labelSmall,
                        color = LockedIndigo
                    )
                }
            }
        }
    }
}

private fun resolveVoltageBadgeColors(label: String, isUndervolted: Boolean): Pair<Color, Color> {
    if (isUndervolted) {
        return Pair(UndervoltEmeraldDim, UndervoltEmerald)
    }
    return when {
        label.contains("TURBO") -> Pair(TurboCoralDim, TurboCoral)
        label.contains("NOM") -> Pair(NominalAmberDim, NominalAmber)
        label.contains("SVS") -> Pair(TelemetryCyanDim, TelemetryCyan)
        else -> Pair(SlateElevated, TextSecondary)
    }
}
