package com.example.konabess.ui

import androidx.compose.foundation.BorderStroke
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TurboCoral
import com.example.ui.theme.UndervoltEmerald

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
    var busFreqText by remember(row.id) { mutableStateOf(row.busFreq.toString()) }
    var busMinText by remember(row.id) { mutableStateOf(row.busMin.toString()) }
    var busMaxText by remember(row.id) { mutableStateOf(row.busMax.toString()) }
    var acdHexText by remember(row.id) { mutableStateOf(row.acdLevelHex.orEmpty()) }

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

                Text(
                    text = "Select from existing voltage levels read from device (${chipProfile.levelPropertyKey}):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chipProfile.readVoltageLevels.forEach { level ->
                        val isSelected = selectedVoltageCode == level.code && customVoltageCodeText.isBlank()
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedVoltageCode = level.code
                                customVoltageCodeText = ""
                            },
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

                // Secondary row actions: Set Initial Boot Level, Duplicate, Delete
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

                // Dialog Confirm / Cancel
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
                                KnownRpmhLabels.encodeLabelToCodeIfKnown(customVoltageCodeText)
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
    val defaultCode = chipProfile.readVoltageLevels.lastOrNull()?.code ?: 224
    var freqMhzText by remember { mutableStateOf("855") }
    var selectedVoltageCode by remember { mutableIntStateOf(defaultCode) }
    var busFreqText by remember { mutableStateOf("11") }
    var busMinText by remember { mutableStateOf("9") }
    var busMaxText by remember { mutableStateOf("11") }
    var acdHexText by remember { mutableStateOf("") }

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

                Text(
                    text = "Voltage Level (from existing read table):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chipProfile.readVoltageLevels.forEach { level ->
                        FilterChip(
                            selected = selectedVoltageCode == level.code,
                            onClick = { selectedVoltageCode = level.code },
                            label = {
                                Text(
                                    text = level.label,
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
                            val mhz = freqMhzText.toLongOrNull() ?: 800L
                            onAddStep(
                                mhz,
                                selectedVoltageCode,
                                busFreqText.toIntOrNull() ?: 11,
                                busMinText.toIntOrNull() ?: 9,
                                busMaxText.toIntOrNull() ?: 11,
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
