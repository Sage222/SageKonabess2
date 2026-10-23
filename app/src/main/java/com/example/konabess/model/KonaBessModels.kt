package com.example.konabess.model

/**
 * Represents a voltage level read directly from the device's existing DTB/DTS table.
 * Never assumes levels that are not present in the unpacked device tree unless
 * explicitly added by the user.
 */
data class ReadVoltageLevel(
    val code: Int,
    val label: String,
    val isFromExistingTable: Boolean = true
) {
    val displayText: String
        get() = if (label.isNotBlank() && label != code.toString()) {
            "$label ($code)"
        } else {
            label.ifBlank { "LEVEL_$code" }
        }
}

/**
 * Dynamic chip and table metadata constructed strictly from reading the unpacked DTS/DTB.
 * Does NOT assume a fixed preset of voltage bins (e.g. omits NOM or TURBO_L1 if the device's
 * existing table does not have or use them).
 */
data class DynamicChipProfile(
    val modelName: String,
    val compatibleTokens: List<String>,
    val msmIdHex: String?,
    val levelPropertyKey: String,
    val readVoltageLevels: List<ReadVoltageLevel>,
    val hasSeparateVoltTable: Boolean,
    val sourcePartition: String
) {
    fun resolveLabel(code: Int): String {
        val found = readVoltageLevels.firstOrNull { it.code == code }
        if (found != null) return found.label
        return KnownRpmhLabels.decodeKnownCodeOrRaw(code)
    }

    fun resolveDisplayWithCode(code: Int): String {
        val label = resolveLabel(code)
        return if (label == "0x${code.toString(16)}" || label == code.toString()) {
            "LVL $code"
        } else {
            label
        }
    }
}

/**
 * Standard Qualcomm RPMh integer-to-name lookup used ONLY to translate integer codes
 * that are actually encountered in the device's unpacked DTS into human-readable labels
 * (e.g. 192 -> SVS_L1, 224 -> SVS_L2). Never used to inject unreferenced bins into a device.
 */
object KnownRpmhLabels {
    private val rpmhCodeMap: Map<Int, String> = mapOf(
        16 to "RETENTION",
        32 to "MIN_SVS_D1",
        48 to "MIN_SVS",
        56 to "LOW_SVS_D2",
        60 to "LOW_SVS_D1",
        64 to "LOW_SVS",
        80 to "LOW_SVS_L1",
        96 to "LOW_SVS_L2",
        128 to "SVS",
        144 to "SVS_L0",
        192 to "SVS_L1",
        224 to "SVS_L2",
        256 to "NOM",
        320 to "NOM_L1",
        336 to "NOM_L2",
        352 to "NOM_L3",
        384 to "TURBO",
        392 to "TURBO_L0",
        400 to "TURBO_L1",
        416 to "TURBO_L2",
        432 to "TURBO_L3",
        448 to "SUPER_TURBO",
        464 to "SUPER_TURBO_NO_CPR"
    )

    // For older sdm845/sm8150 or custom 1..N indexed tables where codes are small indices,
    // or standard RPMh tables:
    fun decodeKnownCodeOrRaw(code: Int): String {
        return rpmhCodeMap[code] ?: "LEVEL_$code"
    }

    fun encodeLabelToCodeIfKnown(label: String): Int? {
        val normalized = label.trim().uppercase()
        rpmhCodeMap.entries.firstOrNull { it.value == normalized }?.let { return it.key }
        if (normalized.startsWith("LEVEL_")) {
            return normalized.removePrefix("LEVEL_").toIntOrNull()
        }
        return normalized.removePrefix("0X").toIntOrNull(16) ?: normalized.toIntOrNull()
    }
}

/**
 * Represents a single GPU frequency step (`qcom,gpu-pwrlevel@X`) inside a `qcom,gpu-pwrlevels` bin.
 */
data class GpuFreqRow(
    val id: String,
    val levelIndex: Int,
    val frequencyHz: Long,
    val voltageLevelCode: Int,
    val busFreq: Int,
    val busMin: Int,
    val busMax: Int,
    val acdLevelHex: String? = null,
    val extraProperties: Map<String, String> = emptyMap()
) {
    val frequencyMHz: Long
        get() = frequencyHz / 1_000_000L

    val frequencyFormattedMHz: String
        get() {
            val mhz = frequencyHz / 1_000_000.0
            return if (mhz % 1.0 == 0.0) {
                "${mhz.toLong()} MHz"
            } else {
                String.format("%.1f MHz", mhz)
            }
        }
}

/**
 * Represents a GPU Power Level Bin (`qcom,gpu-pwrlevels-0`, `qcom,gpu-pwrlevels-1`, etc.)
 * parsed from the device tree, along with the immutable `stockLevels` read at unpack time.
 */
data class GpuPowerBin(
    val binIndex: Int,
    val nodeName: String,
    val speedBinValue: Int? = null,
    val initialPwrLevel: Int = 1,
    val throttlePwrLevel: Int? = null,
    val headerProperties: Map<String, String> = emptyMap(),
    val levels: List<GpuFreqRow>,
    val stockLevels: List<GpuFreqRow>
) {
    val modifiedRowCount: Int
        get() = levels.mapIndexed { idx, row ->
            val stock = stockLevels.getOrNull(idx)
            stock == null || stock.voltageLevelCode != row.voltageLevelCode || stock.frequencyHz != row.frequencyHz
        }.count { it }
}

/**
 * Represents an entry in `qcom,gpu-opp-table` for chips that define an explicit
 * frequency-to-voltage OPP curve alongside `qcom,gpu-pwrlevels`.
 */
data class GpuOppVoltRow(
    val id: String,
    val nodeName: String,
    val frequencyHz: Long,
    val microvoltOrLevel: Long,
    val peakKbps: Long? = null,
    val stockMicrovoltOrLevel: Long
) {
    val frequencyMHz: Long
        get() = frequencyHz / 1_000_000L
}

/**
 * Complete parsed Device Tree state for the active workspace session.
 */
data class ParsedDtsWorkspace(
    val chipProfile: DynamicChipProfile,
    val bins: List<GpuPowerBin>,
    val oppVoltRows: List<GpuOppVoltRow>,
    val rawHeaderBlock: String,
    val rawFooterBlock: String
)
