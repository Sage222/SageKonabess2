package com.example.konabess.model

/**
 * Exact chip types from libxzr/KonaBess ChipInfo.type.
 */
enum class KonaBessChipType(val isSingleBin: Boolean, val voltTableIgnore: Boolean) {
    kona(isSingleBin = false, voltTableIgnore = false),
    kona_singleBin(isSingleBin = true, voltTableIgnore = false),
    msmnile(isSingleBin = false, voltTableIgnore = false),
    msmnile_singleBin(isSingleBin = true, voltTableIgnore = false),
    lahaina(isSingleBin = false, voltTableIgnore = true),
    lahaina_singleBin(isSingleBin = true, voltTableIgnore = true),
    lito_v1(isSingleBin = false, voltTableIgnore = false),
    lito_v2(isSingleBin = false, voltTableIgnore = false),
    lagoon(isSingleBin = false, voltTableIgnore = false),
    shima(isSingleBin = false, voltTableIgnore = true),
    yupik(isSingleBin = false, voltTableIgnore = true),
    waipio_singleBin(isSingleBin = true, voltTableIgnore = true),
    cape_singleBin(isSingleBin = true, voltTableIgnore = true),
    kalama(isSingleBin = false, voltTableIgnore = true),
    kalama_sg_singleBin(isSingleBin = true, voltTableIgnore = true),
    diwali(isSingleBin = false, voltTableIgnore = true),
    ukee_singleBin(isSingleBin = true, voltTableIgnore = true),
    pineapple(isSingleBin = false, voltTableIgnore = true),
    cliffs_singleBin(isSingleBin = true, voltTableIgnore = true),
    cliffs_7_singleBin(isSingleBin = true, voltTableIgnore = true),
    sun(isSingleBin = false, voltTableIgnore = true),
    canoe(isSingleBin = false, voltTableIgnore = true),
    unknown(isSingleBin = false, voltTableIgnore = true)
}

/**
 * Represents a decompiled DTB candidate discovered by KonaBessCore.checkDevice().
 */
data class DetectedDtb(
    val id: Int,
    val chipType: KonaBessChipType,
    val description: String,
    val isActiveBootDtb: Boolean = false
)

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
 * Does NOT assume a fixed preset of voltage bins (e.g. omits MIN_SVS, NOM, or TURBO_L1 if the
 * device's existing table does not have or use them).
 */
data class DynamicChipProfile(
    val modelName: String,
    val chipType: KonaBessChipType = KonaBessChipType.unknown,
    val compatibleTokens: List<String>,
    val msmIdHex: String?,
    val levelPropertyKey: String,
    val readVoltageLevels: List<ReadVoltageLevel>,
    val hasSeparateVoltTable: Boolean,
    val sourcePartition: String,
    val activeDtbId: Int = -1
) {
    fun resolveLabel(code: Int): String {
        val decoded = KnownRpmhLabels.decodeKnownCodeOrRaw(code, chipType)
        if (!decoded.contains("LEVEL_")) return decoded
        val found = readVoltageLevels.firstOrNull { it.code == code }
        return found?.label ?: decoded
    }

    fun resolveDisplayWithCode(code: Int): String {
        val label = resolveLabel(code)
        return if (label == "0x${code.toString(16)}" || label == code.toString()) {
            "LVL $code"
        } else {
            label
        }
    }

    /**
     * Full hardware RPMh map for this chip family (from original ChipInfo.rpmh_levels),
     * matching the exact Spinner options in GpuTableEditor.generateALevel().
     */
    val chipFamilyManualLevels: List<ReadVoltageLevel>
        get() {
            val existingCodes = readVoltageLevels.map { it.code }.toSet()
            return KnownRpmhLabels.getManualLevelsForChip(chipType).map { lvl ->
                lvl.copy(isFromExistingTable = lvl.code in existingCodes)
            }
        }
}

/**
 * Exact Qualcomm RPMh integer-to-name lookup from libxzr/KonaBess ChipInfo.rpmh_levels
 * and KonaBessStr.convert_bins.
 */
object KnownRpmhLabels {

    private data class LevelTable(
        val levels: IntArray,
        val labels: Array<String>
    )

    // Exact rpmh_levels_kona / lahaina_singleBin / lito / lagoon / shima / yupik / waipio / cape / diwali / ukee
    private val konaAndWaipioRpmh = LevelTable(
        levels = intArrayOf(
            16, 48, 56, 64, 80, 96, 128, 144, 192, 224, 256, 320,
            336, 352, 384, 400, 416
        ),
        labels = arrayOf(
            "RETENTION",
            "MIN_SVS",
            "LOW_SVS_D1",
            "LOW_SVS",
            "LOW_SVS_L1",
            "LOW_SVS_L2",
            "SVS",
            "SVS_L0",
            "SVS_L1",
            "SVS_L2",
            "NOM",
            "NOM_L1",
            "NOM_L2",
            "NOM_L3",
            "TURBO",
            "TURBO_L0",
            "TURBO_L1"
        )
    )

    // Exact rpmh_levels_msmnile (RPMH_REGULATOR_LEVEL_OFFSET = 1)
    private val msmnileRpmh = LevelTable(
        levels = intArrayOf(
            17, 49, 57, 65, 81, 97, 129, 145, 193, 225, 257, 321,
            337, 353, 385, 401, 417
        ),
        labels = arrayOf(
            "RETENTION",
            "MIN_SVS",
            "LOW_SVS_D1",
            "LOW_SVS",
            "LOW_SVS_L1",
            "LOW_SVS_L2",
            "SVS",
            "SVS_L0",
            "SVS_L1",
            "SVS_L2",
            "NOM",
            "NOM_L1",
            "NOM_L2",
            "NOM_L3",
            "TURBO",
            "TURBO_L0",
            "TURBO_L1"
        )
    )

    // Exact rpmh_levels_lahaina
    private val lahainaRpmh = LevelTable(
        levels = intArrayOf(
            16, 48, 56, 64, 80, 96, 128, 144, 192, 224, 256, 320,
            336, 352, 384, 400, 416, 432, 448, 464
        ),
        labels = arrayOf(
            "RETENTION",
            "MIN_SVS",
            "LOW_SVS_D1",
            "LOW_SVS",
            "LOW_SVS_L1",
            "LOW_SVS_L2",
            "SVS",
            "SVS_L0",
            "SVS_L1",
            "SVS_L2",
            "NOM",
            "NOM_L1",
            "NOM_L2",
            "NOM_L3",
            "TURBO",
            "TURBO_L0",
            "TURBO_L1",
            "TURBO_L2",
            "SUPER_TURBO",
            "SUPER_TURBO_NO_CPR"
        )
    )

    // Exact rpmh_levels_kalama / rpmh_levels_pineapple / rpmh_levels_cliffs
    private val kalamaAndPineappleRpmh = LevelTable(
        levels = intArrayOf(
            16, 48, 52, 56, 60, 64, 72, 80, 96, 128, 144, 192,
            224, 256, 288, 320, 336, 384, 400, 416, 432, 448, 464, 480
        ),
        labels = arrayOf(
            "RETENTION",
            "MIN_SVS",
            "LOW_SVS_D2",
            "LOW_SVS_D1",
            "LOW_SVS_D0",
            "LOW_SVS",
            "LOW_SVS_P1",
            "LOW_SVS_L1",
            "LOW_SVS_L2",
            "SVS",
            "SVS_L0",
            "SVS_L1",
            "SVS_L2",
            "NOM",
            "NOM_L0",
            "NOM_L1",
            "NOM_L2",
            "TURBO",
            "TURBO_L0",
            "TURBO_L1",
            "TURBO_L2",
            "TURBO_L3",
            "SUPER_TURBO",
            "SUPER_TURBO_NO_CPR"
        )
    )

    // Exact rpmh_levels_sun
    private val sunRpmh = LevelTable(
        levels = intArrayOf(
            16, 48, 50, 52, 56, 60, 64, 72, 80, 96, 128, 144, 192,
            224, 256, 288, 320, 336, 384, 400, 416, 432, 448, 452, 464, 480
        ),
        labels = arrayOf(
            "RETENTION",
            "MIN_SVS",
            "LOW_SVS_D3",
            "LOW_SVS_D2",
            "LOW_SVS_D1",
            "LOW_SVS_D0",
            "LOW_SVS",
            "LOW_SVS_P1",
            "LOW_SVS_L1",
            "LOW_SVS_L2",
            "SVS",
            "SVS_L0",
            "SVS_L1",
            "SVS_L2",
            "NOM",
            "NOM_L0",
            "NOM_L1",
            "NOM_L2",
            "TURBO",
            "TURBO_L0",
            "TURBO_L1",
            "TURBO_L2",
            "TURBO_L3",
            "TURBO_L4",
            "SUPER_TURBO",
            "SUPER_TURBO_NO_CPR"
        )
    )

    // Exact rpmh_levels_canoe (superset of all newer RPMh levels)
    private val canoeRpmh = LevelTable(
        levels = intArrayOf(
            16, 48, 50, 51, 52, 54, 56, 60, 64, 72, 80, 96, 128, 144, 192,
            224, 256, 288, 320, 336, 384, 400, 416, 432, 448, 452, 464, 480
        ),
        labels = arrayOf(
            "RETENTION",
            "MIN_SVS",
            "LOW_SVS_D3",
            "LOW_SVS_D2_5",
            "LOW_SVS_D2",
            "LOW_SVS_D1_5",
            "LOW_SVS_D1",
            "LOW_SVS_D0",
            "LOW_SVS",
            "LOW_SVS_P1",
            "LOW_SVS_L1",
            "LOW_SVS_L2",
            "SVS",
            "SVS_L0",
            "SVS_L1",
            "SVS_L2",
            "NOM",
            "NOM_L0",
            "NOM_L1",
            "NOM_L2",
            "TURBO",
            "TURBO_L0",
            "TURBO_L1",
            "TURBO_L2",
            "TURBO_L3",
            "TURBO_L4",
            "SUPER_TURBO",
            "SUPER_TURBO_NO_CPR"
        )
    )

    private fun tableForChip(chipType: KonaBessChipType): LevelTable {
        return when (chipType) {
            KonaBessChipType.kona,
            KonaBessChipType.kona_singleBin,
            KonaBessChipType.lahaina_singleBin,
            KonaBessChipType.lito_v1,
            KonaBessChipType.lito_v2,
            KonaBessChipType.lagoon,
            KonaBessChipType.shima,
            KonaBessChipType.yupik,
            KonaBessChipType.waipio_singleBin,
            KonaBessChipType.cape_singleBin,
            KonaBessChipType.diwali,
            KonaBessChipType.ukee_singleBin -> konaAndWaipioRpmh

            KonaBessChipType.msmnile,
            KonaBessChipType.msmnile_singleBin -> msmnileRpmh

            KonaBessChipType.lahaina -> lahainaRpmh

            KonaBessChipType.kalama,
            KonaBessChipType.kalama_sg_singleBin,
            KonaBessChipType.pineapple,
            KonaBessChipType.cliffs_singleBin,
            KonaBessChipType.cliffs_7_singleBin -> kalamaAndPineappleRpmh

            KonaBessChipType.sun -> sunRpmh
            KonaBessChipType.canoe -> canoeRpmh
            KonaBessChipType.unknown -> kalamaAndPineappleRpmh
        }
    }

    fun decodeKnownCodeOrRaw(
        code: Int,
        chipType: KonaBessChipType = KonaBessChipType.unknown
    ): String {
        val primary = tableForChip(chipType)
        val idx = primary.levels.indexOf(code)
        if (idx >= 0) return primary.labels[idx]

        // Fallback across all known Qualcomm RPMh tables if chipType was unknown or custom
        for (fallback in listOf(kalamaAndPineappleRpmh, canoeRpmh, konaAndWaipioRpmh, lahainaRpmh, msmnileRpmh)) {
            val fbIdx = fallback.levels.indexOf(code)
            if (fbIdx >= 0) return fallback.labels[fbIdx]
        }

        return "$code - LEVEL_$code"
    }

    fun encodeLabelToCodeIfKnown(
        label: String,
        chipType: KonaBessChipType = KonaBessChipType.unknown
    ): Int? {
        val normalized = label.trim()
        if (normalized.contains(" - ")) {
            normalized.substringBefore(" - ").trim().toIntOrNull()?.let { return it }
        }
        val upper = normalized.uppercase()
        val primary = tableForChip(chipType)
        val primaryIdx = primary.labels.indexOfFirst { it.equals(upper, ignoreCase = true) }
        if (primaryIdx >= 0) return primary.levels[primaryIdx]

        for (fallback in listOf(kalamaAndPineappleRpmh, canoeRpmh, konaAndWaipioRpmh, lahainaRpmh, msmnileRpmh)) {
            val fbIdx = fallback.labels.indexOfFirst { it.equals(upper, ignoreCase = true) }
            if (fbIdx >= 0) return fallback.levels[fbIdx]
        }

        if (upper.startsWith("LEVEL_")) {
            return upper.removePrefix("LEVEL_").toIntOrNull()
        }
        return upper.removePrefix("0X").toIntOrNull(16) ?: upper.toIntOrNull()
    }

    fun getManualLevelsForChip(chipType: KonaBessChipType): List<ReadVoltageLevel> {
        val table = tableForChip(chipType)
        return table.levels.mapIndexed { idx, code ->
            ReadVoltageLevel(
                code = code,
                label = table.labels[idx],
                isFromExistingTable = false
            )
        }
    }

    /**
     * Exact bin label translation from libxzr/KonaBess KonaBessStr.convert_bins().
     */
    fun formatBinDisplayName(binId: Int, chipType: KonaBessChipType): String {
        return when (chipType) {
            KonaBessChipType.kona -> when (binId) {
                0 -> "Snapdragon 865"
                1 -> "Snapdragon 865+"
                2 -> "Snapdragon 865 Ultra (Overclocked)"
                3 -> "Snapdragon 870"
                else -> "Bin $binId"
            }
            KonaBessChipType.kona_singleBin -> when (binId) {
                0 -> "Snapdragon 865 (Single Bin)"
                else -> "Bin $binId"
            }
            KonaBessChipType.msmnile -> when (binId) {
                0 -> "Snapdragon 855"
                1 -> "Snapdragon 855+"
                else -> "Bin $binId"
            }
            KonaBessChipType.msmnile_singleBin -> when (binId) {
                0 -> "Snapdragon 855 (Single Bin)"
                else -> "Bin $binId"
            }
            KonaBessChipType.lahaina -> when (binId) {
                0 -> "Snapdragon 888"
                3 -> "Snapdragon 888+"
                else -> "Bin $binId"
            }
            KonaBessChipType.lahaina_singleBin -> when (binId) {
                0 -> "Snapdragon 888 (Single Bin)"
                else -> "Bin $binId"
            }
            KonaBessChipType.lito_v1, KonaBessChipType.lito_v2 -> when (binId) {
                1 -> "Snapdragon 765G"
                3 -> "Snapdragon 765"
                else -> "Bin $binId"
            }
            KonaBessChipType.lagoon -> when (binId) {
                2 -> "Snapdragon 750G"
                else -> "Bin $binId"
            }
            KonaBessChipType.shima -> when (binId) {
                1 -> "Snapdragon 780G"
                else -> "Bin $binId"
            }
            KonaBessChipType.waipio_singleBin -> when (binId) {
                0 -> "Snapdragon 8 Gen 1"
                else -> "Bin $binId"
            }
            KonaBessChipType.cape_singleBin -> when (binId) {
                0 -> "Snapdragon 8+ Gen 1"
                else -> "Bin $binId"
            }
            KonaBessChipType.kalama -> when (binId) {
                0 -> "Snapdragon 8 Gen 2 for Galaxy"
                1 -> "Snapdragon 8 Gen 2"
                else -> "Bin $binId"
            }
            KonaBessChipType.kalama_sg_singleBin -> when (binId) {
                0 -> "Snapdragon G3x Gen 2"
                else -> "Bin $binId"
            }
            KonaBessChipType.diwali -> when (binId) {
                3 -> "Snapdragon 7 Gen 1"
                else -> "Bin $binId"
            }
            KonaBessChipType.ukee_singleBin -> when (binId) {
                0 -> "Snapdragon 7+ Gen 2"
                else -> "Bin $binId"
            }
            KonaBessChipType.pineapple -> when (binId) {
                0 -> "Snapdragon 8 Gen 3 for Galaxy"
                1 -> "Snapdragon 8 Gen 3"
                else -> "Bin $binId"
            }
            KonaBessChipType.cliffs_singleBin -> when (binId) {
                0 -> "Snapdragon 8s Gen 3"
                else -> "Bin $binId"
            }
            KonaBessChipType.cliffs_7_singleBin -> when (binId) {
                0 -> "Snapdragon 7+ Gen 3"
                else -> "Bin $binId"
            }
            KonaBessChipType.sun -> when (binId) {
                0 -> "Snapdragon 8 Elite (1)"
                1 -> "Snapdragon 8 Elite (0)"
                2 -> "Snapdragon 8 Elite for Galaxy"
                else -> "Bin $binId"
            }
            KonaBessChipType.canoe -> when (binId) {
                0 -> "Snapdragon 8 Elite Gen 5 (0)"
                1 -> "Snapdragon 8 Elite Gen 5 (1)"
                else -> "Bin $binId"
            }
            else -> "Bin $binId"
        }
    }
}

/**
 * Represents a single GPU frequency step (`qcom,gpu-pwrlevel@X`) inside a `qcom,gpu-pwrlevels` bin.
 * Preserves the exact original DTS lines (`rawLines`) just like GpuTableEditor.level in KonaBess.
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
    val extraProperties: Map<String, String> = emptyMap(),
    val rawLines: List<String> = emptyList()
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
 * Represents a GPU Power Level Bin (`qcom,gpu-pwrlevels` or `qcom,gpu-pwrlevels-X`)
 * parsed from the device tree, along with the immutable `stockLevels` read at unpack time
 * and the hidden trailing 0 Hz idle level (`trailingZeroFreqLevelLines`) that original KonaBess
 * keeps at the end of `bin.levels`.
 */
data class GpuPowerBin(
    val binIndex: Int,
    val nodeName: String,
    val speedBinValue: Int? = null,
    val initialPwrLevel: Int = 1,
    val throttlePwrLevel: Int? = null,
    val headerProperties: Map<String, String> = emptyMap(),
    val rawHeaderLines: List<String> = emptyList(),
    val levels: List<GpuFreqRow>,
    val stockLevels: List<GpuFreqRow>,
    val trailingZeroFreqLevelLines: List<String>? = null
) {
    val modifiedRowCount: Int
        get() = levels.mapIndexed { idx, row ->
            val stock = stockLevels.getOrNull(idx)
            stock == null || stock.voltageLevelCode != row.voltageLevelCode || stock.frequencyHz != row.frequencyHz
        }.count { it }
}

/**
 * Represents an entry in `qcom,gpu-opp-table` (`gpu-opp-table`) for chips that define an explicit
 * frequency-to-voltage OPP curve alongside `qcom,gpu-pwrlevels` (GpuVoltEditor.java).
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
 * Maintains the original full `.dts` lines (`originalDtsLines`), `binPosition`, and `voltPosition`
 * so `DtsParser.serializeToDts()` performs an exact in-place splice (`GpuTableEditor.genBack`)
 * without ever losing a single line of the decompiled device tree!
 */
data class ParsedDtsWorkspace(
    val chipProfile: DynamicChipProfile,
    val bins: List<GpuPowerBin>,
    val oppVoltRows: List<GpuOppVoltRow>,
    val rawHeaderBlock: String,
    val rawFooterBlock: String,
    val originalDtsLinesConsideringSplice: List<String> = emptyList(),
    val binSplicePosition: Int = -1,
    val voltSplicePosition: Int = -1,
    val oppTableHeaderLines: List<String> = emptyList()
)
