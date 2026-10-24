package com.example.konabess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.konabess.viewmodel.KonaBessTab
import com.example.konabess.viewmodel.KonaBessViewModel
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryCyanDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UndervoltEmerald

@Composable
fun SageKonaBessApp(
    viewModel: KonaBessViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeBin = uiState.activeBin

    // Handle system back from secondary tabs back to the primary Freq Table editor
    BackHandler(enabled = uiState.selectedTab != KonaBessTab.FREQ_TABLE) {
        viewModel.selectTab(KonaBessTab.FREQ_TABLE)
    }

    Scaffold(
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = SlateSurface,
                contentColor = TextPrimary
            ) {
                NavigationBarItem(
                    selected = uiState.selectedTab == KonaBessTab.FREQ_TABLE,
                    onClick = { viewModel.selectTab(KonaBessTab.FREQ_TABLE) },
                    icon = {
                        Icon(
                            imageVector = if (uiState.selectedTab == KonaBessTab.FREQ_TABLE) {
                                Icons.Filled.Speed
                            } else {
                                Icons.Outlined.Speed
                            },
                            contentDescription = "GPU Frequency Table"
                        )
                    },
                    label = { Text("Freq Table") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        indicatorColor = TelemetryCyanDim,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_freq_table")
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == KonaBessTab.VOLT_TABLE,
                    onClick = { viewModel.selectTab(KonaBessTab.VOLT_TABLE) },
                    icon = {
                        Icon(
                            imageVector = if (uiState.selectedTab == KonaBessTab.VOLT_TABLE) {
                                Icons.Filled.Bolt
                            } else {
                                Icons.Outlined.Bolt
                            },
                            contentDescription = "GPU Voltage OPP Table"
                        )
                    },
                    label = { Text("Volt Table") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        indicatorColor = TelemetryCyanDim,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_volt_table")
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == KonaBessTab.IMPORT_EXPORT,
                    onClick = { viewModel.selectTab(KonaBessTab.IMPORT_EXPORT) },
                    icon = {
                        Icon(
                            imageVector = if (uiState.selectedTab == KonaBessTab.IMPORT_EXPORT) {
                                Icons.Filled.ImportExport
                            } else {
                                Icons.Outlined.ImportExport
                            },
                            contentDescription = "Import and Export Config"
                        )
                    },
                    label = { Text("Import/Export") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        indicatorColor = TelemetryCyanDim,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_import_export")
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == KonaBessTab.DTS_CONSOLE,
                    onClick = { viewModel.selectTab(KonaBessTab.DTS_CONSOLE) },
                    icon = {
                        Icon(
                            imageVector = if (uiState.selectedTab == KonaBessTab.DTS_CONSOLE) {
                                Icons.Filled.Terminal
                            } else {
                                Icons.Outlined.Terminal
                            },
                            contentDescription = "DTS Source and Shell Log"
                        )
                    },
                    label = { Text("DTS & Log") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TelemetryCyan,
                        selectedTextColor = TelemetryCyan,
                        indicatorColor = TelemetryCyanDim,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_dts_console")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HardwareTelemetryHeader(
                chipProfile = uiState.workspace.chipProfile,
                bins = uiState.workspace.bins,
                selectedBinIndex = uiState.selectedBinIndex,
                detectedDtbs = uiState.detectedDtbs,
                selectedDtbId = uiState.selectedDtbId,
                hasRootAccess = uiState.hasRootAccess,
                activePartition = uiState.activePartition,
                statusMessage = uiState.statusBannerMessage,
                isStatusSuccess = uiState.isStatusHighlightSuccess,
                onDismissStatus = viewModel::dismissStatusBanner,
                onSelectDtb = viewModel::selectDtb,
                onSelectBin = viewModel::selectBin,
                onUnpackDevice = { viewModel.unpackDeviceImage(forceReloadStock = true) },
                onSaveTable = viewModel::saveFreqTableToDts,
                onBackupImage = viewModel::backupOldImage,
                onRepackAndFlash = viewModel::requestFlashConfirmation,
                onRestoreDefaults = viewModel::requestRestoreDefaultsConfirmation
            )

            Box(modifier = Modifier.weight(1f)) {
                when (uiState.selectedTab) {
                    KonaBessTab.FREQ_TABLE -> {
                        if (activeBin != null) {
                            FreqTableEditorView(
                                bin = activeBin,
                                chipProfile = uiState.workspace.chipProfile,
                                netStepDrops = uiState.currentNetStepDrop,
                                onDropVoltageStep = viewModel::dropAllVoltagesOneStep,
                                onRaiseVoltageStep = viewModel::raiseAllVoltagesOneStep,
                                onResetToStock = viewModel::resetActiveBinToStock,
                                onAddNewFreqStep = viewModel::openNewFreqDialog,
                                onSelectFreqRow = viewModel::openFreqRowEditor
                            )
                        } else {
                            UnpackDevicePromptView(
                                isLoading = uiState.isLoading,
                                hasRootAccess = uiState.hasRootAccess,
                                onUnpackDevice = { viewModel.unpackDeviceImage(forceReloadStock = true) },
                                onOpenImportTab = { viewModel.selectTab(KonaBessTab.IMPORT_EXPORT) },
                                onOpenLogsTab = { viewModel.selectTab(KonaBessTab.DTS_CONSOLE) }
                            )
                        }
                    }

                    KonaBessTab.VOLT_TABLE -> {
                        VoltTableEditorView(
                            oppRows = uiState.workspace.oppVoltRows,
                            chipProfile = uiState.workspace.chipProfile,
                            onEditOppRow = viewModel::openOppRowEditor
                        )
                    }

                    KonaBessTab.IMPORT_EXPORT -> {
                        ImportExportConfigView(
                            exportedConfigPreview = viewModel.exportActiveBinConfig(),
                            onImportRawText = { raw ->
                                viewModel.importDtsOrConfigText(raw, "User Imported DTS/Config")
                            },
                            onCopyTextFeedback = { /* Clipboard copy handled in view */ }
                        )
                    }

                    KonaBessTab.DTS_CONSOLE -> {
                        DtsAndLogsConsoleView(
                            fullDtsText = viewModel.exportFullDtsText(),
                            shellLogs = uiState.shellLogs,
                            onCopyDtsFeedback = { /* Clipboard copy handled in view */ }
                        )
                    }
                }
            }
        }

        val editingRow = uiState.editingFreqRow
        if (editingRow != null && activeBin != null) {
            EditFreqRowDialog(
                row = editingRow,
                chipProfile = uiState.workspace.chipProfile,
                isInitialPwrLevel = editingRow.levelIndex == activeBin.initialPwrLevel,
                onDismiss = viewModel::dismissFreqRowEditor,
                onSave = viewModel::saveEditedFreqRow,
                onDuplicate = viewModel::duplicateFreqRow,
                onDelete = viewModel::deleteFreqRow,
                onSetInitialLevel = viewModel::updateInitialPowerLevel
            )
        }

        if (uiState.showNewFreqDialog) {
            NewFreqRowDialog(
                chipProfile = uiState.workspace.chipProfile,
                onDismiss = viewModel::dismissNewFreqDialog,
                onAddStep = viewModel::addNewFrequencyStep
            )
        }

        val editingOpp = uiState.editingOppRow
        if (editingOpp != null) {
            EditOppVoltRowDialog(
                oppRow = editingOpp,
                chipProfile = uiState.workspace.chipProfile,
                onDismiss = viewModel::dismissOppRowEditor,
                onSave = viewModel::saveEditedOppRow
            )
        }

        if (uiState.showFlashConfirmDialog) {
            ConfirmFlashDialog(
                activePartition = uiState.activePartition,
                modelName = uiState.workspace.chipProfile.modelName,
                selectedDtbId = uiState.selectedDtbId,
                modifiedStepsSummary = uiState.modifiedStepsSummary,
                onDismiss = viewModel::dismissFlashConfirmation,
                onConfirmFlash = viewModel::confirmAndRepackFlashImage
            )
        }

        if (uiState.showRestoreDefaultsConfirmDialog) {
            ConfirmRestoreDefaultsDialog(
                activePartition = uiState.activePartition,
                backupImageSourceSummary = uiState.backupImageSourceSummary,
                onDismiss = viewModel::dismissRestoreDefaultsConfirmation,
                onConfirmRestoreDefaults = viewModel::confirmAndRestoreDefaultBootImage
            )
        }

        val flashReport = uiState.flashVerificationReport
        if (flashReport != null) {
            FlashVerificationAndRebootDialog(
                report = flashReport,
                modifiedStepsSummary = uiState.modifiedStepsSummary,
                onDismiss = viewModel::dismissFlashVerificationDialog,
                onRebootNow = viewModel::confirmRebootDevice
            )
        }
    }
}

@Composable
private fun UnpackDevicePromptView(
    isLoading: Boolean,
    hasRootAccess: Boolean,
    onUnpackDevice: () -> Unit,
    onOpenImportTab: () -> Unit,
    onOpenLogsTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, BorderHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isLoading) {
                        "UNPACKING DEVICE BOOT / VENDOR_BOOT..."
                    } else {
                        "NO UNPACKED DEVICE DTB LOADED"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TelemetryCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLoading) {
                        "Running magiskboot & dtc to extract your device's exact stock GPU frequency and voltage table..."
                    } else if (!hasRootAccess) {
                        "SageKonaBess never assumes or invents fake frequencies or voltage levels. Grant root (su) access and tap Unpack Device Image to read your device's actual stock boot/vendor_boot DTB, or import a .dts / konabess:// backup."
                    } else {
                        "Tap Unpack Device Image to extract your device's stock GPU frequency & voltage table via magiskboot and dtc, or inspect the shell log."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Button(
                    onClick = onUnpackDevice,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UndervoltEmerald,
                        contentColor = Color(0xFF002411)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("prompt_unpack_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Unpack Device Image",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Unpack Device Boot / Vendor_Boot",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenImportTab,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderHairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Import .DTS / Config", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = onOpenLogsTab,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderHairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SlateElevated,
                            contentColor = TelemetryCyan
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("View Shell Log", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
