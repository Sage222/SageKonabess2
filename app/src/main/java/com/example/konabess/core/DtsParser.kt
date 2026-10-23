package com.example.konabess.core

import android.util.Base64
import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuOppVoltRow
import com.example.konabess.model.GpuPowerBin
import com.example.konabess.model.KnownRpmhLabels
import com.example.konabess.model.KonaBessChipType
import com.example.konabess.model.ParsedDtsWorkspace
import com.example.konabess.model.ReadVoltageLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Authentic implementation of libxzr/KonaBess DtsHelper, ChipInfo, GpuTableEditor,
 * GpuVoltEditor, and TableIO.
 *
 * CRITICAL GUARANTEE:
 * - Never invents or assumes frequencies or voltage levels not present in the device's DTB.
 * - Preserves all surrounding DTS lines in `originalDtsLinesConsideringSplice` and splices
 *   modified `qcom,gpu-pwrlevels` and `gpu-opp-table` blocks back in-place at `binSplicePosition`
 *   and `voltSplicePosition` (matching `GpuTableEditor.genBack(genTable())`).
 * - Handles the `dtc` decompiler quirk `decode_stringed_int` where 32-bit cells ending in `0x00`
 *   with 3 printable ASCII bytes are emitted by `dtc` as `"..."` strings.
 * - Preserves the trailing `qcom,gpu-freq = <0>` idle level at the end of each bin while hiding
 *   it from the visible frequency list, matching `GpuTableEditor.generateLevels()`.
 */
object DtsParser {

    /**
     * Exact port of `KonaBessCore.checkDevice()` + `KonaBessCore.checkChip()` from `libxzr/KonaBess`.
     *
     * CRITICAL: Upstream KonaBess ONLY matches DTBs whose `model = "...";` line contains the exact
     * production SoC model string (e.g., `"Kalama v2"` or `"KalamaP v2"`, NOT `"Kalama"`,
     * `"Kalama IOT"`, or `"Kalama HHG"`). This ensures that on multi-DTB vendor_boot images
     * (like Snapdragon 8 Gen 2 which packs 4 DTBs: Kalama, Kalama v2, Kalama IOT, Kalama HHG),
     * ONLY the single production DTB (`Kalama v2`) is matched and exposed—identical to original KonaBess.
     */
    fun detectChipTypeStrict(lines: List<String>, deviceName: String = ""): Pair<KonaBessChipType, String> {
        var modelLine = ""
        var modelDesc = ""
        var hasSingleBinNode = false
        var hasMultiBinNode = false

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("model =") && modelLine.isEmpty()) {
                modelLine = line
                modelDesc = line.substringAfter("=").removeSuffix(";").trim().removeSurrounding("\"")
            } else if (line.startsWith("qcom,gpu-pwrlevels {")) {
                hasSingleBinNode = true
            } else if (line.startsWith("qcom,gpu-pwrlevels-")) {
                hasMultiBinNode = true
            }
        }

        val isSingleBin = hasSingleBinNode && !hasMultiBinNode
        fun checkChip(chip: String): Boolean = modelLine.contains(chip)

        val detectedType = when {
            checkChip("kona v2.1") ->
                if (deviceName != "OP4E5D") KonaBessChipType.kona else KonaBessChipType.kona_singleBin
            checkChip("SM8150 v2") ->
                if (isSingleBin) KonaBessChipType.msmnile_singleBin else KonaBessChipType.msmnile
            checkChip("Lahaina V2.1") || checkChip("Lahaina v2.1") ->
                if (isSingleBin) KonaBessChipType.lahaina_singleBin else KonaBessChipType.lahaina
            checkChip("Lito v2") || checkChip("Lito") ->
                if (checkChip("v2")) KonaBessChipType.lito_v2 else KonaBessChipType.lito_v1
            checkChip("Lagoon") ->
                KonaBessChipType.lagoon
            checkChip("Shima") ->
                KonaBessChipType.shima
            checkChip("Yupik") ->
                KonaBessChipType.yupik
            checkChip("Waipio") || checkChip("Waipio v2") ->
                KonaBessChipType.waipio_singleBin
            checkChip("Cape") ->
                KonaBessChipType.cape_singleBin
            checkChip("KalamaP SG SoC") ->
                KonaBessChipType.kalama_sg_singleBin
            checkChip("Kalama v2") || checkChip("KalamaP v2") ->
                KonaBessChipType.kalama
            checkChip("Diwali") ->
                KonaBessChipType.diwali
            checkChip("Ukee") ->
                KonaBessChipType.ukee_singleBin
            checkChip("Pineapple v2") || checkChip("PineappleP v2") ->
                KonaBessChipType.pineapple
            checkChip("Cliffs SoC") ->
                KonaBessChipType.cliffs_singleBin
            checkChip("Cliffs 7 SoC") ->
                KonaBessChipType.cliffs_7_singleBin
            checkChip("Sun v2 SoC") ||
                checkChip("Sun Alt. Thermal Profile v2 SoC") ||
                checkChip("SunP v2 SoC") ||
                checkChip("SunP v2 Alt. Thermal Profile SoC") ->
                KonaBessChipType.sun
            checkChip("Canoe v2 SoC") || checkChip("CanoeP v2 SoC") ->
                KonaBessChipType.canoe
            else ->
                KonaBessChipType.unknown
        }

        return Pair(detectedType, modelDesc)
    }

    /**
     * Detects the chip type from a decompiled `.dts` file or imported `konabess://` snippet.
     * First applies the strict `KonaBessCore.checkDevice()` model check; if importing a snippet
     * that lacks the full `model` string, falls back to `compatible`, `fallbackChipType`, or
     * RPMh level signature detection.
     */
    fun detectChipType(
        lines: List<String>,
        fallbackChipType: KonaBessChipType = KonaBessChipType.unknown
    ): Pair<KonaBessChipType, String> {
        val (strictType, strictModel) = detectChipTypeStrict(lines)
        if (strictType != KonaBessChipType.unknown) {
            return Pair(strictType, strictModel)
        }

        var modelDesc = strictModel
        var compatibleLine = ""
        var hasSingleBinNode = false
        var hasMultiBinNode = false
        var hasKalamaD2OrD0Level = false

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("model =") && modelDesc.isEmpty()) {
                modelDesc = line.substringAfter("=").removeSuffix(";").trim().removeSurrounding("\"")
            } else if (line.startsWith("compatible =") && compatibleLine.isEmpty()) {
                compatibleLine = line.substringAfter("=").removeSuffix(";").trim()
            } else if (line.startsWith("qcom,gpu-pwrlevels {")) {
                hasSingleBinNode = true
            } else if (line.startsWith("qcom,gpu-pwrlevels-")) {
                hasMultiBinNode = true
            } else if (line.startsWith("qcom,level =") || line.startsWith("qcom,cx-level =")) {
                val code = decodeIntLine(line).toInt()
                if (code == 52 || code == 60 || code == 72) {
                    hasKalamaD2OrD0Level = true
                }
            }
        }

        val combined = "$modelDesc $compatibleLine"
        val detectedType = when {
            combined.contains("Canoe", ignoreCase = true) || compatibleLine.contains("qcom,canoe") ->
                KonaBessChipType.canoe
            combined.contains("Sun", ignoreCase = true) || compatibleLine.contains("qcom,sun") ->
                KonaBessChipType.sun
            compatibleLine.contains("qcom,cliffs") && modelDesc.contains("7") ->
                KonaBessChipType.cliffs_7_singleBin
            compatibleLine.contains("qcom,cliffs") ->
                KonaBessChipType.cliffs_singleBin
            compatibleLine.contains("qcom,pineapple") || combined.contains("Pineapple", ignoreCase = true) ->
                KonaBessChipType.pineapple
            compatibleLine.contains("qcom,ukee") || combined.contains("Ukee", ignoreCase = true) ->
                KonaBessChipType.ukee_singleBin
            compatibleLine.contains("qcom,diwali") || combined.contains("Diwali", ignoreCase = true) ->
                KonaBessChipType.diwali
            (compatibleLine.contains("qcom,kalama") || combined.contains("Kalama", ignoreCase = true)) &&
                (modelDesc.contains("SG") || (hasSingleBinNode && !hasMultiBinNode)) ->
                KonaBessChipType.kalama_sg_singleBin
            compatibleLine.contains("qcom,kalama") || combined.contains("Kalama", ignoreCase = true) ->
                KonaBessChipType.kalama
            compatibleLine.contains("qcom,cape") || combined.contains("Cape", ignoreCase = true) ->
                KonaBessChipType.cape_singleBin
            compatibleLine.contains("qcom,waipio") || combined.contains("Waipio", ignoreCase = true) ->
                KonaBessChipType.waipio_singleBin
            compatibleLine.contains("qcom,yupik") || combined.contains("Yupik", ignoreCase = true) ->
                KonaBessChipType.yupik
            compatibleLine.contains("qcom,shima") || combined.contains("Shima", ignoreCase = true) ->
                KonaBessChipType.shima
            compatibleLine.contains("qcom,lagoon") || combined.contains("Lagoon", ignoreCase = true) ->
                KonaBessChipType.lagoon
            (compatibleLine.contains("qcom,lito") || combined.contains("Lito", ignoreCase = true)) &&
                modelDesc.contains("v2", ignoreCase = true) ->
                KonaBessChipType.lito_v2
            compatibleLine.contains("qcom,lito") || combined.contains("Lito", ignoreCase = true) ->
                KonaBessChipType.lito_v1
            compatibleLine.contains("qcom,lahaina") || combined.contains("Lahaina", ignoreCase = true) ->
                if (hasSingleBinNode && !hasMultiBinNode) KonaBessChipType.lahaina_singleBin else KonaBessChipType.lahaina
            compatibleLine.contains("qcom,kona") || compatibleLine.contains("qcom,sm8250") || combined.contains("Kona", ignoreCase = true) ->
                if (hasSingleBinNode && !hasMultiBinNode) KonaBessChipType.kona_singleBin else KonaBessChipType.kona
            compatibleLine.contains("qcom,msmnile") || compatibleLine.contains("qcom,sm8150") || combined.contains("SM8150", ignoreCase = true) ->
                if (hasSingleBinNode && !hasMultiBinNode) KonaBessChipType.msmnile_singleBin else KonaBessChipType.msmnile
            fallbackChipType != KonaBessChipType.unknown ->
                fallbackChipType
            hasKalamaD2OrD0Level ->
                if (hasSingleBinNode && !hasMultiBinNode) KonaBessChipType.kalama_sg_singleBin else KonaBessChipType.kalama
            hasSingleBinNode && !hasMultiBinNode ->
                KonaBessChipType.kona_singleBin
            hasMultiBinNode ->
                KonaBessChipType.kona
            else ->
                KonaBessChipType.unknown
        }

        return Pair(detectedType, modelDesc)
    }

    fun parseDts(
        dtsContent: String,
        sourcePartition: String = "vendor_boot / boot",
        activeDtbId: Int = -1,
        fallbackChipType: KonaBessChipType = KonaBessChipType.unknown
    ): ParsedDtsWorkspace {
        if (dtsContent.isBlank()) {
            val emptyProfile = DynamicChipProfile(
                modelName = "No Unpacked Device Tree Loaded",
                chipType = KonaBessChipType.unknown,
                compatibleTokens = emptyList(),
                msmIdHex = null,
                levelPropertyKey = "qcom,level",
                readVoltageLevels = emptyList(),
                hasSeparateVoltTable = false,
                sourcePartition = sourcePartition,
                activeDtbId = activeDtbId
            )
            return ParsedDtsWorkspace(
                chipProfile = emptyProfile,
                bins = emptyList(),
                oppVoltRows = emptyList(),
                rawHeaderBlock = "",
                rawFooterBlock = ""
            )
        }

        val allLines = dtsContent.lines()
        val (chipType, detectedModelDesc) = detectChipType(allLines, fallbackChipType)

        var modelName = detectedModelDesc
        val compatibleTokens = mutableListOf<String>()
        var msmIdHex: String? = null
        var detectedLevelKey = "qcom,level"

        for (rawLine in allLines) {
            val line = rawLine.trim()
            if (line.startsWith("model =") && modelName.isEmpty()) {
                modelName = extractQuotedStrings(line).firstOrNull().orEmpty()
            } else if (line.startsWith("compatible =") && compatibleTokens.isEmpty()) {
                compatibleTokens.addAll(extractQuotedStrings(line))
            } else if (line.startsWith("qcom,msm-id =") && msmIdHex == null) {
                msmIdHex = decodeHexLine(line)
            } else if (line.startsWith("qcom,cx-ipeak =")) {
                detectedLevelKey = "qcom,cx-ipeak"
            } else if (line.startsWith("qcom,cx-level =")) {
                detectedLevelKey = "qcom,cx-level"
            } else if (line.startsWith("qcom,level =")) {
                detectedLevelKey = "qcom,level"
            }
        }

        // Decode bins and splice points using exact GpuTableEditor algorithm
        val workingLines = ArrayList(allLines)
        val (bins, binPosition) = decodeAndExtractPowerBins(workingLines, chipType)

        // Decode optional gpu-opp-table (for chips that don't ignore volt table)
        val (oppRows, voltPosition, oppHeaderLines) = if (!chipType.voltTableIgnore) {
            decodeAndExtractOppVoltTable(workingLines)
        } else {
            Triple(emptyList(), -1, emptyList())
        }

        // Collect ONLY the voltage levels that actually exist in the read DTS table
        val existingLevelCodes = linkedSetOf<Int>()
        bins.forEach { bin ->
            bin.levels.forEach { row ->
                existingLevelCodes.add(row.voltageLevelCode)
            }
        }
        oppRows.forEach { opp ->
            if (opp.microvoltOrLevel in 1..1000L) {
                existingLevelCodes.add(opp.microvoltOrLevel.toInt())
            }
        }

        val sortedReadLevels = existingLevelCodes
            .sorted()
            .map { code ->
                ReadVoltageLevel(
                    code = code,
                    label = KnownRpmhLabels.decodeKnownCodeOrRaw(code, chipType),
                    isFromExistingTable = true
                )
            }

        val resolvedModelName = when {
            modelName.isNotBlank() -> modelName
            compatibleTokens.isNotEmpty() -> compatibleTokens.joinToString(" / ")
            else -> "Qualcomm Snapdragon Adreno GPU"
        }

        val chipProfile = DynamicChipProfile(
            modelName = resolvedModelName,
            chipType = chipType,
            compatibleTokens = compatibleTokens,
            msmIdHex = msmIdHex,
            levelPropertyKey = detectedLevelKey,
            readVoltageLevels = sortedReadLevels,
            hasSeparateVoltTable = oppRows.isNotEmpty(),
            sourcePartition = sourcePartition,
            activeDtbId = activeDtbId
        )

        return ParsedDtsWorkspace(
            chipProfile = chipProfile,
            bins = bins,
            oppVoltRows = oppRows,
            rawHeaderBlock = extractHeaderMetadataComment(resolvedModelName, msmIdHex),
            rawFooterBlock = "",
            originalDtsLinesConsideringSplice = workingLines,
            binSplicePosition = binPosition,
            voltSplicePosition = voltPosition,
            oppTableHeaderLines = oppHeaderLines
        )
    }

    /**
     * Exact port of `GpuTableEditor.decode()` from `libxzr/KonaBess`:
     * Finds `qcom,gpu-pwrlevels {` or `qcom,gpu-pwrlevels-X {`, records `bin_position`,
     * removes the original bin lines from `linesInDts`, and parses each bin's header + levels.
     */
    private fun decodeAndExtractPowerBins(
        linesInDts: MutableList<String>,
        chipType: KonaBessChipType
    ): Pair<List<GpuPowerBin>, Int> {
        val bins = mutableListOf<GpuPowerBin>()
        var binPosition = -1
        var i = 0
        var fallbackBinCounter = 0

        while (i < linesInDts.size) {
            val trimmed = linesInDts[i].trim()
            if (isBinNodeStart(trimmed, chipType.isSingleBin)) {
                if (binPosition < 0) {
                    binPosition = i
                }
                val openLine = linesInDts.removeAt(i)
                val nodeName = openLine.trim().substringBefore("{").trim()
                val binId = if (nodeName.contains("qcom,gpu-pwrlevels-")) {
                    nodeName.substringAfterLast("qcom,gpu-pwrlevels-").trim().toIntOrNull() ?: fallbackBinCounter
                } else {
                    fallbackBinCounter
                }

                var bracket = countChar(openLine, '{') - countChar(openLine, '}')
                val rawHeaderLines = mutableListOf<String>()
                val headerProps = linkedMapOf<String, String>()
                var speedBinValue: Int? = null
                var initialPwrLevel = 1
                var throttlePwrLevel: Int? = null
                val rawLevelBlocks = mutableListOf<List<String>>()

                while (i < linesInDts.size && bracket > 0) {
                    val currentRaw = linesInDts.removeAt(i)
                    val currentTrimmed = currentRaw.trim()

                    if (isPwrLevelNodeStart(currentTrimmed)) {
                        val levelLines = mutableListOf<String>()
                        var levelBracket = countChar(currentRaw, '{') - countChar(currentRaw, '}')
                        while (i < linesInDts.size && levelBracket > 0) {
                            val lvlLine = linesInDts.removeAt(i)
                            levelBracket += countChar(lvlLine, '{') - countChar(lvlLine, '}')
                            if (levelBracket > 0) {
                                levelLines.add(lvlLine.trim())
                            }
                        }
                        rawLevelBlocks.add(levelLines)
                        continue
                    }

                    bracket += countChar(currentRaw, '{') - countChar(currentRaw, '}')
                    if (bracket > 0 && currentTrimmed.isNotEmpty()) {
                        rawHeaderLines.add(currentTrimmed)
                        if (currentTrimmed.contains("=") && !currentTrimmed.endsWith("{")) {
                            val key = currentTrimmed.substringBefore("=").trim()
                            val rawVal = currentTrimmed.substringAfter("=").substringBefore(";").trim()
                            when (key) {
                                "qcom,gpu-speed-bin" -> speedBinValue = decodeIntLine(currentTrimmed).toInt()
                                "qcom,initial-pwrlevel" -> initialPwrLevel = decodeIntLine(currentTrimmed).toInt()
                                "qcom,throttle-pwrlevel" -> throttlePwrLevel = decodeIntLine(currentTrimmed).toInt()
                                else -> if (key != "compatible" && key != "#address-cells" && key != "#size-cells") {
                                    headerProps[key] = rawVal
                                }
                            }
                        }
                    }
                }

                // Separate visible operating levels (freq > 0) from the trailing 0 Hz idle level
                // exactly as GpuTableEditor.generateLevels() does in original KonaBess.
                val visibleRows = mutableListOf<GpuFreqRow>()
                var trailingZeroLevelLines: List<String>? = null

                rawLevelBlocks.forEachIndexed { idx, lvlLines ->
                    val freqLine = lvlLines.firstOrNull { it.startsWith("qcom,gpu-freq") }
                    val freqHz = if (freqLine != null) decodeIntLine(freqLine) else 0L

                    if (freqHz == 0L && idx == rawLevelBlocks.lastIndex) {
                        trailingZeroLevelLines = lvlLines
                    } else if (freqHz > 0L) {
                        var voltCode = 0
                        var busFreq = 0
                        var busMin = 0
                        var busMax = 0
                        var acdHex: String? = null
                        val extraProps = linkedMapOf<String, String>()

                        for (prop in lvlLines) {
                            if (!prop.contains("=")) continue
                            val key = prop.substringBefore("=").trim()
                            val rawVal = prop.substringAfter("=").substringBefore(";").trim()
                            when (key) {
                                "qcom,gpu-freq" -> { /* already parsed */ }
                                "qcom,level", "qcom,cx-level", "qcom,cx-ipeak" -> voltCode = decodeIntLine(prop).toInt()
                                "qcom,bus-freq" -> busFreq = decodeIntLine(prop).toInt()
                                "qcom,bus-min" -> busMin = decodeIntLine(prop).toInt()
                                "qcom,bus-max" -> busMax = decodeIntLine(prop).toInt()
                                "qcom,acd-level" -> acdHex = decodeHexLine(prop)
                                "reg" -> { /* regenerated on genTable() */ }
                                else -> extraProps[key] = rawVal
                            }
                        }

                        visibleRows.add(
                            GpuFreqRow(
                                id = UUID.randomUUID().toString(),
                                levelIndex = visibleRows.size,
                                frequencyHz = freqHz,
                                voltageLevelCode = voltCode,
                                busFreq = busFreq,
                                busMin = busMin,
                                busMax = busMax,
                                acdLevelHex = acdHex,
                                extraProperties = extraProps,
                                rawLines = lvlLines
                            )
                        )
                    }
                }

                if (visibleRows.isNotEmpty()) {
                    val indexedRows = visibleRows.mapIndexed { idx, r -> r.copy(levelIndex = idx) }
                    bins.add(
                        GpuPowerBin(
                            binIndex = binId,
                            nodeName = nodeName,
                            speedBinValue = speedBinValue,
                            initialPwrLevel = initialPwrLevel,
                            throttlePwrLevel = throttlePwrLevel,
                            headerProperties = headerProps,
                            rawHeaderLines = rawHeaderLines,
                            levels = indexedRows,
                            stockLevels = indexedRows.map { it.copy() },
                            trailingZeroFreqLevelLines = trailingZeroLevelLines
                        )
                    )
                    fallbackBinCounter++
                }
                continue
            }
            i++
        }

        return Pair(bins, binPosition)
    }

    /**
     * Exact port of `GpuVoltEditor.decode()` for chips that use `gpu-opp-table` (`kona`, `msmnile`, `lito`, `lagoon`).
     */
    private fun decodeAndExtractOppVoltTable(
        linesInDts: MutableList<String>
    ): Triple<List<GpuOppVoltRow>, Int, List<String>> {
        val oppRows = mutableListOf<GpuOppVoltRow>()
        val headerLines = mutableListOf<String>()
        var voltPosition = -1
        var i = 0

        while (i < linesInDts.size) {
            val trimmed = linesInDts[i].trim()
            if ((trimmed.startsWith("gpu-opp-table {") || trimmed.startsWith("qcom,gpu-opp-table {")) && voltPosition < 0) {
                voltPosition = i
                val openLine = linesInDts.removeAt(i)
                var bracket = countChar(openLine, '{') - countChar(openLine, '}')
                while (i < linesInDts.size && bracket > 0) {
                    val currentRaw = linesInDts.removeAt(i)
                    val currentTrimmed = currentRaw.trim()
                    if (currentTrimmed.startsWith("opp-") && currentTrimmed.contains("{")) {
                        val nodeName = currentTrimmed.substringBefore("{").trim()
                        var oppBracket = countChar(currentRaw, '{') - countChar(currentRaw, '}')
                        var freqHz = 0L
                        var microvolt = 0L
                        var peakBw: Long? = null
                        while (i < linesInDts.size && oppBracket > 0) {
                            val propRaw = linesInDts.removeAt(i)
                            val prop = propRaw.trim()
                            oppBracket += countChar(propRaw, '{') - countChar(propRaw, '}')
                            if (oppBracket > 0 && prop.contains("=")) {
                                val key = prop.substringBefore("=").trim()
                                when (key) {
                                    "opp-hz" -> freqHz = decodeIntLine(prop)
                                    "opp-microvolt" -> microvolt = decodeIntLine(prop)
                                    "opp-peak-kBps" -> peakBw = decodeIntLine(prop)
                                }
                            }
                        }
                        if (freqHz > 0L) {
                            oppRows.add(
                                GpuOppVoltRow(
                                    id = UUID.randomUUID().toString(),
                                    nodeName = nodeName,
                                    frequencyHz = freqHz,
                                    microvoltOrLevel = microvolt,
                                    peakKbps = peakBw,
                                    stockMicrovoltOrLevel = microvolt
                                )
                            )
                        }
                        continue
                    }
                    bracket += countChar(currentRaw, '{') - countChar(currentRaw, '}')
                    if (bracket > 0 && currentTrimmed.isNotEmpty()) {
                        headerLines.add(currentTrimmed)
                    }
                }
                break
            }
            i++
        }

        return Triple(oppRows, voltPosition, headerLines)
    }

    /**
     * Generates the DTS lines for all `qcom,gpu-pwrlevels` bins, matching `GpuTableEditor.genTable()`
     * from `libxzr/KonaBess` (including restoring the hidden trailing 0 Hz idle level).
     */
    fun generateBinsTableLines(workspace: ParsedDtsWorkspace): List<String> {
        val lines = mutableListOf<String>()
        val levelKey = workspace.chipProfile.levelPropertyKey
        val isSingleBin = workspace.chipProfile.chipType.isSingleBin && workspace.bins.size == 1

        workspace.bins.forEach { bin ->
            val binOpenName = when {
                bin.nodeName.isNotBlank() -> bin.nodeName
                isSingleBin -> "qcom,gpu-pwrlevels"
                else -> "qcom,gpu-pwrlevels-${bin.binIndex}"
            }
            lines.add("$binOpenName {")

            if (bin.rawHeaderLines.isNotEmpty()) {
                for (hdr in bin.rawHeaderLines) {
                    val key = hdr.substringBefore("=").trim()
                    when (key) {
                        "qcom,initial-pwrlevel" ->
                            lines.add("    qcom,initial-pwrlevel = <0x${bin.initialPwrLevel.toString(16)}>;")
                        "qcom,throttle-pwrlevel" ->
                            if (bin.throttlePwrLevel != null) {
                                lines.add("    qcom,throttle-pwrlevel = <0x${bin.throttlePwrLevel.toString(16)}>;")
                            } else {
                                lines.add("    $hdr")
                            }
                        else -> lines.add("    $hdr")
                    }
                }
            } else {
                lines.add("    compatible = \"qcom,gpu-pwrlevels\";")
                if (bin.speedBinValue != null) {
                    lines.add("    qcom,gpu-speed-bin = <0x${bin.speedBinValue.toString(16)}>;")
                }
                lines.add("    qcom,initial-pwrlevel = <0x${bin.initialPwrLevel.toString(16)}>;")
                if (bin.throttlePwrLevel != null) {
                    lines.add("    qcom,throttle-pwrlevel = <0x${bin.throttlePwrLevel.toString(16)}>;")
                }
                bin.headerProperties.forEach { (k, v) ->
                    lines.add("    $k = $v;")
                }
            }

            bin.levels.forEachIndexed { idx, row ->
                lines.add("    qcom,gpu-pwrlevel@$idx {")
                val levelPropStartIndex = lines.size
                if (row.rawLines.isNotEmpty()) {
                    var wroteReg = false
                    var wroteFreq = false
                    var wroteLevel = false
                    var wroteBusFreq = false
                    var wroteBusMin = false
                    var wroteBusMax = false
                    var wroteAcd = false

                    for (origLine in row.rawLines) {
                        val key = origLine.substringBefore("=").trim()
                        when (key) {
                            "reg" -> {
                                lines.add("        reg = <0x${idx.toString(16)}>;")
                                wroteReg = true
                            }
                            "qcom,gpu-freq" -> {
                                lines.add("        qcom,gpu-freq = <0x${row.frequencyHz.toString(16)}>;")
                                wroteFreq = true
                            }
                            "qcom,level", "qcom,cx-level", "qcom,cx-ipeak" -> {
                                lines.add("        $key = <0x${row.voltageLevelCode.toString(16)}>;")
                                wroteLevel = true
                            }
                            "qcom,bus-freq" -> {
                                lines.add("        qcom,bus-freq = <0x${row.busFreq.toString(16)}>;")
                                wroteBusFreq = true
                            }
                            "qcom,bus-min" -> {
                                lines.add("        qcom,bus-min = <0x${row.busMin.toString(16)}>;")
                                wroteBusMin = true
                            }
                            "qcom,bus-max" -> {
                                lines.add("        qcom,bus-max = <0x${row.busMax.toString(16)}>;")
                                wroteBusMax = true
                            }
                            "qcom,acd-level" -> {
                                if (!row.acdLevelHex.isNullOrBlank()) {
                                    lines.add("        qcom,acd-level = <${ formatHexWithPrefix(row.acdLevelHex) }>;")
                                }
                                wroteAcd = true
                            }
                            else -> lines.add("        $origLine")
                        }
                    }
                    if (!wroteReg) lines.add(levelPropStartIndex, "        reg = <0x${idx.toString(16)}>;")
                    if (!wroteFreq) lines.add("        qcom,gpu-freq = <0x${row.frequencyHz.toString(16)}>;")
                    if (!wroteLevel) lines.add("        $levelKey = <0x${row.voltageLevelCode.toString(16)}>;")
                    if (!wroteBusFreq) lines.add("        qcom,bus-freq = <0x${row.busFreq.toString(16)}>;")
                    if (!wroteBusMin) lines.add("        qcom,bus-min = <0x${row.busMin.toString(16)}>;")
                    if (!wroteBusMax) lines.add("        qcom,bus-max = <0x${row.busMax.toString(16)}>;")
                    if (!wroteAcd && !row.acdLevelHex.isNullOrBlank()) {
                        lines.add("        qcom,acd-level = <${ formatHexWithPrefix(row.acdLevelHex) }>;")
                    }
                } else {
                    lines.add("        reg = <0x${idx.toString(16)}>;")
                    lines.add("        qcom,gpu-freq = <0x${row.frequencyHz.toString(16)}>;")
                    lines.add("        $levelKey = <0x${row.voltageLevelCode.toString(16)}>;")
                    lines.add("        qcom,bus-freq = <0x${row.busFreq.toString(16)}>;")
                    lines.add("        qcom,bus-min = <0x${row.busMin.toString(16)}>;")
                    lines.add("        qcom,bus-max = <0x${row.busMax.toString(16)}>;")
                    if (!row.acdLevelHex.isNullOrBlank()) {
                        lines.add("        qcom,acd-level = <${ formatHexWithPrefix(row.acdLevelHex) }>;")
                    }
                    row.extraProperties.forEach { (k, v) ->
                        lines.add("        $k = $v;")
                    }
                }
                lines.add("    };")
            }

            // Re-emit the untouched trailing 0 Hz idle level at index `bin.levels.size`
            val trailingZero = bin.trailingZeroFreqLevelLines
            if (trailingZero != null) {
                val zeroIdx = bin.levels.size
                lines.add("    qcom,gpu-pwrlevel@$zeroIdx {")
                for (zLine in trailingZero) {
                    val key = zLine.substringBefore("=").trim()
                    if (key == "reg") {
                        lines.add("        reg = <0x${zeroIdx.toString(16)}>;")
                    } else {
                        lines.add("        $zLine")
                    }
                }
                lines.add("    };")
            }

            lines.add("};")
        }

        return lines
    }

    private fun generateOppVoltTableLines(workspace: ParsedDtsWorkspace): List<String> {
        if (workspace.oppVoltRows.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        lines.add("gpu-opp-table {")
        if (workspace.oppTableHeaderLines.isNotEmpty()) {
            workspace.oppTableHeaderLines.forEach { lines.add("    $it") }
        } else {
            lines.add("    compatible = \"operating-points-v2\";")
        }
        workspace.oppVoltRows.forEachIndexed { index, opp ->
            val nodeName = if (opp.nodeName.isNotBlank()) opp.nodeName else "opp-$index"
            lines.add("    $nodeName {")
            lines.add("        opp-hz = <0x0 0x${opp.frequencyHz.toString(16)}>;")
            lines.add("        opp-microvolt = <0x${opp.microvoltOrLevel.toString(16)}>;")
            if (opp.peakKbps != null) {
                lines.add("        opp-peak-kBps = <0x${opp.peakKbps.toString(16)}>;")
            }
            lines.add("    };")
        }
        lines.add("};")
        return lines
    }

    /**
     * Exact port of `GpuTableEditor.genBack(genTable())` + `GpuVoltEditor.genBack(genTable())`:
     * Splices the modified GPU frequency and OPP tables back into the original unpacked `.dts` lines
     * at their exact original positions (`binSplicePosition` and `voltSplicePosition`).
     */
    fun serializeToDts(workspace: ParsedDtsWorkspace): String {
        val binLines = generateBinsTableLines(workspace)
        if (workspace.originalDtsLinesConsideringSplice.isNotEmpty() && workspace.binSplicePosition >= 0) {
            val result = ArrayList(workspace.originalDtsLinesConsideringSplice)
            val safeBinPos = workspace.binSplicePosition.coerceIn(0, result.size)
            val oppLines = generateOppVoltTableLines(workspace)

            if (workspace.voltSplicePosition >= 0 && oppLines.isNotEmpty()) {
                if (workspace.voltSplicePosition <= safeBinPos) {
                    val safeVoltPos = workspace.voltSplicePosition.coerceIn(0, result.size)
                    result.addAll(safeVoltPos, oppLines)
                    val shiftedBinPos = (safeBinPos + oppLines.size).coerceIn(0, result.size)
                    result.addAll(shiftedBinPos, binLines)
                } else {
                    result.addAll(safeBinPos, binLines)
                    val shiftedVoltPos = (workspace.voltSplicePosition + binLines.size).coerceIn(0, result.size)
                    result.addAll(shiftedVoltPos, oppLines)
                }
            } else {
                result.addAll(safeBinPos, binLines)
            }
            return result.joinToString("\n")
        }

        // Fallback if workspace was constructed from a bare snippet without surrounding DTB
        val sb = StringBuilder()
        val profile = workspace.chipProfile
        sb.appendLine("/dts-v1/;")
        sb.appendLine("/ {")
        sb.appendLine("    model = \"${profile.modelName}\";")
        if (profile.compatibleTokens.isNotEmpty()) {
            val quoted = profile.compatibleTokens.joinToString(", ") { "\"$it\"" }
            sb.appendLine("    compatible = $quoted;")
        }
        if (!profile.msmIdHex.isNullOrBlank()) {
            sb.appendLine("    qcom,msm-id = <${profile.msmIdHex}>;")
        }
        sb.appendLine("    qcom,kgsl-3d0@3d00000 {")
        val oppLines = generateOppVoltTableLines(workspace)
        oppLines.forEach { sb.appendLine("        $it") }
        sb.appendLine("        qcom,gpu-pwrlevel-bins {")
        binLines.forEach { sb.appendLine("            $it") }
        sb.appendLine("        };")
        sb.appendLine("    };")
        sb.appendLine("};")
        return sb.toString()
    }

    /**
     * Exports a `konabess://` Gzip+Base64 config string (100% compatible with `libxzr/KonaBess` `TableIO`)
     * as well as readable DTS preview lines.
     */
    fun exportKonaBessConfig(workspace: ParsedDtsWorkspace, binIndex: Int): String {
        val bin = workspace.bins.firstOrNull { it.binIndex == binIndex } ?: workspace.bins.firstOrNull()
            ?: return "# No GPU bin loaded"
        val profile = workspace.chipProfile
        val sb = StringBuilder()
        sb.appendLine("# SageKonaBess GPU Frequency & Voltage Configuration")
        sb.appendLine("# Chip: ${profile.modelName} (${profile.chipType.name})")
        sb.appendLine("# Bin: ${KnownRpmhLabels.formatBinDisplayName(bin.binIndex, profile.chipType)}")
        sb.appendLine()
        val uriPayload = exportKonaBessUri(workspace)
        if (uriPayload.isNotBlank()) {
            sb.appendLine(uriPayload)
            sb.appendLine()
        }
        val singleBinWorkspace = workspace.copy(bins = listOf(bin))
        generateBinsTableLines(singleBinWorkspace).forEach { sb.appendLine(it) }
        return sb.toString()
    }

    /**
     * Generates the exact `konabess://` Gzip + Base64 string used by `libxzr/KonaBess` `TableIO`.
     */
    fun exportKonaBessUri(workspace: ParsedDtsWorkspace): String {
        if (workspace.bins.isEmpty()) return ""
        return try {
            val tableLines = generateBinsTableLines(workspace).joinToString("\n")
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { gzip ->
                gzip.write(tableLines.toByteArray(StandardCharsets.UTF_8))
            }
            "konabess://" + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Decodes a `konabess://` Gzip + Base64 string from `libxzr/KonaBess` `TableIO` if present.
     */
    fun decodeKonaBessUriIfPresent(rawText: String): String {
        val trimmed = rawText.trim()
        val uriLine = trimmed.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("konabess://") }
            ?: return trimmed

        return try {
            val base64Part = uriLine.removePrefix("konabess://").trim()
            val compressed = Base64.decode(base64Part, Base64.DEFAULT)
            GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(StandardCharsets.UTF_8).use {
                it.readText()
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * Exact port of `DtsHelper.decode_int_line` + `DtsHelper.decode_stringed_int` from `libxzr/KonaBess`.
     *
     * Handles both standard `<0x...>` cells AND the `dtc` decompiler quirk where 32-bit integers
     * whose lowest byte is `0x00` and upper 3 bytes are ASCII characters get decompiled as `"..."`!
     */
    fun decodeIntLine(line: String): Long {
        val trimmed = line.trim().removeSuffix(";")
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx < 0) return 0L
        val rhs = trimmed.substring(eqIdx + 1).trim()
        if (rhs.startsWith("\"")) {
            return decodeStringedInt(rhs)
        }
        val openIdx = rhs.indexOf('<')
        val closeIdx = rhs.indexOf('>')
        val inside = if (openIdx >= 0 && closeIdx > openIdx) {
            rhs.substring(openIdx + 1, closeIdx).trim()
        } else {
            rhs
        }
        val tokens = inside.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size >= 2) {
            val high = parseHexOrDec(tokens[tokens.size - 2])
            val low = parseHexOrDec(tokens.last())
            return (high shl 32) or (low and 0xFFFFFFFFL)
        }
        return parseHexOrDec(tokens.firstOrNull().orEmpty())
    }

    /**
     * Exact port of `DtsHelper.decode_stringed_int` from `libxzr/KonaBess`.
     * When a 32-bit cell in DTB has 3 printable ASCII bytes followed by `0x00`, `dtc` outputs `"abc"`.
     */
    fun decodeStringedInt(input: String): Long {
        val str = input.trim().removeSurrounding("\"")
        // Unescape simple C escapes if any, or read raw chars as DtsHelper.decode_stringed_int does
        if (str.length < 3) return 0L
        val c0 = str[0].code.toLong() and 0xFFL
        val c1 = str[1].code.toLong() and 0xFFL
        val c2 = str[2].code.toLong() and 0xFFL
        return (c0 shl 24) or (c1 shl 16) or (c2 shl 8)
    }

    fun decodeHexLine(line: String): String {
        val trimmed = line.trim().removeSuffix(";")
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx >= 0) {
            val rhs = trimmed.substring(eqIdx + 1).trim()
            if (rhs.startsWith("\"")) {
                val decoded = decodeStringedInt(rhs)
                return "0x" + (decoded and 0xFFFFFFFFL).toString(16)
            }
        }
        val openIdx = line.indexOf('<')
        val closeIdx = line.indexOf('>')
        return if (openIdx >= 0 && closeIdx > openIdx) {
            line.substring(openIdx + 1, closeIdx).trim()
        } else {
            line.substringAfter("=").removeSuffix(";").trim()
        }
    }

    private fun formatHexWithPrefix(hexValue: String): String {
        val clean = hexValue.trim()
        return if (clean.startsWith("0x", ignoreCase = true) || clean.contains(" ")) {
            clean
        } else {
            "0x$clean"
        }
    }

    private fun isBinNodeStart(line: String, isSingleBin: Boolean): Boolean {
        if (line.startsWith("qcom,gpu-pwrlevel-bins")) return false
        if (isSingleBin && line.startsWith("qcom,gpu-pwrlevels {")) return true
        return line.startsWith("qcom,gpu-pwrlevels-") && line.contains("{") ||
            line.startsWith("qcom,gpu-pwrlevels {")
    }

    private fun isPwrLevelNodeStart(line: String): Boolean {
        return line.startsWith("qcom,gpu-pwrlevel@") && line.contains("{")
    }

    private fun countChar(s: String, c: Char): Int = s.count { it == c }

    private fun extractQuotedStrings(line: String): List<String> {
        val regex = Regex("\"([^\"]+)\"")
        return regex.findAll(line).map { it.groupValues[1] }.toList()
    }

    private fun parseHexOrDec(token: String): Long {
        val clean = token.trim()
        if (clean.isEmpty()) return 0L
        return if (clean.startsWith("0x", ignoreCase = true)) {
            java.lang.Long.decode(clean)
        } else {
            clean.toLongOrNull() ?: 0L
        }
    }

    private fun extractHeaderMetadataComment(modelName: String, msmIdHex: String?): String {
        return buildString {
            append("Model: $modelName")
            if (!msmIdHex.isNullOrBlank()) {
                append(" | MSM-ID: <$msmIdHex>")
            }
        }
    }
}
