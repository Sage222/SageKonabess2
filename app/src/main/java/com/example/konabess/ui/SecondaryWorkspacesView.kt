package com.example.konabess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.konabess.core.VoltageStepShifter
import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuOppVoltRow
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LockedIndigo
import com.example.ui.theme.LockedIndigoDim
import com.example.ui.theme.NominalAmber
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryCyanDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UndervoltEmerald
import com.example.ui.theme.UndervoltEmeraldDim

@Composable
fun VoltTableEditorView(
    oppRows: List<GpuOppVoltRow>,
    chipProfile: DynamicChipProfile,
    onEditOppRow: (GpuOppVoltRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstLockedIdx = (oppRows.size - VoltageStepShifter.PROTECTED_BOTTOM_ROW_COUNT).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "GPU VOLTAGE OPP TABLE (qcom,gpu-opp-table)",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelemetryCyan
                )
                Text(
                    text = "Maps operating frequencies (opp-hz) to RPMh voltage levels (opp-microvolt) read directly from the device tree.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("volt_table_lazy_column"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(oppRows, key = { _, item -> item.id }) { index, opp ->
                val isLocked = index >= firstLockedIdx
                val currentLabel = chipProfile.resolveLabel(opp.microvoltOrLevel.toInt())
                val stockLabel = chipProfile.resolveLabel(opp.stockMicrovoltOrLevel.toInt())
                val isModified = opp.microvoltOrLevel != opp.stockMicrovoltOrLevel

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isLocked) ObsidianBackground else SlateSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isModified) UndervoltEmerald.copy(alpha = 0.7f) else BorderHairline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onEditOppRow(opp) }
                        .testTag("opp_row_$index")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${opp.frequencyMHz} MHz",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isLocked) {
                                    Surface(
                                        color = LockedIndigoDim,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Bottom 2 Locked",
                                                tint = LockedIndigo,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Text(
                                                text = "LOCKED",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = LockedIndigo
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "opp-hz: ${opp.frequencyHz} Hz • Peak BW: ${opp.peakKbps ?: 0} kBps",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            if (isModified) {
                                Text(
                                    text = "DIFF: $stockLabel → $currentLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = UndervoltEmerald
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = if (isModified) UndervoltEmeraldDim else TelemetryCyanDim,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = currentLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isModified) UndervoltEmerald else TelemetryCyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit OPP voltage row",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImportExportConfigView(
    exportedConfigPreview: String,
    onImportRawText: (String) -> Unit,
    onCopyTextFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pasteBuffer by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "IMPORT DEVICE DTS OR KONABESS CONFIG",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelemetryCyan
                )
                Text(
                    text = "Paste any unpacked .dts or KonaBess table below. SageKonaBess reads the existing values directly without assuming preset voltage bins.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = pasteBuffer,
                    onValueChange = { pasteBuffer = it },
                    label = { Text("Paste .dts or KonaBess qcom,gpu-pwrlevels config") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_config_text_field")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clipText.isNotBlank()) {
                                pasteBuffer = clipText
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("paste_clipboard_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste from Clipboard",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste Clipboard", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = { onImportRawText(pasteBuffer) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UndervoltEmerald,
                            contentColor = Color(0xFF002411)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("apply_import_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Parse and Load Table",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Load & Read Values", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Export Active Table Section
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EXPORT TUNED KONABESS CONFIG",
                        style = MaterialTheme.typography.labelLarge,
                        color = UndervoltEmerald
                    )
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("SageKonaBess Config", exportedConfigPreview))
                            onCopyTextFeedback("Copied KonaBess table config to clipboard.")
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TelemetryCyan,
                            contentColor = Color(0xFF002229)
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("copy_export_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Config to Clipboard",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Config", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianBackground,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = exportedConfigPreview,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun DtsAndLogsConsoleView(
    fullDtsText: String,
    shellLogs: List<String>,
    onCopyDtsFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Execution & Step Shift Log Console
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "KONABESS SHELL & STEP SHIFT TELEMETRY LOG",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelemetryCyan
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianBackground,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        shellLogs.takeLast(15).forEach { logLine ->
                            Text(
                                text = logLine,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (logLine.contains("Step")) UndervoltEmerald else TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Full Decompiled / Modified Device Tree Source (.dts)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "DTS Source",
                            tint = NominalAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "GENERATED DEVICE TREE SOURCE (.DTS)",
                            style = MaterialTheme.typography.labelLarge,
                            color = NominalAmber
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("unpacked_gpu.dts", fullDtsText))
                            onCopyDtsFeedback("Copied full .dts source to clipboard.")
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("copy_full_dts_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Copy DTS",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy .DTS", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianBackground,
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fullDtsText,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
