package com.example.konabess.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.konabess.core.DtsParser
import com.example.konabess.core.KonaBessShellEngine
import com.example.konabess.core.VoltageStepShifter
import com.example.konabess.model.DetectedDtb
import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuOppVoltRow
import com.example.konabess.model.GpuPowerBin
import com.example.konabess.model.KnownRpmhLabels
import com.example.konabess.model.ParsedDtsWorkspace
import com.example.konabess.model.ReadVoltageLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val isFlashing: Boolean = false,
    val hasRootAccess: Boolean = false,
    val activePartition: String = "vendor_boot / boot",
    val detectedDtbs: List<DetectedDtb> = emptyList(),
    val selectedDtbId: Int = -1,
    val workspace: ParsedDtsWorkspace,
    val selectedBinIndex: Int = 0,
    val selectedTab: KonaBessTab = KonaBessTab.FREQ_TABLE,
    val netStepDropsByBin: Map<Int, Int> = emptyMap(),
    val editingFreqRow: GpuFreqRow? = null,
    val editingOppRow: GpuOppVoltRow? = null,
    val showNewFreqDialog: Boolean = false,
    val showFlashConfirmDialog: Boolean = false,
    val showRestoreDefaultsConfirmDialog: Boolean = false,
    val backupImageSourceSummary: String = "/sdcard/vendor_boot.img",
    val flashVerificationReport: KonaBessShellEngine.FlashVerificationReport? = null,
    val statusBannerMessage: String? = null,
    val isStatusHighlightSuccess: Boolean = true,
    val shellLogs: List<String> = emptyList()
) {
    val activeBin: GpuPowerBin?
        get() = workspace.bins.firstOrNull { it.binIndex == selectedBinIndex }
            ?: workspace.bins.firstOrNull()

    val currentNetStepDrop: Int
        get() = netStepDropsByBin[selectedBinIndex] ?: 0

    /**
     * Computes a human-readable list of all modified frequency/voltage steps across all GPU bins
     * compared to the untouched stock table, shown in the Pre-Flash Confirmation and Post-Flash
     * Verification dialogs.
     */
    val modifiedStepsSummary: List<String>
        get() {
            val diffs = mutableListOf<String>()
            val profile = workspace.chipProfile
            for (bin in workspace.bins) {
                val binName = KnownRpmhLabels.formatBinDisplayName(bin.binIndex, profile.chipType)
                if (bin.levels.size != bin.stockLevels.size) {
                    diffs.add("$binName: Step count changed (${bin.stockLevels.size} → ${bin.levels.size} steps)")
                }
                bin.levels.forEachIndexed { idx, row ->
                    val stockRow = bin.stockLevels.getOrNull(idx)
                        ?: bin.stockLevels.firstOrNull { it.frequencyHz == row.frequencyHz }
                    if (stockRow == null) {
                        val curLabel = profile.resolveLabel(row.voltageLevelCode)
                        diffs.add("$binName @${row.levelIndex}: Added ${row.frequencyMHz} MHz ($curLabel)")
                    } else if (stockRow.frequencyHz != row.frequencyHz ||
                        stockRow.voltageLevelCode != row.voltageLevelCode ||
                        stockRow.busFreq != row.busFreq
                    ) {
                        val stockLabel = profile.resolveLabel(stockRow.voltageLevelCode)
                        val curLabel = profile.resolveLabel(row.voltageLevelCode)
                        val freqPart = if (stockRow.frequencyMHz != row.frequencyMHz) {
                            "${stockRow.frequencyMHz}→${row.frequencyMHz} MHz"
                        } else {
                            "${row.frequencyMHz} MHz"
                        }
                        diffs.add("$binName @$idx ($freqPart): $stockLabel → $curLabel")
                    }
                }
            }
            return diffs
        }
}

class KonaBessViewModel(application: Application) : AndroidViewModel(application) {

    private val shellEngine = KonaBessShellEngine(application.applicationContext)

    // Per-bin history stack of frequency row snapshots for exact step restoration
    private val stepHistoryByBin = mutableMapOf<Int, ArrayDeque<List<GpuFreqRow>>>()
    private val oppStepHistory = ArrayDeque<List<GpuOppVoltRow>>()

    // Untouched stock DTS string unpacked from the device DTB (or imported baseline)
    private var untouchedStockDts: String = ""

    private val emptyWorkspace = DtsParser.parseDts("", "vendor_boot / boot")

    private val _uiState = MutableStateFlow(
        KonaBessUiState(
            isLoading = true,
            workspace = emptyWorkspace,
            selectedBinIndex = 0,
            shellLogs = listOf(
                "[Init] Initializing KonaBess Core (magiskboot + dtc)..."
            )
        )
    )
    val uiState: StateFlow<KonaBessUiState> = _uiState.asStateFlow()

    init {
        unpackDeviceImage(forceReloadStock = true)
    }

    fun unpackDeviceImage(forceReloadStock: Boolean = false, preferredDtbId: Int? = null) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val result = shellEngine.unpackAndReadDeviceDts(
                    forceReloadFromPartition = forceReloadStock,
                    preferredDtbId = preferredDtbId
                )

                if (result.dtsContent.isBlank()) {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            hasRootAccess = result.hasRootAccess,
                            activePartition = result.activePartition,
                            detectedDtbs = result.detectedDtbs,
                            selectedDtbId = result.selectedDtbId,
                            statusBannerMessage = if (result.hasRootAccess) {
                                "Could not find Qualcomm GPU power levels in boot/vendor_boot. Import a .dts or konabess:// config."
                            } else {
                                "Root access (su) required to unpack device boot/vendor_boot. Grant root & tap Re-Read, or import a config."
                            },
                            isStatusHighlightSuccess = false,
                            shellLogs = state.shellLogs + result.logs
                        )
                    }
                    return@launch
                }

                val activeParsed = withContext(Dispatchers.Default) {
                    untouchedStockDts = result.stockDtsContent.ifBlank { result.dtsContent }
                    val stockParsed = DtsParser.parseDts(
                        untouchedStockDts,
                        result.activePartition,
                        result.selectedDtbId
                    )
                    if (forceReloadStock || result.dtsContent == untouchedStockDts) {
                        stockParsed
                    } else {
                        val currentParsed = DtsParser.parseDts(
                            result.dtsContent,
                            result.activePartition,
                            result.selectedDtbId,
                            stockParsed.chipProfile.chipType
                        )
                        val mergedBins = currentParsed.bins.map { bin ->
                            val matchingStockBin = stockParsed.bins.firstOrNull { it.binIndex == bin.binIndex }
                            if (matchingStockBin != null) {
                                bin.copy(stockLevels = matchingStockBin.stockLevels.map { it.copy() })
                            } else {
                                bin
                            }
                        }
                        val mergedCodes = (stockParsed.chipProfile.readVoltageLevels.map { it.code } +
                            currentParsed.chipProfile.readVoltageLevels.map { it.code }).toSortedSet()
                        val stockCodeSet = stockParsed.chipProfile.readVoltageLevels.map { it.code }.toSet()
                        val mergedReadLevels = mergedCodes.map { code ->
                            ReadVoltageLevel(
                                code = code,
                                label = KnownRpmhLabels.decodeKnownCodeOrRaw(code, stockParsed.chipProfile.chipType),
                                isFromExistingTable = code in stockCodeSet
                            )
                        }
                        currentParsed.copy(
                            chipProfile = currentParsed.chipProfile.copy(readVoltageLevels = mergedReadLevels),
                            bins = mergedBins
                        )
                    }
                }

                stepHistoryByBin.clear()
                oppStepHistory.clear()

                val firstBin = activeParsed.bins.firstOrNull()?.binIndex ?: 0
                val levelSummary = activeParsed.chipProfile.readVoltageLevels.joinToString(", ") { it.label }
                val freqSummary = activeParsed.bins.firstOrNull()?.levels?.joinToString(", ") { "${it.frequencyMHz}MHz" }.orEmpty()

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        hasRootAccess = result.hasRootAccess,
                        activePartition = result.activePartition,
                        detectedDtbs = result.detectedDtbs,
                        selectedDtbId = result.selectedDtbId,
                        workspace = activeParsed,
                        selectedBinIndex = firstBin,
                        netStepDropsByBin = emptyMap(),
                        statusBannerMessage = "Unpacked ${activeParsed.chipProfile.modelName}: ${activeParsed.bins.size} bin(s), ${activeParsed.bins.firstOrNull()?.levels?.size ?: 0} stock frequencies.",
                        isStatusHighlightSuccess = true,
                        shellLogs = state.shellLogs + result.logs + listOf(
                            "[Stock Freqs] $freqSummary",
                            "[Stock Levels] $levelSummary"
                        )
                    )
                }
            } catch (t: Throwable) {
                appendLog("[Unpack Error] ${t.message}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusBannerMessage = "Error unpacking DTB: ${t.message}",
                        isStatusHighlightSuccess = false
                    )
                }
            }
        }
    }

    fun selectDtb(dtbId: Int) {
        if (dtbId == _uiState.value.selectedDtbId) return
        unpackDeviceImage(forceReloadStock = false, preferredDtbId = dtbId)
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
     * level, while strictly keeping the 2 bottom visible frequencies (e.g. 295MHz & 220MHz) unchanged.
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

        val historyStack = stepHistoryByBin.getOrPut(bin.binIndex) { ArrayDeque() }
        historyStack.addLast(currentRows.map { it.copy() })

        val shiftResult = VoltageStepShifter.dropVoltageStepsOneLevel(currentRows)

        val updatedOppRows = if (state.workspace.oppVoltRows.isNotEmpty()) {
            oppStepHistory.addLast(state.workspace.oppVoltRows.map { it.copy() })
            VoltageStepShifter.dropOppVoltTableOneLevel(state.workspace.oppVoltRows)
        } else {
            state.workspace.oppVoltRows
        }

        val sampleRowIdx = currentRows.indexOfFirst { it.frequencyMHz == 719L }.takeIf { it >= 0 } ?: 0
        val beforeCode = currentRows[sampleRowIdx].voltageLevelCode
        val afterCode = shiftResult.updatedRows[sampleRowIdx].voltageLevelCode
        val beforeLabel = state.workspace.chipProfile.resolveLabel(beforeCode)
        val afterLabel = state.workspace.chipProfile.resolveLabel(afterCode)
        val sampleFreqMHz = currentRows[sampleRowIdx].frequencyMHz

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = shiftResult.updatedRows) else b
        }
        val updatedWorkspace = state.workspace.copy(
            bins = updatedBins,
            oppVoltRows = updatedOppRows
        )
        val newNetDrops = (state.netStepDropsByBin[bin.binIndex] ?: 0) + 1

        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog(
            "[-1 Step Drop] Bin ${bin.binIndex}: Shifted ${shiftResult.shiftedCount} frequencies down 1 step " +
                "(e.g. ${sampleFreqMHz}MHz: $beforeLabel -> $afterLabel). Bottom ${shiftResult.lockedBottomCount} frequencies kept unchanged."
        )

        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
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
        val updatedWorkspace = state.workspace.copy(
            bins = updatedBins,
            oppVoltRows = previousOpp ?: state.workspace.oppVoltRows
        )
        val currentDrops = state.netStepDropsByBin[bin.binIndex] ?: 0
        val newNetDrops = if (currentDrops > 0) currentDrops - 1 else currentDrops

        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog(
            "[+1 Step Up] Bin ${bin.binIndex}: Stepped up ${shiftResult.shiftedCount} frequencies " +
                "(Bottom ${shiftResult.lockedBottomCount} frequencies kept unchanged)."
        )

        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
                netStepDropsByBin = it.netStepDropsByBin + (bin.binIndex to newNetDrops),
                statusBannerMessage = "Stepped up ${shiftResult.shiftedCount} frequency rows (+1 Step). Bottom 2 unchanged.",
                isStatusHighlightSuccess = true
            )
        }
    }

    /**
     * Resets the active GPU Power Bin (and OPP table + readVoltageLevels) to the exact untouched
     * stock snapshot unpacked from the device's DTB, removing any user-added frequencies or levels.
     */
    fun resetActiveBinToStock() {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        stepHistoryByBin[bin.binIndex]?.clear()
        oppStepHistory.clear()

        viewModelScope.launch {
            try {
                val stockDiskText = shellEngine.restoreStockDtsForDtb(state.selectedDtbId)
                val authoritativeStockText = stockDiskText ?: untouchedStockDts

                val restoredWorkspace = withContext(Dispatchers.Default) {
                    if (authoritativeStockText.isNotBlank()) {
                        val freshStockParsed = DtsParser.parseDts(
                            authoritativeStockText,
                            state.activePartition,
                            state.selectedDtbId
                        )
                        val stockBin = freshStockParsed.bins.firstOrNull { it.binIndex == bin.binIndex }
                        if (stockBin != null) {
                            val updatedBins = state.workspace.bins.map { b ->
                                if (b.binIndex == bin.binIndex) stockBin else b
                            }
                            freshStockParsed.copy(bins = updatedBins)
                        } else {
                            freshStockParsed
                        }
                    } else {
                        val restoredRows = bin.stockLevels.map { it.copy() }
                        val restoredOpp = state.workspace.oppVoltRows.map {
                            it.copy(microvoltOrLevel = it.stockMicrovoltOrLevel)
                        }
                        val updatedBins = state.workspace.bins.map { b ->
                            if (b.binIndex == bin.binIndex) b.copy(levels = restoredRows) else b
                        }
                        val stockCodes = linkedSetOf<Int>()
                        updatedBins.forEach { b -> b.levels.forEach { r -> stockCodes.add(r.voltageLevelCode) } }
                        val cleanReadLevels = stockCodes.sorted().map { code ->
                            ReadVoltageLevel(
                                code = code,
                                label = KnownRpmhLabels.decodeKnownCodeOrRaw(code, state.workspace.chipProfile.chipType),
                                isFromExistingTable = true
                            )
                        }
                        state.workspace.copy(
                            chipProfile = state.workspace.chipProfile.copy(readVoltageLevels = cleanReadLevels),
                            bins = updatedBins,
                            oppVoltRows = restoredOpp
                        )
                    }
                }

                persistWorkspaceToDtsDisk(restoredWorkspace)
                appendLog("[Reset Stock] Bin ${bin.binIndex} restored to exact stock device DTB values.")

                _uiState.update {
                    it.copy(
                        workspace = restoredWorkspace,
                        netStepDropsByBin = it.netStepDropsByBin + (bin.binIndex to 0),
                        statusBannerMessage = "Restored Bin ${bin.binIndex} to exact stock unpacked table (${restoredWorkspace.bins.firstOrNull { b -> b.binIndex == bin.binIndex }?.levels?.size ?: 0} stock steps).",
                        isStatusHighlightSuccess = true
                    )
                }
            } catch (t: Throwable) {
                appendLog("[Reset Error] ${t.message}")
            }
        }
    }

    /**
     * Explicit "Save GPU Freq Table" action matching original `GpuTableEditor.writeOut(genBack(genTable()))`.
     */
    fun saveFreqTableToDts() {
        val state = _uiState.value
        viewModelScope.launch {
            try {
                val dtsText = withContext(Dispatchers.Default) {
                    DtsParser.serializeToDts(state.workspace)
                }
                val saved = shellEngine.writeEditedDtsToDisk(state.selectedDtbId, dtsText)
                val modCount = state.modifiedStepsSummary.size
                appendLog(
                    "[Save Table] Wrote GPU table to ${if (state.selectedDtbId >= 0) "${state.selectedDtbId}.dts" else "workspace"} " +
                        "($modCount modified step(s), ${dtsText.length} bytes, diskSaved=$saved)."
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessage = if (saved) {
                            "Saved GPU table to ${state.selectedDtbId}.dts ($modCount modified step(s)). Tap Flash to repack & flash."
                        } else {
                            "Saved GPU table in workspace ($modCount modified step(s)). Tap Flash to repack & flash."
                        },
                        isStatusHighlightSuccess = true
                    )
                }
            } catch (t: Throwable) {
                appendLog("[Save Error] ${t.message}")
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Save error: ${t.message}",
                        isStatusHighlightSuccess = false
                    )
                }
            }
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
            .mapIndexed { idx, r -> r.copy(levelIndex = idx) }

        val updatedProfile = ensureVoltageCodeRegisteredIfCustom(
            state.workspace.chipProfile,
            updatedRow.voltageLevelCode
        )

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = updatedLevels) else b
        }
        val updatedWorkspace = state.workspace.copy(
            chipProfile = updatedProfile,
            bins = updatedBins
        )

        persistWorkspaceToDtsDisk(updatedWorkspace)

        val label = updatedProfile.resolveLabel(updatedRow.voltageLevelCode)
        appendLog("[Edit Row] Updated ${updatedRow.frequencyMHz} MHz -> $label (0x${updatedRow.voltageLevelCode.toString(16)})")

        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
                editingFreqRow = null,
                statusBannerMessage = "Updated ${updatedRow.frequencyMHz} MHz to $label.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun duplicateFreqRow(row: GpuFreqRow) {
        val state = _uiState.value
        val bin = state.activeBin ?: return

        val currentIdx = bin.levels.indexOfFirst { it.id == row.id }.coerceAtLeast(0)
        val cloned = row.copy(
            id = UUID.randomUUID().toString()
        )
        val mutableLevels = bin.levels.toMutableList()
        mutableLevels.add(currentIdx, cloned)
        val updatedLevels = mutableLevels.mapIndexed { idx, r -> r.copy(levelIndex = idx) }

        val updatedBins = state.workspace.bins.map { b ->
            if (b.binIndex == bin.binIndex) b.copy(levels = updatedLevels) else b
        }
        val updatedWorkspace = state.workspace.copy(bins = updatedBins)
        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog("[Duplicate] Cloned ${row.frequencyMHz} MHz step at index $currentIdx.")
        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
                editingFreqRow = null,
                statusBannerMessage = "Duplicated ${cloned.frequencyMHz} MHz step.",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun deleteFreqRow(rowId: String) {
        val state = _uiState.value
        val bin = state.activeBin ?: return
        if (bin.levels.size <= 2) {
            _uiState.update {
                it.copy(
                    editingFreqRow = null,
                    statusBannerMessage = "Cannot delete: at least 2 GPU power levels are required.",
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
        val updatedWorkspace = state.workspace.copy(bins = updatedBins)
        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog("[Delete] Removed ${target?.frequencyMHz ?: ""} MHz frequency step.")
        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
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
        val templateRawLines = bin.levels.firstOrNull()?.rawLines.orEmpty()
        val newRow = GpuFreqRow(
            id = UUID.randomUUID().toString(),
            levelIndex = 0,
            frequencyHz = frequencyMHz * 1_000_000L,
            voltageLevelCode = voltageLevelCode,
            busFreq = busFreq,
            busMin = busMin,
            busMax = busMax,
            acdLevelHex = acdHex?.takeIf { it.isNotBlank() },
            rawLines = templateRawLines
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
        val updatedWorkspace = state.workspace.copy(
            chipProfile = updatedProfile,
            bins = updatedBins
        )
        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog("[New Step] Added ${frequencyMHz} MHz (${updatedProfile.resolveLabel(voltageLevelCode)}) to Bin ${bin.binIndex}.")
        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
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
        val updatedWorkspace = state.workspace.copy(bins = updatedBins)
        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog("[Bin Param] Set qcom,initial-pwrlevel = $coerced (${bin.levels.getOrNull(coerced)?.frequencyMHz ?: 0} MHz)")
        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
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
        val updatedWorkspace = state.workspace.copy(
            chipProfile = updatedProfile,
            oppVoltRows = updatedOpp
        )
        persistWorkspaceToDtsDisk(updatedWorkspace)

        appendLog("[Volt Table] Updated OPP ${frequencyHz / 1_000_000L} MHz -> ${updatedProfile.resolveLabel(microvoltOrLevel.toInt())}")
        _uiState.update {
            it.copy(
                workspace = updatedWorkspace,
                editingOppRow = null,
                statusBannerMessage = "Updated OPP Voltage Table entry (${frequencyHz / 1_000_000L} MHz).",
                isStatusHighlightSuccess = true
            )
        }
    }

    /**
     * Imports user-provided `.dts` or `konabess://` Gzip+Base64 config text and derives the
     * ChipDefinition strictly from the existing values present in the imported text.
     */
    fun importDtsOrConfigText(rawText: String, sourceLabel: String = "Imported Config") {
        val decodedText = DtsParser.decodeKonaBessUriIfPresent(rawText).trim()
        if (decodedText.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Import failed: input text is empty.",
                    isStatusHighlightSuccess = false
                )
            }
            return
        }

        val currentState = _uiState.value
        val normalizedDts = when {
            decodedText.contains("qcom,gpu-pwrlevel@") && !decodedText.contains("qcom,gpu-pwrlevels") -> {
                """
                /dts-v1/;
                / {
                    model = "${currentState.workspace.chipProfile.modelName.ifBlank { sourceLabel }}";
                    qcom,kgsl-3d0@3d00000 {
                        qcom,gpu-pwrlevel-bins {
                            qcom,gpu-pwrlevels-0 {
                                compatible = "qcom,gpu-pwrlevels";
                                $decodedText
                            };
                        };
                    };
                };
                """.trimIndent()
            }
            !decodedText.contains("/dts-v1/;") && decodedText.contains("qcom,gpu-pwrlevels") -> {
                """
                /dts-v1/;
                / {
                    model = "${currentState.workspace.chipProfile.modelName.ifBlank { sourceLabel }}";
                    qcom,kgsl-3d0@3d00000 {
                        qcom,gpu-pwrlevel-bins {
                            $decodedText
                        };
                    };
                };
                """.trimIndent()
            }
            else -> decodedText
        }

        val parsed = DtsParser.parseDts(
            normalizedDts,
            sourceLabel,
            currentState.selectedDtbId,
            currentState.workspace.chipProfile.chipType
        )
        if (parsed.bins.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "No valid qcom,gpu-pwrlevels nodes found in imported text.",
                    isStatusHighlightSuccess = false
                )
            }
            return
        }

        val finalWorkspace = if (!decodedText.contains("/dts-v1/;") &&
            currentState.workspace.originalDtsLinesConsideringSplice.isNotEmpty() &&
            currentState.workspace.binSplicePosition >= 0
        ) {
            val stockBins = currentState.workspace.bins
            val binsWithStockPreserved = parsed.bins.map { importedBin ->
                val origStock = stockBins.firstOrNull { it.binIndex == importedBin.binIndex }?.stockLevels
                if (origStock != null) importedBin.copy(stockLevels = origStock) else importedBin
            }
            val mergedCodes = (currentState.workspace.chipProfile.readVoltageLevels.map { it.code } +
                parsed.chipProfile.readVoltageLevels.map { it.code }).toSortedSet()
            val mergedReadLevels = mergedCodes.map { code ->
                ReadVoltageLevel(
                    code = code,
                    label = KnownRpmhLabels.decodeKnownCodeOrRaw(code, currentState.workspace.chipProfile.chipType),
                    isFromExistingTable = true
                )
            }
            currentState.workspace.copy(
                chipProfile = currentState.workspace.chipProfile.copy(
                    readVoltageLevels = mergedReadLevels
                ),
                bins = binsWithStockPreserved,
                oppVoltRows = if (parsed.oppVoltRows.isNotEmpty()) parsed.oppVoltRows else currentState.workspace.oppVoltRows
            )
        } else {
            if (untouchedStockDts.isBlank()) {
                untouchedStockDts = normalizedDts
            }
            parsed
        }

        stepHistoryByBin.clear()
        oppStepHistory.clear()
        persistWorkspaceToDtsDisk(finalWorkspace)

        val readLevelsText = finalWorkspace.chipProfile.readVoltageLevels.joinToString(", ") { it.label }
        appendLog("[Import] Loaded ${finalWorkspace.bins.size} bin(s) from $sourceLabel. Voltage levels: $readLevelsText")

        _uiState.update {
            it.copy(
                workspace = finalWorkspace,
                selectedBinIndex = finalWorkspace.bins.first().binIndex,
                selectedTab = KonaBessTab.FREQ_TABLE,
                netStepDropsByBin = emptyMap(),
                statusBannerMessage = "Imported ${finalWorkspace.bins.size} bin(s) (${finalWorkspace.bins.first().levels.size} steps).",
                isStatusHighlightSuccess = true
            )
        }
    }

    fun exportActiveBinConfig(): String {
        val state = _uiState.value
        return DtsParser.exportKonaBessConfig(state.workspace, state.selectedBinIndex)
    }

    fun exportKonaBessClipboardUri(): String {
        return DtsParser.exportKonaBessUri(_uiState.value.workspace)
    }

    fun exportFullDtsText(): String {
        return DtsParser.serializeToDts(_uiState.value.workspace)
    }

    fun backupOldImage() {
        viewModelScope.launch {
            val (ok, message) = shellEngine.createBackupImageCopy()
            appendLog("[Backup] $message")
            _uiState.update {
                it.copy(
                    statusBannerMessage = message,
                    isStatusHighlightSuccess = ok
                )
            }
        }
    }

    /**
     * Opens the Pre-Flash Confirmation Dialog when the user taps the Flash button.
     */
    fun requestFlashConfirmation() {
        _uiState.update { it.copy(showFlashConfirmDialog = true) }
    }

    fun dismissFlashConfirmation() {
        _uiState.update { it.copy(showFlashConfirmDialog = false) }
    }

    /**
     * Executes the original KonaBess repack & flash pipeline (`dts2bootImage` + `writeBootImage`)
     * and opens the Post-Flash Verification & Reboot Confirmation Dialog when complete.
     */
    fun confirmAndRepackFlashImage() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    showFlashConfirmDialog = false,
                    isLoading = true,
                    isFlashing = true,
                    statusBannerMessage = "Compiling DTB, repacking boot_new.img & flashing partition..."
                )
            }
            try {
                val dtsText = withContext(Dispatchers.Default) {
                    DtsParser.serializeToDts(state.workspace)
                }
                val result = shellEngine.saveRepackedDtsAndFlash(state.selectedDtbId, dtsText)
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isFlashing = false,
                        flashVerificationReport = result.flashVerification,
                        shellLogs = current.shellLogs + result.logs,
                        statusBannerMessage = if (result.success && result.hasRootAccess) {
                            "Flash verified on ${result.flashVerification?.targetPartitionPath ?: result.bootName}! Ready to reboot."
                        } else {
                            result.flashVerification?.errorMessage
                                ?: result.logs.lastOrNull()
                                ?: "Flash failed."
                        },
                        isStatusHighlightSuccess = result.success
                    )
                }
            } catch (t: Throwable) {
                val errMsg = "Flash failed: ${t.message ?: t.javaClass.simpleName}"
                appendLog("[Flash Error] $errMsg")
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isFlashing = false,
                        flashVerificationReport = KonaBessShellEngine.FlashVerificationReport(
                            success = false,
                            failedStage = "Flash Pipeline",
                            partitionLabel = current.activePartition,
                            selectedDtbId = current.selectedDtbId,
                            errorMessage = errMsg
                        ),
                        statusBannerMessage = errMsg,
                        isStatusHighlightSuccess = false
                    )
                }
            }
        }
    }

    /**
     * Direct entry point (kept for callers/tests).
     */
    fun repackAndFlashImage() {
        confirmAndRepackFlashImage()
    }

    fun dismissFlashVerificationDialog() {
        _uiState.update { it.copy(flashVerificationReport = null) }
    }

    /**
     * Opens the Restore to Defaults Confirmation Dialog when the user taps Restore to Defaults.
     */
    fun requestRestoreDefaultsConfirmation() {
        _uiState.update { it.copy(showRestoreDefaultsConfirmDialog = true) }
        viewModelScope.launch {
            try {
                val summary = shellEngine.describeAvailableBackupSource()
                _uiState.update { it.copy(backupImageSourceSummary = summary) }
            } catch (_: Throwable) {
            }
        }
    }

    fun dismissRestoreDefaultsConfirmation() {
        _uiState.update { it.copy(showRestoreDefaultsConfirmDialog = false) }
    }

    /**
     * Flashes the original backed-up boot image (`/sdcard/<boot_name>.img`,
     * `<filesDir>/backup_<boot_name>.img`, or `<filesDir>/boot.img`) back to the active
     * partition via `dd`, verifies the partition readback SHA-256, resets the GPU table workspace
     * to stock defaults, and shows the verification + reboot prompt dialog.
     */
    fun confirmAndRestoreDefaultBootImage() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    showRestoreDefaultsConfirmDialog = false,
                    isLoading = true,
                    isFlashing = true,
                    statusBannerMessage = "Flashing original backed-up boot image & verifying partition..."
                )
            }
            try {
                val result = shellEngine.restoreOriginalBootImageAndFlash(state.selectedDtbId)

                val restoredWorkspace = withContext(Dispatchers.Default) {
                    val authoritativeStock = result.stockDtsContent
                        .ifBlank { result.dtsContent }
                        .ifBlank { untouchedStockDts }
                    if (authoritativeStock.isNotBlank()) {
                        untouchedStockDts = authoritativeStock
                        DtsParser.parseDts(
                            authoritativeStock,
                            result.activePartition.ifBlank { state.activePartition },
                            result.selectedDtbId.takeIf { it >= 0 } ?: state.selectedDtbId,
                            state.workspace.chipProfile.chipType
                        )
                    } else {
                        val restoredBins = state.workspace.bins.map { bin ->
                            bin.copy(levels = bin.stockLevels.map { it.copy() })
                        }
                        val restoredOpp = state.workspace.oppVoltRows.map {
                            it.copy(microvoltOrLevel = it.stockMicrovoltOrLevel)
                        }
                        state.workspace.copy(
                            bins = restoredBins,
                            oppVoltRows = restoredOpp
                        )
                    }
                }

                stepHistoryByBin.clear()
                oppStepHistory.clear()

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isFlashing = false,
                        workspace = if (restoredWorkspace.bins.isNotEmpty()) restoredWorkspace else current.workspace,
                        netStepDropsByBin = emptyMap(),
                        flashVerificationReport = result.flashVerification,
                        shellLogs = current.shellLogs + result.logs,
                        statusBannerMessage = if (result.success && result.hasRootAccess) {
                            "Restored original backed-up image (${result.flashVerification?.sourceImagePath ?: result.bootName}) & verified partition! Ready to reboot."
                        } else {
                            result.flashVerification?.errorMessage
                                ?: result.logs.lastOrNull()
                                ?: "Restore to defaults failed."
                        },
                        isStatusHighlightSuccess = result.success
                    )
                }
            } catch (t: Throwable) {
                val errMsg = "Restore to defaults failed: ${t.message ?: t.javaClass.simpleName}"
                appendLog("[Restore Error] $errMsg")
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isFlashing = false,
                        flashVerificationReport = KonaBessShellEngine.FlashVerificationReport(
                            success = false,
                            isRestoreDefaultImage = true,
                            failedStage = "Restore Pipeline",
                            partitionLabel = current.activePartition,
                            selectedDtbId = current.selectedDtbId,
                            errorMessage = errMsg
                        ),
                        statusBannerMessage = errMsg,
                        isStatusHighlightSuccess = false
                    )
                }
            }
        }
    }

    /**
     * Executes `KonaBessCore.reboot()` (`su -c "svc power reboot || reboot"`) after flash is confirmed.
     */
    fun confirmRebootDevice() {
        viewModelScope.launch {
            appendLog("[Reboot] Requesting system reboot via su -c svc power reboot...")
            val ok = shellEngine.rebootDevice()
            if (!ok) {
                _uiState.update {
                    it.copy(
                        flashVerificationReport = null,
                        statusBannerMessage = "Reboot command requires root access. Please reboot your device manually.",
                        isStatusHighlightSuccess = false
                    )
                }
            }
        }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    private fun persistWorkspaceToDtsDisk(workspace: ParsedDtsWorkspace) {
        val dtbId = _uiState.value.selectedDtbId
        if (dtbId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dtsText = DtsParser.serializeToDts(workspace)
                shellEngine.writeEditedDtsToDisk(dtbId, dtsText)
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureVoltageCodeRegisteredIfCustom(
        profile: DynamicChipProfile,
        code: Int
    ): DynamicChipProfile {
        if (profile.readVoltageLevels.any { it.code == code }) return profile
        val updatedLevels = (profile.readVoltageLevels + ReadVoltageLevel(
            code = code,
            label = KnownRpmhLabels.decodeKnownCodeOrRaw(code, profile.chipType),
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
