package com.example.konabess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.konabess.model.DetectedDtb
import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuPowerBin
import com.example.konabess.model.KnownRpmhLabels
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NominalAmber
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateCardHighlight
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryCyanDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UndervoltEmerald
import com.example.ui.theme.UndervoltEmeraldDim

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareTelemetryHeader(
    chipProfile: DynamicChipProfile,
    bins: List<GpuPowerBin>,
    selectedBinIndex: Int,
    detectedDtbs: List<DetectedDtb>,
    selectedDtbId: Int,
    hasRootAccess: Boolean,
    activePartition: String,
    statusMessage: String?,
    isStatusSuccess: Boolean,
    onDismissStatus: () -> Unit,
    onSelectDtb: (Int) -> Unit,
    onSelectBin: (Int) -> Unit,
    onUnpackDevice: () -> Unit,
    onSaveTable: () -> Unit,
    onBackupImage: () -> Unit,
    onRepackAndFlash: () -> Unit,
    onRestoreDefaults: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Main Silicon Telemetry Banner Card with custom Canvas circuit grid & waveform
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val gridStep = 28.dp.toPx()
                        val gridColor = Color(0xFF1A2333).copy(alpha = 0.55f)
                        var x = 0f
                        while (x < size.width) {
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1f
                            )
                            x += gridStep
                        }
                        var y = 0f
                        while (y < size.height) {
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f
                            )
                            y += gridStep
                        }
                        val path = Path().apply {
                            moveTo(size.width * 0.62f, size.height * 0.18f)
                            lineTo(size.width * 0.72f, size.height * 0.18f)
                            lineTo(size.width * 0.72f, size.height * 0.36f)
                            lineTo(size.width * 0.82f, size.height * 0.36f)
                            lineTo(size.width * 0.82f, size.height * 0.56f)
                            lineTo(size.width * 0.92f, size.height * 0.56f)
                            lineTo(size.width * 0.92f, size.height * 0.76f)
                            lineTo(size.width * 0.99f, size.height * 0.76f)
                        }
                        drawPath(
                            path = path,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    TelemetryCyan.copy(alpha = 0.18f),
                                    UndervoltEmerald.copy(alpha = 0.28f)
                                )
                            ),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TelemetryCyanDim),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeveloperBoard,
                                    contentDescription = "GPU Processor Identity",
                                    tint = TelemetryCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "SAGEKONABESS",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = if (hasRootAccess) UndervoltEmeraldDim else SlateCardHighlight,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (hasRootAccess) UndervoltEmerald.copy(alpha = 0.5f) else BorderHairline
                                        )
                                    ) {
                                        Text(
                                            text = if (hasRootAccess) "ROOT SHELL" else "NO ROOT",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (hasRootAccess) UndervoltEmerald else TelemetryCyan,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = chipProfile.modelName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Dynamic ChipDefinition telemetry pills (showing exact existing read voltage levels)
                    if (chipProfile.readVoltageLevels.isNotEmpty()) {
                        Surface(
                            color = ObsidianBackground.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "STOCK LEVELS READ (${chipProfile.levelPropertyKey})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TelemetryCyan
                                    )
                                    Text(
                                        text = activePartition,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    chipProfile.readVoltageLevels.forEach { readLevel ->
                                        Surface(
                                            color = SlateElevated,
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, BorderHairline)
                                        ) {
                                            Text(
                                                text = readLevel.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Core KonaBess Image Operations Bar (Re-Read / Backup / Save / Repack & Flash)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onUnpackDevice,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderHairline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SlateElevated,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(0.95f)
                                .height(48.dp)
                                .testTag("unpack_device_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Unpack Image",
                                tint = TelemetryCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Re-Read",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = onBackupImage,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderHairline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SlateElevated,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(0.95f)
                                .height(48.dp)
                                .testTag("backup_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Backup,
                                contentDescription = "Backup Old Image",
                                tint = NominalAmber,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Backup",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = onSaveTable,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderHairline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SlateElevated,
                                contentColor = UndervoltEmerald
                            ),
                            modifier = Modifier
                                .weight(0.95f)
                                .height(48.dp)
                                .testTag("save_table_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save GPU Freq Table",
                                tint = UndervoltEmerald,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Save",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = onRepackAndFlash,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TelemetryCyan,
                                contentColor = Color(0xFF002229)
                            ),
                            modifier = Modifier
                                .weight(1.25f)
                                .height(48.dp)
                                .testTag("repack_flash_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = "Repack and Flash",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Flash",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    // Load / Restore Backed-Up Image Button (Allows choosing Stock or Named Backups)
                    OutlinedButton(
                        onClick = onRestoreDefaults,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NominalAmber.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = NominalAmber
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("restore_defaults_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Load or Restore Backed-Up Boot Image",
                            tint = NominalAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Load / Restore Backed-Up Image",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Multiple DTB Selector Row (when device boot/vendor_boot has multiple compatible DTBs)
        if (detectedDtbs.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DTB:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                detectedDtbs.forEach { dtb ->
                    val isSelected = dtb.id == selectedDtbId
                    val activeTag = if (dtb.isActiveBootDtb) " [ACTIVE]" else ""
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDtb(dtb.id) },
                        label = {
                            Text(
                                text = "DTB #${dtb.id}: ${dtb.description}$activeTag",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = UndervoltEmeraldDim,
                            selectedLabelColor = UndervoltEmerald,
                            containerColor = SlateSurface,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("dtb_chip_${dtb.id}")
                    )
                }
            }
        }

        // GPU Speed Bin Selector Row (using exact KonaBessStr.convert_bins labels)
        if (bins.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = "GPU Speed Bins",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "GPU BIN:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                bins.forEach { bin ->
                    val isSelected = bin.binIndex == selectedBinIndex
                    val modifiedCount = bin.modifiedRowCount
                    val binTitle = KnownRpmhLabels.formatBinDisplayName(bin.binIndex, chipProfile.chipType)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectBin(bin.binIndex) },
                        label = {
                            val badge = if (modifiedCount > 0) " • $modifiedCount mod" else ""
                            Text(
                                text = "$binTitle (${bin.levels.size} steps$badge)",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TelemetryCyanDim,
                            selectedLabelColor = TelemetryCyan,
                            containerColor = SlateSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = BorderHairline,
                            selectedBorderColor = TelemetryCyan
                        ),
                        modifier = Modifier.testTag("bin_chip_${bin.binIndex}")
                    )
                }
            }
        }

        // Dismissible Status Feedback Banner
        AnimatedVisibility(visible = !statusMessage.isNullOrBlank()) {
            if (!statusMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isStatusSuccess) UndervoltEmeraldDim else Color(0xFF3B1A1E),
                    border = BorderStroke(
                        1.dp,
                        if (isStatusSuccess) UndervoltEmerald.copy(alpha = 0.6f) else NominalAmber
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("status_feedback_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Status",
                                tint = if (isStatusSuccess) UndervoltEmerald else NominalAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = onDismissStatus,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss notification",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
