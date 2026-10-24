package com.example.konabess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.konabess.viewmodel.KonaBessViewModel
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.NominalAmber
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UndervoltEmerald

@Composable
fun SageKonaBessApp(
    viewModel: KonaBessViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeBin = uiState.activeBin

    Scaffold(
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing
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
                onBackupImage = viewModel::openCreateBackupDialog,
                onRepackAndFlash = viewModel::requestFlashConfirmation,
                onRestoreDefaults = viewModel::requestRestoreDefaultsConfirmation
            )

            Box(modifier = Modifier.weight(1f)) {
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
                        onOpenBackupPicker = viewModel::requestRestoreDefaultsConfirmation
                    )
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

        if (uiState.showCreateBackupDialog) {
            CreateNamedBackupDialog(
                activePartition = uiState.activePartition,
                modifiedStepsSummary = uiState.modifiedStepsSummary,
                onDismiss = viewModel::dismissCreateBackupDialog,
                onConfirmCreateBackup = viewModel::createNamedBackup
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
                availableBackups = uiState.availableBackups,
                selectedBackupId = uiState.selectedBackupId,
                onSelectBackup = viewModel::selectBackupToRestore,
                onDeleteBackup = viewModel::deleteSavedBackup,
                onLoadBackupIntoEditor = viewModel::loadSelectedBackupIntoEditor,
                onDismiss = viewModel::dismissRestoreDefaultsConfirmation,
                onConfirmRestoreDefaults = { viewModel.confirmAndRestoreDefaultBootImage() }
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
    onOpenBackupPicker: () -> Unit
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
                        "SageKonaBess never assumes or invents fake frequencies or voltage levels. Grant root (su) access and tap Unpack Device Boot / Vendor_Boot to read your device's actual stock DTB, or load a backed-up image."
                    } else {
                        "Tap Unpack Device Boot / Vendor_Boot to extract your device's stock GPU frequency & voltage table via magiskboot and dtc, or load a previous backup."
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

                OutlinedButton(
                    onClick = onOpenBackupPicker,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NominalAmber.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SlateElevated,
                        contentColor = NominalAmber
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("prompt_load_backup_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = "Load or Restore Backed-Up Image",
                        tint = NominalAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Load / Restore Backed-Up Image",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
