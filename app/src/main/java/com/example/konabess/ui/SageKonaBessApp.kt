package com.example.konabess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.konabess.viewmodel.KonaBessTab
import com.example.konabess.viewmodel.KonaBessViewModel
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryCyanDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

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
                hasRootAccess = uiState.hasRootAccess,
                activePartition = uiState.activePartition,
                statusMessage = uiState.statusBannerMessage,
                isStatusSuccess = uiState.isStatusHighlightSuccess,
                onDismissStatus = viewModel::dismissStatusBanner,
                onSelectBin = viewModel::selectBin,
                onUnpackDevice = { viewModel.unpackDeviceImage(forceReloadStock = true) },
                onBackupImage = viewModel::backupOldImage,
                onRepackAndFlash = viewModel::repackAndFlashImage
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
                            onCopyTextFeedback = { msg ->
                                viewModel.importDtsOrConfigText(
                                    viewModel.exportFullDtsText(),
                                    uiState.workspace.chipProfile.sourcePartition
                                )
                            }
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

        // Precision Level Inspector Dialog for editing individual frequency step
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

        // Add New Frequency Step Dialog
        if (uiState.showNewFreqDialog) {
            NewFreqRowDialog(
                chipProfile = uiState.workspace.chipProfile,
                onDismiss = viewModel::dismissNewFreqDialog,
                onAddStep = viewModel::addNewFrequencyStep
            )
        }

        // Edit OPP Voltage Table Entry Dialog
        val editingOpp = uiState.editingOppRow
        if (editingOpp != null) {
            EditOppVoltRowDialog(
                oppRow = editingOpp,
                chipProfile = uiState.workspace.chipProfile,
                onDismiss = viewModel::dismissOppRowEditor,
                onSave = viewModel::saveEditedOppRow
            )
        }
    }
}
