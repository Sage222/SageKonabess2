package com.example.konabess.core

import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuOppVoltRow

/**
 * Deterministic voltage step shifting engine for SageKonaBess.
 *
 * Implements the user's exact specification:
 * - Pressing Drop Voltage (-1 Step) shifts every frequency's voltage level to the level of the
 *   next lower frequency step in the table (e.g., if 719MHz is SVS_L2 and the next step down
 *   680MHz is SVS_L1, 719MHz drops to SVS_L1, 680MHz drops to the next lower frequency's level, etc.).
 * - Strictly keeps the 2 bottom visible frequencies (e.g. 295MHz and 220MHz) unchanged, as well as
 *   any trailing 0Hz idle state.
 * - Never assumes or invents external voltage tiers (like MIN_SVS, NOM, or TURBO_L1) because every
 *   shifted value comes directly from the existing read frequency rows.
 */
object VoltageStepShifter {

    const val PROTECTED_BOTTOM_ROW_COUNT = 2

    data class ShiftResult(
        val updatedRows: List<GpuFreqRow>,
        val shiftedCount: Int,
        val lockedBottomCount: Int
    )

    /**
     * Drops each frequency's voltage level (`qcom,level` / `qcom,cx-ipeak`) to the voltage level
     * of the next lower frequency step (`row[i].voltageLevelCode = row[i + 1].voltageLevelCode`),
     * while keeping the bottom 2 visible (`frequencyHz > 0`) frequencies unchanged.
     */
    fun dropVoltageStepsOneLevel(currentRows: List<GpuFreqRow>): ShiftResult {
        val positiveRows = currentRows.filter { it.frequencyHz > 0L }
        val trailingZeroRows = currentRows.filter { it.frequencyHz <= 0L }

        if (positiveRows.size <= PROTECTED_BOTTOM_ROW_COUNT) {
            return ShiftResult(
                updatedRows = currentRows,
                shiftedCount = 0,
                lockedBottomCount = positiveRows.size
            )
        }

        val lastShiftableIndex = positiveRows.size - PROTECTED_BOTTOM_ROW_COUNT - 1
        var changedCount = 0

        val updatedPositive = positiveRows.mapIndexed { index, row ->
            if (index <= lastShiftableIndex) {
                val nextLowerStepVoltage = positiveRows[index + 1].voltageLevelCode
                if (row.voltageLevelCode != nextLowerStepVoltage) {
                    changedCount++
                }
                row.copy(voltageLevelCode = nextLowerStepVoltage)
            } else {
                // Bottom 2 frequencies remain strictly unchanged
                row
            }
        }

        return ShiftResult(
            updatedRows = updatedPositive + trailingZeroRows,
            shiftedCount = changedCount,
            lockedBottomCount = PROTECTED_BOTTOM_ROW_COUNT
        )
    }

    /**
     * Companion Step Up (+1) action:
     * - If a previous snapshot exists on the undo/step history stack, restores that exact state.
     * - Otherwise shifts rows 1..(N-3) upward to adopt `positiveRows[i - 1].voltageLevelCode`
     *   using only existing values from the table, while keeping the bottom 2 frequencies unchanged.
     */
    fun raiseVoltageStepsOneLevel(
        currentRows: List<GpuFreqRow>,
        previousSnapshot: List<GpuFreqRow>?
    ): ShiftResult {
        if (previousSnapshot != null && previousSnapshot.size == currentRows.size) {
            var restoredCount = 0
            val restored = currentRows.mapIndexed { idx, row ->
                val prev = previousSnapshot[idx]
                if (idx <= currentRows.size - PROTECTED_BOTTOM_ROW_COUNT - 1 && row.frequencyHz > 0L) {
                    if (row.voltageLevelCode != prev.voltageLevelCode) {
                        restoredCount++
                    }
                    row.copy(voltageLevelCode = prev.voltageLevelCode)
                } else {
                    row
                }
            }
            return ShiftResult(
                updatedRows = restored,
                shiftedCount = restoredCount,
                lockedBottomCount = minOf(PROTECTED_BOTTOM_ROW_COUNT, currentRows.size)
            )
        }

        val positiveRows = currentRows.filter { it.frequencyHz > 0L }
        val trailingZeroRows = currentRows.filter { it.frequencyHz <= 0L }

        if (positiveRows.size <= PROTECTED_BOTTOM_ROW_COUNT) {
            return ShiftResult(
                updatedRows = currentRows,
                shiftedCount = 0,
                lockedBottomCount = positiveRows.size
            )
        }

        val lastShiftableIndex = positiveRows.size - PROTECTED_BOTTOM_ROW_COUNT - 1
        var changedCount = 0

        val updatedPositive = positiveRows.mapIndexed { index, row ->
            when {
                index == 0 -> row
                index <= lastShiftableIndex -> {
                    val nextHigherStepVoltage = positiveRows[index - 1].voltageLevelCode
                    if (row.voltageLevelCode != nextHigherStepVoltage) {
                        changedCount++
                    }
                    row.copy(voltageLevelCode = nextHigherStepVoltage)
                }
                else -> row // Bottom 2 frequencies remain strictly unchanged
            }
        }

        return ShiftResult(
            updatedRows = updatedPositive + trailingZeroRows,
            shiftedCount = changedCount,
            lockedBottomCount = PROTECTED_BOTTOM_ROW_COUNT
        )
    }

    /**
     * Applies the same cascade step drop to `qcom,gpu-opp-table` rows if the device tree
     * also maintains an explicit OPP table, keeping the bottom 2 frequencies unchanged.
     */
    fun dropOppVoltTableOneLevel(currentOppRows: List<GpuOppVoltRow>): List<GpuOppVoltRow> {
        if (currentOppRows.size <= PROTECTED_BOTTOM_ROW_COUNT) return currentOppRows
        val lastShiftableIndex = currentOppRows.size - PROTECTED_BOTTOM_ROW_COUNT - 1
        return currentOppRows.mapIndexed { index, row ->
            if (index <= lastShiftableIndex) {
                row.copy(microvoltOrLevel = currentOppRows[index + 1].microvoltOrLevel)
            } else {
                row
            }
        }
    }
}
