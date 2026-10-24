package com.example.konabess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuOppVoltRow
import com.example.konabess.model.KnownRpmhLabels
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.NominalAmber
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryCyanDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TurboCoral
import com.example.ui.theme.UndervoltEmerald
import com.example.ui.theme.UndervoltEmeraldDim
import com.example.ui.theme.NominalAmberDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditFreqRowDialog(
    row: GpuFreqRow,
    chipProfile: DynamicChipProfile,
    isInitialPwrLevel: Boolean,
    onDismiss: () -> Unit,
    onSave: (GpuFreqRow) -> Unit,
    onDuplicate: (GpuFreqRow) -> Unit,
    onDelete: (String) -> Unit,
    onSetInitialLevel: (Int) -> Unit
) {
    var freqMhzText by remember(row.id) { mutableStateOf(row.frequencyMHz.toString()) }
    var selectedVoltageCode by remember(row.id) { mutableIntStateOf(row.voltageLevelCode) }
    var customVoltageCodeText by remember(row.id) { mutableStateOf("") }
    var showAllChipLevels by remember(row.id) { mutableStateOf(true) }
    var busFreqText by remember(row.id) { mutableStateOf(row.busFreq.toString()) }
    var busMinText by remember(row.id) { mutableStateOf(row.busMin.toString()) }
    var busMaxText by remember(row.id) { mutableStateOf(row.busMax.toString()) }
    var acdHexText by remember(row.id) { mutableStateOf(row.acdLevelHex.orEmpty()) }

    val displayedLevels = if (showAllChipLevels) {
        chipProfile.chipFamilyManualLevels
    } else {
        chipProfile.readVoltageLevels
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("edit_freq_row_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Edit GPU Level @${row.levelIndex} (${row.frequencyFormattedMHz})",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAllChipLevels) {
                            "All Chip RPMh Levels (${chipProfile.levelPropertyKey}):"
                        } else {
                            "Stock Levels Read from DTB (${chipProfile.levelPropertyKey}):"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showAllChipLevels = !showAllChipLevels }
                    ) {
                        Text(
                            text = if (showAllChipLevels) "Stock Only" else "+ Manual Levels",
                            color = TelemetryCyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    displayedLevels.forEach { level ->
                        val isSelected = selectedVoltageCode == level.code && customVoltageCodeText.isBlank()
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedVoltageCode = level.code
                                customVoltageCodeText = ""
                            },
                            label = {
                                Text(
                                    text = "${level.label} (${level.code})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyanDim,
                                selectedLabelColor = TelemetryCyan,
                                containerColor = SlateElevated,
                                labelColor = TextPrimary
                            ),
                            modifier = Modifier.testTag("dialog_volt_chip_${level.label}")
                        )
                    }
                }

                OutlinedTextField(
                    value = freqMhzText,
                    onValueChange = { freqMhzText = it },
                    label = { Text("Frequency (MHz) [qcom,gpu-freq]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_freq_mhz")
                )

                OutlinedTextField(
                    value = customVoltageCodeText,
                    onValueChange = { customVoltageCodeText = it },
                    label = { Text("Optional Raw Level Override (Dec/Hex or RPMh name)") },
                    placeholder = { Text("Current: ${chipProfile.resolveLabel(selectedVoltageCode)}") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_custom_volt_code")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = busFreqText,
                        onValueChange = { busFreqText = it },
                        label = { Text("Bus Freq") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = busMinText,
                        onValueChange = { busMinText = it },
                        label = { Text("Bus Min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = busMaxText,
                        onValueChange = { busMaxText = it },
                        label = { Text("Bus Max") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = acdHexText,
                    onValueChange = { acdHexText = it },
                    label = { Text("ACD Level Hex [qcom,acd-level] (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isInitialPwrLevel) {
                        OutlinedButton(
                            onClick = {
                                onSetInitialLevel(row.levelIndex)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = "Set Initial Power Level",
                                tint = TelemetryCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Set Init", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    OutlinedButton(
                        onClick = { onDuplicate(row) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("duplicate_freq_row_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Step",
                            tint = UndervoltEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clone", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { onDelete(row.id) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delete_freq_row_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Step",
                            tint = TurboCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", style = MaterialTheme.typography.labelSmall, color = TurboCoral)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val parsedMhz = freqMhzText.toLongOrNull() ?: row.frequencyMHz
                            val resolvedVoltCode = if (customVoltageCodeText.isNotBlank()) {
                                KnownRpmhLabels.encodeLabelToCodeIfKnown(customVoltageCodeText, chipProfile.chipType)
                                    ?: selectedVoltageCode
                            } else {
                                selectedVoltageCode
                            }
                            onSave(
                                row.copy(
                                    frequencyHz = parsedMhz * 1_000_000L,
                                    voltageLevelCode = resolvedVoltCode,
                                    busFreq = busFreqText.toIntOrNull() ?: row.busFreq,
                                    busMin = busMinText.toIntOrNull() ?: row.busMin,
                                    busMax = busMaxText.toIntOrNull() ?: row.busMax,
                                    acdLevelHex = acdHexText.trim().ifEmpty { null }
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = Color(0xFF00242B)
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("save_freq_row_button")
                    ) {
                        Text("Apply Step", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewFreqRowDialog(
    chipProfile: DynamicChipProfile,
    onDismiss: () -> Unit,
    onAddStep: (
        frequencyMHz: Long,
        voltageLevelCode: Int,
        busFreq: Int,
        busMin: Int,
        busMax: Int,
        acdHex: String?
    ) -> Unit
) {
    val defaultCode = chipProfile.readVoltageLevels.lastOrNull()?.code ?: 192
    var freqMhzText by remember { mutableStateOf("750") }
    var selectedVoltageCode by remember { mutableIntStateOf(defaultCode) }
    var showAllChipLevels by remember { mutableStateOf(true) }
    var busFreqText by remember { mutableStateOf("8") }
    var busMinText by remember { mutableStateOf("6") }
    var busMaxText by remember { mutableStateOf("10") }
    var acdHexText by remember { mutableStateOf("") }

    val displayedLevels = if (showAllChipLevels) {
        chipProfile.chipFamilyManualLevels
    } else {
        chipProfile.readVoltageLevels
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new_freq_row_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "New GPU Frequency Step",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = freqMhzText,
                    onValueChange = { freqMhzText = it },
                    label = { Text("Frequency (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_step_freq_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAllChipLevels) "All Chip RPMh Levels:" else "Stock Voltage Levels:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    TextButton(onClick = { showAllChipLevels = !showAllChipLevels }) {
                        Text(
                            text = if (showAllChipLevels) "Stock Only" else "+ Manual Levels",
                            color = TelemetryCyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    displayedLevels.forEach { level ->
                        FilterChip(
                            selected = selectedVoltageCode == level.code,
                            onClick = { selectedVoltageCode = level.code },
                            label = {
                                Text(
                                    text = "${level.label} (${level.code})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyanDim,
                                selectedLabelColor = TelemetryCyan,
                                containerColor = SlateElevated,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = busFreqText,
                        onValueChange = { busFreqText = it },
                        label = { Text("Bus Freq") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = busMinText,
                        onValueChange = { busMinText = it },
                        label = { Text("Bus Min") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = busMaxText,
                        onValueChange = { busMaxText = it },
                        label = { Text("Bus Max") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = acdHexText,
                    onValueChange = { acdHexText = it },
                    label = { Text("ACD Level Hex (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val mhz = freqMhzText.toLongOrNull() ?: 750L
                            onAddStep(
                                mhz,
                                selectedVoltageCode,
                                busFreqText.toIntOrNull() ?: 8,
                                busMinText.toIntOrNull() ?: 6,
                                busMaxText.toIntOrNull() ?: 10,
                                acdHexText.takeIf { it.isNotBlank() }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UndervoltEmerald,
                            contentColor = Color(0xFF002411)
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("confirm_add_freq_button")
                    ) {
                        Text("Insert Step", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditOppVoltRowDialog(
    oppRow: GpuOppVoltRow,
    chipProfile: DynamicChipProfile,
    onDismiss: () -> Unit,
    onSave: (rowId: String, frequencyHz: Long, microvoltOrLevel: Long, peakKbps: Long?) -> Unit
) {
    var freqMhzText by remember(oppRow.id) { mutableStateOf(oppRow.frequencyMHz.toString()) }
    var selectedLevel by remember(oppRow.id) { mutableStateOf(oppRow.microvoltOrLevel) }
    var peakKbpsText by remember(oppRow.id) { mutableStateOf(oppRow.peakKbps?.toString().orEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Edit OPP Voltage Curve (${oppRow.frequencyMHz} MHz)",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = freqMhzText,
                    onValueChange = { freqMhzText = it },
                    label = { Text("OPP Frequency (MHz)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Select Voltage Level (read from existing table):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chipProfile.readVoltageLevels.forEach { level ->
                        FilterChip(
                            selected = selectedLevel == level.code.toLong(),
                            onClick = { selectedLevel = level.code.toLong() },
                            label = {
                                Text(
                                    text = "${level.label} (0x${level.code.toString(16)})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyanDim,
                                selectedLabelColor = TelemetryCyan,
                                containerColor = SlateElevated,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = peakKbpsText,
                    onValueChange = { peakKbpsText = it },
                    label = { Text("Peak Bandwidth kBps [opp-peak-kBps]") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val hz = (freqMhzText.toLongOrNull() ?: oppRow.frequencyMHz) * 1_000_000L
                            onSave(
                                oppRow.id,
                                hz,
                                selectedLevel,
                                peakKbpsText.toLongOrNull()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = Color(0xFF00242B)
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Save OPP Entry", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmFlashDialog(
    activePartition: String,
    modelName: String,
    selectedDtbId: Int,
    modifiedStepsSummary: List<String>,
    onDismiss: () -> Unit,
    onConfirmFlash: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("confirm_flash_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Confirm Repack & Flash",
                    style = MaterialTheme.typography.titleLarge,
                    color = TelemetryCyan,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "This will execute the original KonaBess repack & flash pipeline and verify the partition checksum:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateElevated,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Target: $activePartition",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Device DTB: ${if (selectedDtbId >= 0) "DTB #$selectedDtbId" else "Active DTB"} ($modelName)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "Pipeline: Save .dts → dtc -I dts -O dtb -f → Link DTBs → magiskboot repack → dd flash → SHA-256 readback verify",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelemetryCyan
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateElevated,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (modifiedStepsSummary.isNotEmpty()) {
                                "MODIFIED STEPS TO FLASH (${modifiedStepsSummary.size}):"
                            } else {
                                "TABLE STATUS:"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = UndervoltEmerald,
                            fontWeight = FontWeight.Bold
                        )
                        if (modifiedStepsSummary.isEmpty()) {
                            Text(
                                text = "Current table matches stock DTB values (no modified rows).",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            modifiedStepsSummary.take(10).forEach { diffLine ->
                                Text(
                                    text = "• $diffLine",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                            if (modifiedStepsSummary.size > 10) {
                                Text(
                                    text = "• ...and ${modifiedStepsSummary.size - 10} more step change(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("cancel_flash_button")
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirmFlash,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = Color(0xFF00242B)
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("confirm_flash_now_button")
                    ) {
                        Text("Confirm & Flash", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateNamedBackupDialog(
    activePartition: String,
    modifiedStepsSummary: List<String>,
    onDismiss: () -> Unit,
    onConfirmCreateBackup: (backupName: String, saveModifiedTable: Boolean) -> Unit
) {
    val hasModifiedSteps = modifiedStepsSummary.isNotEmpty()
    var saveModifiedTable by remember { mutableStateOf(hasModifiedSteps) }
    var backupNameText by remember {
        mutableStateOf(if (hasModifiedSteps) "Modified_UV" else "Stock")
    }

    val quickNamePresets = listOf(
        "Stock" to false,
        "Undervolt_1Step" to true,
        "Undervolt_2Steps" to true,
        "Modified_Custom" to true,
        "Daily_Stable" to true
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, NominalAmber.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create_named_backup_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Create Named Boot Backup",
                    style = MaterialTheme.typography.titleLarge,
                    color = NominalAmber,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Name your backup and choose whether to save the untouched Stock boot image or repack & save your current Modified GPU table:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                // Backup Mode Selector: Stock vs Modified
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !saveModifiedTable,
                        onClick = {
                            saveModifiedTable = false
                            if (backupNameText == "Modified_UV") {
                                backupNameText = "Stock"
                            }
                        },
                        label = {
                            Text(
                                text = "Stock Image",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NominalAmberDim,
                            selectedLabelColor = NominalAmber,
                            containerColor = SlateElevated,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("backup_mode_stock_chip")
                    )

                    FilterChip(
                        selected = saveModifiedTable,
                        onClick = {
                            saveModifiedTable = true
                            if (backupNameText == "Stock") {
                                backupNameText = "Modified_UV"
                            }
                        },
                        label = {
                            Text(
                                text = "Modified GPU Table",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = UndervoltEmeraldDim,
                            selectedLabelColor = UndervoltEmerald,
                            containerColor = SlateElevated,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("backup_mode_modified_chip")
                    )
                }

                OutlinedTextField(
                    value = backupNameText,
                    onValueChange = { backupNameText = it },
                    label = { Text("Backup Name") },
                    placeholder = { Text("e.g. Stock, Undervolt_1Step, Modified") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_name_input")
                )

                Text(
                    text = "Quick Name Presets:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickNamePresets.forEach { (presetName, presetIsModified) ->
                        FilterChip(
                            selected = backupNameText.equals(presetName, ignoreCase = true),
                            onClick = {
                                backupNameText = presetName
                                saveModifiedTable = presetIsModified
                            },
                            label = {
                                Text(
                                    text = presetName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TelemetryCyanDim,
                                selectedLabelColor = TelemetryCyan,
                                containerColor = SlateElevated,
                                labelColor = TextPrimary
                            ),
                            modifier = Modifier.testTag("backup_preset_chip_$presetName")
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateElevated,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val safePreview = backupNameText.trim()
                        .ifBlank { if (saveModifiedTable) "Modified" else "Stock" }
                        .replace(Regex("\\s+"), "_")
                        .replace(Regex("[^A-Za-z0-9_.-]"), "")
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (saveModifiedTable) {
                                "Mode: Repack & Backup Current Modified GPU Table (${modifiedStepsSummary.size} modified step(s))"
                            } else {
                                "Mode: Backup Untouched Stock Partition Image ($activePartition)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (saveModifiedTable) UndervoltEmerald else NominalAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Destination: /sdcard/KonaBessBackups/${safePreview}__vendor_boot.img",
                            style = MaterialTheme.typography.bodySmall,
                            color = TelemetryCyan
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("cancel_create_backup_button")
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalName = backupNameText.trim().ifBlank {
                                if (saveModifiedTable) "Modified" else "Stock"
                            }
                            onConfirmCreateBackup(finalName, saveModifiedTable)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NominalAmber,
                            contentColor = Color(0xFF261700)
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("confirm_create_backup_button")
                    ) {
                        Text("Save Named Backup", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmRestoreDefaultsDialog(
    activePartition: String,
    backupImageSourceSummary: String,
    availableBackups: List<com.example.konabess.core.KonaBessShellEngine.SavedBootBackup> = emptyList(),
    selectedBackupId: String? = null,
    onSelectBackup: (String) -> Unit = {},
    onDeleteBackup: (com.example.konabess.core.KonaBessShellEngine.SavedBootBackup) -> Unit = {},
    onLoadBackupIntoEditor: (com.example.konabess.core.KonaBessShellEngine.SavedBootBackup?) -> Unit = {},
    onDismiss: () -> Unit,
    onConfirmRestoreDefaults: () -> Unit
) {
    val selectedBackup = availableBackups.firstOrNull { it.id == selectedBackupId }
        ?: availableBackups.firstOrNull()
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, NominalAmber.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("confirm_restore_defaults_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Load / Restore Backed-Up Image",
                    style = MaterialTheme.typography.titleLarge,
                    color = NominalAmber,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Choose any saved Stock or Modified backup image below to either Load into the Editor or Flash & Restore directly to your partition:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                if (availableBackups.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SlateElevated,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Default Backup Source: $backupImageSourceSummary",
                                style = MaterialTheme.typography.bodySmall,
                                color = NominalAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tip: Tap Backup on the main screen to create named backups for Stock and Modified configurations.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backup_list_column")
                    ) {
                        availableBackups.forEach { backup ->
                            val isSelected = selectedBackup?.id == backup.id
                            val isStockMode = backup.backupModeLabel.equals("STOCK", ignoreCase = true)
                            val cardBorder = when {
                                isSelected && isStockMode -> NominalAmber
                                isSelected -> UndervoltEmerald
                                else -> BorderHairline
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) SlateElevated else Color(0xFF0E141F),
                                border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectBackup(backup.id) }
                                    .testTag("backup_item_${backup.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = backup.displayName,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                color = if (isStockMode) NominalAmberDim else UndervoltEmeraldDim,
                                                shape = RoundedCornerShape(4.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isStockMode) NominalAmber.copy(alpha = 0.6f) else UndervoltEmerald.copy(alpha = 0.6f)
                                                )
                                            ) {
                                                Text(
                                                    text = backup.backupModeLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isStockMode) NominalAmber else UndervoltEmerald,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${backup.bootName} • ${(backup.sizeBytes / 1024).coerceAtLeast(1)} KB • ${dateFormatter.format(Date(backup.lastModifiedMs))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TelemetryCyan
                                        )
                                        Text(
                                            text = backup.path,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }

                                    if (!backup.isBuiltInDefault) {
                                        IconButton(
                                            onClick = { onDeleteBackup(backup) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("delete_backup_${backup.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete backup ${backup.displayName}",
                                                tint = TurboCoral,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateElevated,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Target Partition: $activePartition",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Selected Backup: ${selectedBackup?.let { "${it.displayName} (${it.path})" } ?: backupImageSourceSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NominalAmber
                        )
                    }
                }

                if (selectedBackup != null) {
                    OutlinedButton(
                        onClick = { onLoadBackupIntoEditor(selectedBackup) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = TelemetryCyan
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("load_selected_backup_editor_button")
                    ) {
                        Text(
                            text = "Load \"${selectedBackup.displayName}\" into Editor Only",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("cancel_restore_defaults_button")
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirmRestoreDefaults,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NominalAmber,
                            contentColor = Color(0xFF261700)
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("confirm_restore_defaults_now_button")
                    ) {
                        Text(
                            text = if (selectedBackup != null) {
                                "Flash & Restore \"${selectedBackup.displayName}\""
                            } else {
                                "Restore & Flash Backup"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FlashVerificationAndRebootDialog(
    report: com.example.konabess.core.KonaBessShellEngine.FlashVerificationReport,
    modifiedStepsSummary: List<String>,
    onDismiss: () -> Unit,
    onRebootNow: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val borderColor = if (report.success) UndervoltEmerald else TurboCoral
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, borderColor.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("flash_result_reboot_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = when {
                        report.success && report.isRestoreDefaultImage -> "Restore to Defaults Verified"
                        report.success -> "Flash Verified Successful"
                        report.isRestoreDefaultImage -> "Restore to Defaults Failed"
                        else -> "Flash Failed"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = borderColor,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateElevated,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (report.success) {
                            Text(
                                text = "Partition: ${report.targetPartitionPath.ifBlank { report.partitionLabel }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            if (report.isRestoreDefaultImage) {
                                Text(
                                    text = "Flashed Original Backup: ${report.sourceImagePath.ifBlank { "boot.img" }} (${report.repackedImageBytes / 1024} KB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NominalAmber
                                )
                            } else {
                                Text(
                                    text = "DTB Compiled: ${report.selectedDtbId}.dtb (${report.compiledDtbBytes / 1024} KB) • Linked ${report.totalDtbCount} DTB(s) (${report.linkedDtbBytes / 1024} KB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Repacked Image: boot_new.img (${report.repackedImageBytes / 1024} KB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Text(
                                text = "Partition Readback Check: VERIFIED (SHA-256: ${report.imageSha256.take(16)}...)",
                                style = MaterialTheme.typography.labelMedium,
                                color = UndervoltEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "Failed Stage: ${report.failedStage ?: "Unknown"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TurboCoral,
                                fontWeight = FontWeight.Bold
                            )
                            if (!report.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = report.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                if (report.success) {
                    if (!report.isRestoreDefaultImage && modifiedStepsSummary.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SlateElevated,
                            border = BorderStroke(1.dp, BorderHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "FLASHED GPU CHANGES (${modifiedStepsSummary.size}):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TelemetryCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                modifiedStepsSummary.take(6).forEach { line ->
                                    Text(
                                        text = "• $line",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = if (report.isRestoreDefaultImage) {
                            "Your original backed-up boot image has been flashed and verified on your partition. Would you like to reboot your device now to restore default GPU settings?"
                        } else {
                            "Flash has been verified on your partition. Would you like to reboot your device now to apply the new GPU frequency and voltage table?"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("reboot_later_button")
                        ) {
                            Text("Reboot Later", color = TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRebootNow,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = UndervoltEmerald,
                                contentColor = Color(0xFF002411)
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("reboot_now_button")
                        ) {
                            Text("Reboot System Now", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SlateElevated,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("dismiss_flash_error_button")
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

