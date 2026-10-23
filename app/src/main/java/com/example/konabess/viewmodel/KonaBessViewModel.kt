package com.example.konabess.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.konabess.core.DtsParser
import com.example.konabess.core.KonaBessShellEngine
import com.example.konabess.core.VoltageStepShifter
import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuOppVoltRow
import com.example.konabess.model.GpuPowerBin
import com.example.konabess.model.KnownRpmhLabels
import com.example.konabess.model.ParsedDtsWorkspace
import com.example.konabess.model.ReadVoltageLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class KonaBessTab {
    FREQ_TABLE,
    VOLT_TABLE,
    IMPORT_EXPORT,
    DTS_CONSOLE
}

data class KonaBessUiState(
    val isLoading: Boolean = false,
    val hasRootAccess: Boolean = false,
    val activePartition: String = "boot / vendor_boot",
    val workspace: ParsedDtsWorkspace,
    val selectedBinIndex: Int = 0,
    val selectedTab: KonaBessTab = KonaBessTab.FREQ_TABLE,
    val netStepDropsByBin: Map<Int, Int> = emptyMap(),
    val editingFreqRow: GpuFreqRow? = null,
    val editingOppRow: GpuOppVoltRow? = null,
    val showNewFreqDialog: Boolean = false,
    val statusBannerMessage: String? = null,
    val isStatusHighlightSuccess: Boolean = true,
    val shellLogs: List<String> = emptyList()
) {
    val activeBin: GpuPowerBin?
        get() = workspace.bins.firstOrNull { it.binIndex == selectedBinIndex }
            ?: workspace.bins.firstOrNull()

    val currentNetStepDrop: Int
        get() = netStepDropsByBin[selectedBinIndex] ?: 0
}

class KonaBessViewModel(application: Application) : AndroidViewModel(application) {

    private val shellEngine = KonaBessShellEngine(application.applicationContext)

    // Per-bin history stack of frequency row snapshots for exact step restoration
    private val stepHistoryByBin = mutableMapOf<Int, ArrayDeque<List<GpuFreqRow>>>()
    private val oppStepHistory = ArrayDeque<List<GpuOppVoltRow>>()

    private val initialDts = shellEngine.buildDefaultReadDeviceDts()
    private val initialWorkspace = DtsParser.parseDts(initialDts, "boot / vendor_boot")

    private val _uiState = MutableStateFlow(
        KonaBessUiState(
            workspace = initialWorkspace,
            selectedBinIndex = initialWorkspace.bins.firstOrNull()?.binIndex ?: 0,
            shellLogs = listOf(
                "[Init] Read existing GPU frequency & voltage table from device DTB.",
                "[Init] Derived ${initialWorkspace.chipProfile.readVoltageLevels.size} active voltage levels from existing values: " +
                    initialWorkspace.chipProfile.readVoltageLevels.joinToString(", ") { it.label }
            )
        )
    )
    val uiState: StateFlow<KonaBessUiState> = _uiState.asStateFlow()

    init {
        unpackDeviceImage(forceReloadStock = false)
    }

    fun unpackDeviceImage(forceReloadStock: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = shellEngine.unpackAndReadDeviceDts(forceReloadStock)
            val parsed = DtsParser.parseDts(result.dtsContent, result.activePartition)
            if (forceReloadStock) {
                stepHistoryByBin.clear()
                oppStepHistory.clear()
            }
            val firstBin = parsed.bins.firstOrNull()?.binIndex ?: 0
            val levelSummary = parsed.chipProfile.readVoltageLevels.joinToString(", ") { it.label }
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    hasRootAccess = result.hasRootAccess,
                    activePartition = result.activePartition,
                    workspace = parsed,
                    selectedBinIndex = firstBin,
                    netStepDropsByBin = if (forceReloadStock) emptyMap() else state.netStepDropsByBin,
                    statusBannerMessage = "Read ${parsed.bins.size} GPU bin(s) & ${parsed.chipProfile.readVoltageLevels.size} existing voltage levels from device.",
                    isStatusHighlightSuccess = true,
                    shellLogs = state.shellLogs + result.logs + listOf(
                        "[Parser] Existing voltage levels read: $levelSummary"
                    )
                )
            }
        }
    }

    fun selectBin(binIndex: Int) {
        _uiState.update { it.copy(selectedBinIndex = binIndex) }
    }

    fun selectTab(tab: KonaBessTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /**
     * One-Tap Voltage Step Drop (-1 Step):
     * Drops every frequency's voltage level to the next lower frequency step's existing voltage
     * level, while strictly keeping the 2 bottom frequencies unchanged.
     */
    fun dropAllVoltagesOneStep() {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        val currentRows = bin.levels

        if (currentRows.size <= VoltageStepShifter.PROTECTED_BOTTOM_ROW_COUNT) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Table has ${currentRows.size} rows; bottom 2 frequencies are protected.",
                    isStatusHighlightSuccess = false
                )
            }
            return
        }

        // Push current state onto history stack before shifting
        val historyStack = stepHistoryByBin.getOrPut(bin.binIndex) { ArrayDeque() }
        historyStack.addLast(currentRows.map { it.copy() })

        val shiftResult = VoltageStepShifter.dropVoltageStepsOneLevel(currentRows)

        // Also shift OPP table if present
        val updatedOppRows = if (state.workspace.oppVoltRows.isNotEmpty()) {
            oppStepHistory.addLast(state.workspace.oppVoltRows.map { it.copy() })
            VoltageStepShifter.dropOppVoltTableOneLevel(state.workspace.oppVoltRows)
        } else {
            state.workspace.oppVoltRows
        }

        // Build sample log entry highlighting e.g. 719MHz shift
        val sampleRowIdx = currentRows.indexOfFirst { it.frequencyMHz == 719L }.takeIf { it >= 0 } ?: 0
        val beforeCode = currentRows[sampleRowIdx].voltageLevelCode
        val afterCode = shiftResult.updatedRows[sampleRowIdx].voltageLevelCode
        val beforeLabel = state.workspace.chipProfile.resolveLabel(beforeCode)
        val afterLabel = state.workspace.chipProfile.resolveLabel(afterCode)
        val sampleFreqMHz = currentRows[sampleRowIdx].frequencyMHz

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = shiftResult.updatedRows) else b
        }
        val newNetDrops = (state.netStepDropsByBin[bin.binIndex] ?: 0) + 1

        appendLog(
            "[-1 Step Drop] Bin ${bin.binIndex}: Shifted ${shiftResult.shiftedCount} frequencies down 1 step " +
                "(e.g. ${sampleFreqMHz}MHz: $beforeLabel -> $afterLabel). Bottom ${shiftResult.lockedBottomCount} frequencies kept unchanged."
        )

        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    bins = updatedBins,
                    oppVoltRows = updatedOppRows
                ),
                netStepDropsByBin = it.netStepDropsByBin + (bin.binIndex to newNetDrops),
                statusBannerMessage = "Dropped ${shiftResult.shiftedCount} frequencies by 1 voltage step (${sampleFreqMHz}MHz: $beforeLabel → $afterLabel). Bottom 2 unchanged.",
                isStatusHighlightSuccess = true
            )
        }
    }

    /**
     * Companion Step Up (+1 Step):
     * Restores the previous step snapshot if dropped, or shifts frequencies up 1 step using only
     * existing read table levels while keeping the bottom 2 frequencies unchanged.
     */
    fun raiseAllVoltagesOneStep() {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        val historyStack = stepHistoryByBin[bin.binIndex]
        val previousSnapshot = if (!historyStack.isNullOrEmpty()) historyStack.removeLast() else null

        val shiftResult = VoltageStepShifter.raiseVoltageStepsOneLevel(bin.levels, previousSnapshot)
        val previousOpp = if (oppStepHistory.isNotEmpty()) oppStepHistory.removeLast() else null

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = shiftResult.updatedRows) else b
        }
        val currentDrops = state.netStepDropsByBin[bin.binIndex] ?: 0
        val newNetDrops = if (currentDrops > 0) currentDrops - 1 else currentDrops

        appendLog(
            "[+1 Step Up] Bin ${bin.binIndex}: Stepped up ${shiftResult.shiftedCount} frequencies " +
                "(Bottom ${shiftResult.lockedBottomCount} frequencies kept unchanged)."
        )

        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    bins = updatedBins,
                    oppVoltRows = previousOpp ?: it.workspace.oppVoltRows
                ),
                netStepDropsByBin = it.netStepDropsByBin + (bin.binIndex to newNetDrops),
                statusBannerMessage = "Stepped up ${shiftResult.shiftedCount} frequency rows (+1 Step). Bottom 2 unchanged.",
                isStatusHighlightSuccess = true
            )
        }
    }

    /**
     * Resets the active GPU Power Bin (and OPP table) to the exact stock snapshot read on unpack.
     */
    fun resetActiveBinToStock() {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        stepHistoryByBin[bin.binIndex]?.clear()
        oppStepHistory.clear()

        val restoredRows = bin.stockLevels.map { it.copy() }
        val restoredOpp = state.workspace.oppVoltRows.map {
            it.copy(microvoltOrLevel = it.stockMicrovoltOrLevel)
        }

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = restoredRows) else b
        }

        appendLog("[Reset] Bin ${bin.binIndex} restored to stock unpacked values.")

        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    bins = updatedBins,
                    oppVoltRows = restoredOpp
                ),
                netStepDropsByBin = it.netStepDropsByBin + (bin.binIndex to 0),
                statusBannerMessage = "Restored Bin ${bin.binIndex} to stock unpacked table.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun openFreqRowEditor(row: GpuFreqRow) {
        _uiState.update { it.copy(editingFreqRow = row) }
    }

    fun dismissFreqRowEditor() {
        _uiState.update { it.copy(editingFreqRow = null) }
    }

    fun saveEditedFreqRow(updatedRow: GpuFreqRow) {
        val state = _uiState.value
        val bin = state.activeBin ?: return

        val updatedLevels = bin.levels
            .map { if (it.id == updatedRow.id) updatedRow else it }
            .sortedByDescending { it.frequencyHz }
            .mapIndexed { idx, r -> r.copy(levelIndex = idx) }

        val updatedProfile = ensureVoltageCodeRegisteredIfCustom(
            state.workspace.chipProfile,
            updatedRow.voltageLevelCode
        )

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = updatedLevels) else b
        }

        val label = updatedProfile.resolveLabel(updatedRow.voltageLevelCode)
        appendLog("[Edit Row] Updated ${updatedRow.frequencyMHz} MHz -> $label (0x${updatedRow.voltageLevelCode.toString(16)})")

        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    chipProfile = updatedProfile,
                    bins = updatedBins
                ),
                editingFreqRow = null,
                statusBannerMessage = "Updated ${updatedRow.frequencyMHz} MHz to $label.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun duplicateFreqRow(row: GpuFreqRow) {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        if (bin.levels.size >= 24) return

        val cloned = row.copy(
            id = UUID.randomUUID().toString(),
            frequencyHz = row.frequencyHz + 10_000_000L
        )
        val updatedLevels = (bin.levels + cloned)
            .sortedByDescending { it.frequencyHz }
            .mapIndexed { idx, r -> r.copy(levelIndex = idx) }

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = updatedLevels) else b
        }

        appendLog("[Duplicate] Cloned ${row.frequencyMHz} MHz step as ${cloned.frequencyMHz} MHz.")
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(bins = updatedBins),
                editingFreqRow = null,
                statusBannerMessage = "Duplicated step as ${cloned.frequencyMHz} MHz.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun deleteFreqRow(rowId: String) {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        if (bin.levels.size <= 3) {
            _uiState.update {
                it.copy(
                    editingFreqRow = null,
                    statusBannerMessage = "Cannot delete: a minimum of 3 GPU power levels is required.",
                    isStatusHighlightSuccess = false
                )
            }
            return
        }

        val target = bin.levels.firstOrNull { it.id == rowId }
        val updatedLevels = bin.levels
            .filterNot { it.id == rowId }
            .mapIndexed { idx, r -> r.copy(levelIndex = idx) }

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) {
                val safeInitial = b.initialPwrLevel.coerceAtMost(updatedLevels.lastIndex)
                b.copy(levels = updatedLevels, initialPwrLevel = safeInitial)
            } else b
        }

        appendLog("[Delete] Removed ${target?.frequencyMHz ?: ""} MHz frequency step.")
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(bins = updatedBins),
                editingFreqRow = null,
                statusBannerMessage = "Deleted ${target?.frequencyMHz ?: ""} MHz frequency step.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun openNewFreqDialog() {
        _uiState.update { it.copy(showNewFreqDialog = true) }
    }

    fun dismissNewFreqDialog() {
        _uiState.update { it.copy(showNewFreqDialog = false) }
    }

    fun addNewFrequencyStep(
        frequencyMHz: Long,
        voltageLevelCode: Int,
        busFreq: Int,
        busMin: Int,
        busMax: Int,
        acdHex: String?
    ) {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        val newRow = GpuFreqRow(
            id = UUID.randomUUID().toString(),
            levelIndex = 0,
            frequencyHz = frequencyMHz * 1_000_000L,
            voltageLevelCode = voltageLevelCode,
            busFreq = busFreq,
            busMin = busMin,
            busMax = busMax,
            acdLevelHex = acdHex?.takeIf { it.isNotBlank() }
        )
        val updatedLevels = (bin.levels + newRow)
            .sortedByDescending { it.frequencyHz }
            .mapIndexed { idx, r -> r.copy(levelIndex = idx) }

        val updatedProfile = ensureVoltageCodeRegisteredIfCustom(
            state.workspace.chipProfile,
            voltageLevelCode
        )

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = updatedLevels) else b
        }

        appendLog("[New Step] Added ${frequencyMHz} MHz (${updatedProfile.resolveLabel(voltageLevelCode)}) to Bin ${bin.binIndex}.")
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    chipProfile = updatedProfile,
                    bins = updatedBins
                ),
                showNewFreqDialog = false,
                statusBannerMessage = "Added ${frequencyMHz} MHz step to Bin ${bin.binIndex}.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun updateInitialPowerLevel(newIndex: Int) {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        val coerced = newIndex.coerceIn(0, (bin.levels.size - 1).coerceAtLeast(0))
        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(initialPwrLevel = coerced) else b
        }
        appendLog("[Bin Param] Set qcom,initial-pwrlevel = $coerced (${bin.levels.getOrNull(coerced)?.frequencyMHz ?: 0} MHz)")
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(bins = updatedBins),
                statusBannerMessage = "Updated initial boot power level to index $coerced.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun openOppRowEditor(row: GpuOppVoltRow) {
        _uiState.update { it.copy(editingOppRow = row) }
    }

    fun dismissOppRowEditor() {
        _uiState.update { it.copy(editingOppRow = null) }
    }

    fun saveEditedOppRow(rowId: String, frequencyHz: Long, microvoltOrLevel: Long, peakKbps: Long?) {
        val state = _uiState.value
        val updatedOpp = state.workspace.oppVoltRows
            .map {
                if (it.id == rowId) {
                    it.copy(
                        frequencyHz = frequencyHz,
                        microvoltOrLevel = microvoltOrLevel,
                        peakKbps = peakKbps
                    )
                } else it
            }
            .sortedByDescending { it.frequencyHz }

        val updatedProfile = if (microvoltOrLevel in 1..1000L) {
            ensureVoltageCodeRegisteredIfCustom(state.workspace.chipProfile, microvoltOrLevel.toInt())
        } else {
            state.workspace.chipProfile
        }

        appendLog("[Volt Table] Updated OPP ${frequencyHz / 1_000_000L} MHz -> ${updatedProfile.resolveLabel(microvoltOrLevel.toInt())}")
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    chipProfile = updatedProfile,
                    oppVoltRows = updatedOpp
                ),
                editingOppRow = null,
                statusBannerMessage = "Updated OPP Voltage Table entry (${frequencyHz / 1_000_000L} MHz).",
                isStatusHighlightSuccess = true
            )
        }
    }

    /**
     * Imports user-provided `.dts` or KonaBess table text and derives the ChipDefinition
     * strictly from the existing values present in the imported text.
     */
    fun importDtsOrConfigText(rawText: String, sourceLabel: String = "Imported Config") {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Import failed: input text is empty.",
                    isStatusHighlightSuccess = false
                )
            }
            return
        }

        val normalizedDts = if (trimmed.contains("qcom,gpu-pwrlevel@") && !trimmed.contains("qcom,gpu-pwrlevels")) {
            // Wrap bare pwrlevel list into a valid bin
            """
            /dts-v1/;
            / {
                model = "$sourceLabel";
                qcom,kgsl-3d0@3d00000 {
                    qcom,gpu-pwrlevel-bins {
                        qcom,gpu-pwrlevels-0 {
                            compatible = "qcom,gpu-pwrlevels";
                            $trimmed
                        };
                    };
                };
            };
            """.trimIndent()
        } else {
            trimmed
        }

        val parsed = DtsParser.parseDts(normalizedDts, sourceLabel)
        if (parsed.bins.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "No valid qcom,gpu-pwrlevels nodes found in imported text.",
                    isStatusHighlightSuccess = false
                )
            }
            return
        }

        stepHistoryByBin.clear()
        oppStepHistory.clear()
        val readLevelsText = parsed.chipProfile.readVoltageLevels.joinToString(", ") { it.label }
        appendLog("[Import] Loaded ${parsed.bins.size} bin(s) from $sourceLabel. Existing voltage levels: $readLevelsText")

        _uiState.update {
            it.copy(
                workspace = parsed,
                selectedBinIndex = parsed.bins.first().binIndex,
                selectedTab = KonaBessTab.FREQ_TABLE,
                netStepDropsByBin = emptyMap(),
                statusBannerMessage = "Imported ${parsed.bins.size} bin(s). Derived ${parsed.chipProfile.readVoltageLevels.size} existing voltage levels.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun exportActiveBinConfig(): String {
        val state = _uiState.value
        return DtsParser.exportKonaBessConfig(state.workspace, state.selectedBinIndex)
    }

    fun exportFullDtsText(): String {
        return DtsParser.serializeToDts(_uiState.value.workspace)
    }

    fun backupOldImage() {
        viewModelScope.launch {
            val (_, message) = shellEngine.createBackupImageCopy()
            appendLog("[Backup] $message")
            _uiState.update {
                it.copy(
                    statusBannerMessage = message,
                    isStatusHighlightSuccess = true
                )
            }
        }
    }

    fun repackAndFlashImage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val dtsText = DtsParser.serializeToDts(_uiState.value.workspace)
            val result = shellEngine.saveRepackedDtsAndFlash(dtsText)
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    shellLogs = state.shellLogs + result.logs,
                    statusBannerMessage = "Repacked modified DTS (${dtsText.lines().size} lines) and wrote image output.",
                    isStatusHighlightSuccess = result.success
                )
            }
        }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    private fun ensureVoltageCodeRegisteredIfCustom(
        profile: com.example.konabess.model.DynamicChipProfile,
        code: Int
    ): com.example.konabess.model.DynamicChipProfile {
        if (profile.readVoltageLevels.any { it.code == code }) return profile
        val updatedLevels = (profile.readVoltageLevels + ReadVoltageLevel(
            code = code,
            label = KnownRpmhLabels.decodeKnownCodeOrRaw(code),
            isFromExistingTable = false
        )).sortedBy { it.code }
        return profile.copy(readVoltageLevels = updatedLevels)
    }

    private fun appendLog(entry: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _uiState.update { state ->
            state.copy(shellLogs = state.shellLogs + "[$ts] $entry")
        }
    }
}
