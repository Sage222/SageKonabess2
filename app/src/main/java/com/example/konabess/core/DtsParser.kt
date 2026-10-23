package com.example.konabess.core

import com.example.konabess.model.DynamicChipProfile
import com.example.konabess.model.GpuFreqRow
import com.example.konabess.model.GpuOppVoltRow
import com.example.konabess.model.GpuPowerBin
import com.example.konabess.model.KnownRpmhLabels
import com.example.konabess.model.ParsedDtsWorkspace
import com.example.konabess.model.ReadVoltageLevel
import java.util.UUID

/**
 * Parses and serializes Qualcomm Adreno GPU Device Tree Source (DTS) files and KonaBess
 * configuration exports.
 *
 * CRITICAL PRINCIPLE: Never assumes a fixed ChipDefinition or global voltage level list.
 * All available voltage levels and properties are constructed strictly from the default reading
 * of the existing values in the unpacked/imported DTS.
 */
object DtsParser {

    fun parseDts(dtsContent: String, sourcePartition: String = "boot / vendor_boot"): ParsedDtsWorkspace {
        val lines = dtsContent.lines()

        var modelName = ""
        val compatibleTokens = mutableListOf<String>()
        var msmIdHex: String? = null
        var detectedLevelKey = "qcom,level"

        // Extract top-level hardware identity from existing DTS lines
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("model =") && modelName.isEmpty()) {
                modelName = extractQuotedStrings(line).firstOrNull().orEmpty()
            } else if (line.startsWith("compatible =") && compatibleTokens.isEmpty()) {
                compatibleTokens.addAll(extractQuotedStrings(line))
            } else if (line.startsWith("qcom,msm-id =") && msmIdHex == null) {
                msmIdHex = extractRawAngleBracketContent(line)
            } else if (line.startsWith("qcom,cx-ipeak =")) {
                detectedLevelKey = "qcom,cx-ipeak"
            } else if (line.startsWith("qcom,level =")) {
                detectedLevelKey = "qcom,level"
            }
        }

        val bins = parsePowerBins(lines, detectedLevelKey)
        val oppRows = parseOppVoltTable(lines)

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
                    label = KnownRpmhLabels.decodeKnownCodeOrRaw(code),
                    isFromExistingTable = true
                )
            }

        val resolvedModelName = when {
            modelName.isNotBlank() -> modelName
            compatibleTokens.isNotEmpty() -> compatibleTokens.joinToString(" / ")
            else -> "Qualcomm Snapdragon Adreno GPU (Read from DTS)"
        }

        val chipProfile = DynamicChipProfile(
            modelName = resolvedModelName,
            compatibleTokens = compatibleTokens,
            msmIdHex = msmIdHex,
            levelPropertyKey = detectedLevelKey,
            readVoltageLevels = sortedReadLevels,
            hasSeparateVoltTable = oppRows.isNotEmpty(),
            sourcePartition = sourcePartition
        )

        return ParsedDtsWorkspace(
            chipProfile = chipProfile,
            bins = bins,
            oppVoltRows = oppRows,
            rawHeaderBlock = extractHeaderMetadataComment(resolvedModelName, msmIdHex),
            rawFooterBlock = ""
        )
    }

    private fun parsePowerBins(lines: List<String>, defaultLevelKey: String): List<GpuPowerBin> {
        val bins = mutableListOf<GpuPowerBin>()
        var i = 0
        var binCounter = 0

        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (isBinNodeStart(trimmed)) {
                val nodeName = trimmed.substringBefore("{").trim()
                val explicitBinIdx = nodeName.substringAfterLast("qcom,gpu-pwrlevels-", "")
                    .toIntOrNull() ?: binCounter
                var braceDepth = countChar(trimmed, '{') - countChar(trimmed, '}')
                i++

                var speedBinValue: Int? = null
                var initialPwrLevel = 1
                var throttlePwrLevel: Int? = null
                val headerProps = linkedMapOf<String, String>()
                val parsedRows = mutableListOf<GpuFreqRow>()

                while (i < lines.size && braceDepth > 0) {
                    val inner = lines[i].trim()
                    if (isPwrLevelNodeStart(inner)) {
                        val levelNodeName = inner.substringBefore("{").trim()
                        val levelIdx = levelNodeName.substringAfter("@", "")
                            .toIntOrNull() ?: parsedRows.size
                        var levelDepth = countChar(inner, '{') - countChar(inner, '}')
                        i++

                        var freqHz = 0L
                        var voltCode = 128
                        var busFreq = 0
                        var busMin = 0
                        var busMax = 0
                        var acdLevelHex: String? = null
                        val extraProps = linkedMapOf<String, String>()

                        while (i < lines.size && levelDepth > 0) {
                            val propLine = lines[i].trim()
                            levelDepth += countChar(propLine, '{') - countChar(propLine, '}')
                            if (levelDepth > 0 && propLine.isNotEmpty() && !propLine.startsWith("}") && propLine.contains("=")) {
                                val key = propLine.substringBefore("=").trim()
                                val rawVal = propLine.substringAfter("=").substringBefore(";").trim()
                                when (key) {
                                    "qcom,gpu-freq" -> freqHz = parseSingleLongCell(rawVal)
                                    "qcom,level", "qcom,cx-ipeak" -> voltCode = parseSingleLongCell(rawVal).toInt()
                                    "qcom,bus-freq" -> busFreq = parseSingleLongCell(rawVal).toInt()
                                    "qcom,bus-min" -> busMin = parseSingleLongCell(rawVal).toInt()
                                    "qcom,bus-max" -> busMax = parseSingleLongCell(rawVal).toInt()
                                    "qcom,acd-level" -> acdLevelHex = extractRawAngleBracketContent(rawVal)
                                    "reg" -> { /* Rebuild reg from index on serialize */ }
                                    else -> extraProps[key] = rawVal
                                }
                            }
                            i++
                        }

                        if (freqHz > 0L) {
                            parsedRows.add(
                                GpuFreqRow(
                                    id = UUID.randomUUID().toString(),
                                    levelIndex = levelIdx,
                                    frequencyHz = freqHz,
                                    voltageLevelCode = voltCode,
                                    busFreq = busFreq,
                                    busMin = busMin,
                                    busMax = busMax,
                                    acdLevelHex = acdLevelHex,
                                    extraProperties = extraProps
                                )
                            )
                        }
                        continue
                    }

                    braceDepth += countChar(inner, '{') - countChar(inner, '}')
                    if (braceDepth > 0 && inner.contains("=") && !inner.endsWith("{")) {
                        val key = inner.substringBefore("=").trim()
                        val rawVal = inner.substringAfter("=").substringBefore(";").trim()
                        when (key) {
                            "qcom,gpu-speed-bin" -> speedBinValue = parseSingleLongCell(rawVal).toInt()
                            "qcom,initial-pwrlevel" -> initialPwrLevel = parseSingleLongCell(rawVal).toInt()
                            "qcom,throttle-pwrlevel" -> throttlePwrLevel = parseSingleLongCell(rawVal).toInt()
                            else -> if (key != "compatible" && key != "#address-cells" && key != "#size-cells") {
                                headerProps[key] = rawVal
                            }
                        }
                    }
                    i++
                }

                if (parsedRows.isNotEmpty()) {
                    // Maintain descending order from highest frequency to lowest frequency as in Qualcomm DTS
                    val sortedRows = parsedRows
                        .sortedByDescending { it.frequencyHz }
                        .mapIndexed { idx, row -> row.copy(levelIndex = idx) }
                    bins.add(
                        GpuPowerBin(
                            binIndex = explicitBinIdx,
                            nodeName = nodeName,
                            speedBinValue = speedBinValue,
                            initialPwrLevel = initialPwrLevel,
                            throttlePwrLevel = throttlePwrLevel,
                            headerProperties = headerProps,
                            levels = sortedRows,
                            stockLevels = sortedRows.map { it.copy() }
                        )
                    )
                    binCounter++
                }
                continue
            }
            i++
        }

        return bins
    }

    private fun parseOppVoltTable(lines: List<String>): List<GpuOppVoltRow> {
        val oppRows = mutableListOf<GpuOppVoltRow>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("qcom,gpu-opp-table") || trimmed.startsWith("gpu-opp-table") || trimmed.startsWith("opp-table")) {
                if (trimmed.contains("{")) {
                    var braceDepth = countChar(trimmed, '{') - countChar(trimmed, '}')
                    i++
                    while (i < lines.size && braceDepth > 0) {
                        val inner = lines[i].trim()
                        if (inner.startsWith("opp-") && inner.contains("{")) {
                            val nodeName = inner.substringBefore("{").trim()
                            var oppDepth = countChar(inner, '{') - countChar(inner, '}')
                            i++
                            var freqHz = 0L
                            var microvolt = 0L
                            var peakBw: Long? = null
                            while (i < lines.size && oppDepth > 0) {
                                val prop = lines[i].trim()
                                oppDepth += countChar(prop, '{') - countChar(prop, '}')
                                if (oppDepth > 0 && prop.contains("=")) {
                                    val key = prop.substringBefore("=").trim()
                                    val rawVal = prop.substringAfter("=").substringBefore(";").trim()
                                    when (key) {
                                        "opp-hz" -> freqHz = parseMultiCell64BitOrSingle(rawVal)
                                        "opp-microvolt" -> microvolt = parseSingleLongCell(rawVal)
                                        "opp-peak-kBps" -> peakBw = parseSingleLongCell(rawVal)
                                    }
                                }
                                i++
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
                        braceDepth += countChar(inner, '{') - countChar(inner, '}')
                        i++
                    }
                    continue
                }
            }
            i++
        }
        return oppRows.sortedByDescending { it.frequencyHz }
    }

    /**
     * Serializes the workspace back into valid Qualcomm Device Tree Source (.dts) format.
     */
    fun serializeToDts(workspace: ParsedDtsWorkspace): String {
        val sb = StringBuilder()
        val profile = workspace.chipProfile
        sb.appendLine("/dts-v1/;")
        sb.appendLine()
        sb.appendLine("/ {")
        sb.appendLine("    model = \"${profile.modelName}\";")
        if (profile.compatibleTokens.isNotEmpty()) {
            val quoted = profile.compatibleTokens.joinToString(", ") { "\"$it\"" }
            sb.appendLine("    compatible = $quoted;")
        }
        if (!profile.msmIdHex.isNullOrBlank()) {
            sb.appendLine("    qcom,msm-id = <${profile.msmIdHex}>;")
        }
        sb.appendLine()
        sb.appendLine("    qcom,kgsl-3d0@3d00000 {")
        sb.appendLine("        compatible = \"qcom,kgsl-3d0\";")

        if (workspace.oppVoltRows.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("        qcom,gpu-opp-table {")
            sb.appendLine("            compatible = \"operating-points-v2\";")
            workspace.oppVoltRows.sortedByDescending { it.frequencyHz }.forEach { opp ->
                sb.appendLine("            opp-${opp.frequencyHz} {")
                sb.appendLine("                opp-hz = <0x0 0x${opp.frequencyHz.toString(16)}>;")
                sb.appendLine("                opp-microvolt = <0x${opp.microvoltOrLevel.toString(16)}>;")
                if (opp.peakKbps != null) {
                    sb.appendLine("                opp-peak-kBps = <0x${opp.peakKbps.toString(16)}>;")
                }
                sb.appendLine("            };")
            }
            sb.appendLine("        };")
        }

        sb.appendLine()
        sb.appendLine("        qcom,gpu-pwrlevel-bins {")
        workspace.bins.forEach { bin ->
            sb.appendLine("            ${bin.nodeName} {")
            sb.appendLine("                compatible = \"qcom,gpu-pwrlevels\";")
            sb.appendLine("                #address-cells = <0x1>;")
            sb.appendLine("                #size-cells = <0x0>;")
            if (bin.speedBinValue != null) {
                sb.appendLine("                qcom,gpu-speed-bin = <0x${bin.speedBinValue.toString(16)}>;")
            }
            sb.appendLine("                qcom,initial-pwrlevel = <0x${bin.initialPwrLevel.toString(16)}>;")
            if (bin.throttlePwrLevel != null) {
                sb.appendLine("                qcom,throttle-pwrlevel = <0x${bin.throttlePwrLevel.toString(16)}>;")
            }
            bin.headerProperties.forEach { (k, v) ->
                sb.appendLine("                $k = $v;")
            }
            sb.appendLine()

            bin.levels.forEachIndexed { idx, row ->
                sb.appendLine("                qcom,gpu-pwrlevel@$idx {")
                sb.appendLine("                    reg = <0x${idx.toString(16)}>;")
                sb.appendLine("                    qcom,gpu-freq = <0x${row.frequencyHz.toString(16)}>;")
                sb.appendLine("                    ${profile.levelPropertyKey} = <0x${row.voltageLevelCode.toString(16)}>;")
                sb.appendLine("                    qcom,bus-freq = <0x${row.busFreq.toString(16)}>;")
                sb.appendLine("                    qcom,bus-min = <0x${row.busMin.toString(16)}>;")
                sb.appendLine("                    qcom,bus-max = <0x${row.busMax.toString(16)}>;")
                if (!row.acdLevelHex.isNullOrBlank()) {
                    sb.appendLine("                    qcom,acd-level = <${row.acdLevelHex}>;")
                }
                row.extraProperties.forEach { (k, v) ->
                    sb.appendLine("                    $k = $v;")
                }
                sb.appendLine("                };")
            }
            sb.appendLine("            };")
        }
        sb.appendLine("        };")
        sb.appendLine("    };")
        sb.appendLine("};")
        return sb.toString()
    }

    /**
     * Exports a single bin or entire GPU table in KonaBess shareable config format.
     */
    fun exportKonaBessConfig(workspace: ParsedDtsWorkspace, binIndex: Int): String {
        val bin = workspace.bins.firstOrNull { it.binIndex == binIndex } ?: workspace.bins.first()
        val profile = workspace.chipProfile
        val sb = StringBuilder()
        sb.appendLine("# SageKonaBess GPU Frequency & Voltage Configuration")
        sb.appendLine("# Device Read Model: ${profile.modelName}")
        sb.appendLine("# Target Bin: ${bin.nodeName}")
        sb.appendLine("# Level Key: ${profile.levelPropertyKey}")
        sb.appendLine()
        sb.appendLine("${bin.nodeName} {")
        sb.appendLine("    qcom,initial-pwrlevel = <0x${bin.initialPwrLevel.toString(16)}>;")
        bin.levels.forEachIndexed { idx, row ->
            val labelComment = profile.resolveLabel(row.voltageLevelCode)
            sb.appendLine("    qcom,gpu-pwrlevel@$idx { /* ${row.frequencyMHz}MHz -> $labelComment */")
            sb.appendLine("        reg = <0x${idx.toString(16)}>;")
            sb.appendLine("        qcom,gpu-freq = <0x${row.frequencyHz.toString(16)}>;")
            sb.appendLine("        ${profile.levelPropertyKey} = <0x${row.voltageLevelCode.toString(16)}>;")
            sb.appendLine("        qcom,bus-freq = <0x${row.busFreq.toString(16)}>;")
            sb.appendLine("        qcom,bus-min = <0x${row.busMin.toString(16)}>;")
            sb.appendLine("        qcom,bus-max = <0x${row.busMax.toString(16)}>;")
            if (!row.acdLevelHex.isNullOrBlank()) {
                sb.appendLine("        qcom,acd-level = <${row.acdLevelHex}>;")
            }
            sb.appendLine("    };")
        }
        sb.appendLine("};")
        return sb.toString()
    }

    private fun isBinNodeStart(line: String): Boolean {
        return (line.startsWith("qcom,gpu-pwrlevels") && line.contains("{") && !line.startsWith("qcom,gpu-pwrlevel-bins"))
    }

    private fun isPwrLevelNodeStart(line: String): Boolean {
        return line.startsWith("qcom,gpu-pwrlevel@") && line.contains("{")
    }

    private fun countChar(s: String, c: Char): Int = s.count { it == c }

    private fun extractQuotedStrings(line: String): List<String> {
        val regex = Regex("\"([^\"]+)\"")
        return regex.findAll(line).map { it.groupValues[1] }.toList()
    }

    private fun extractRawAngleBracketContent(line: String): String {
        return line.substringAfter("<", "").substringBefore(">", "").trim()
    }

    private fun parseSingleLongCell(rawVal: String): Long {
        val inside = extractRawAngleBracketContent(rawVal).ifEmpty { rawVal.trim() }
        val token = inside.split(Regex("\\s+")).lastOrNull().orEmpty().trim()
        return parseHexOrDec(token)
    }

    private fun parseMultiCell64BitOrSingle(rawVal: String): Long {
        val inside = extractRawAngleBracketContent(rawVal).ifEmpty { rawVal.trim() }
        val tokens = inside.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size >= 2) {
            val high = parseHexOrDec(tokens[0])
            val low = parseHexOrDec(tokens[1])
            return (high shl 32) or (low and 0xFFFFFFFFL)
        }
        return parseHexOrDec(tokens.firstOrNull().orEmpty())
    }

    private fun parseHexOrDec(token: String): Long {
        val clean = token.trim()
        if (clean.isEmpty()) return 0L
        return if (clean.startsWith("0x", ignoreCase = true)) {
            clean.substring(2).toLongOrNull(16) ?: 0L
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
