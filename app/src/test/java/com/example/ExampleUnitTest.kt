package com.example

import com.example.konabess.core.DtsParser
import com.example.konabess.core.VoltageStepShifter
import com.example.konabess.model.KonaBessChipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    // Exact Qualcomm kalama-v2-gpu-pwrlevels.dtsi stock DTB (Snapdragon 8 Gen 2 / Kalama v2):
    // 719MHz=224(SVS_L2), 680MHz=192(SVS_L1), 615MHz=144(SVS_L0), 550MHz=128(SVS),
    // 475MHz=80(LOW_SVS_L1), 401MHz=64(LOW_SVS), 348MHz=60(LOW_SVS_D0),
    // 295MHz=56(LOW_SVS_D1), 220MHz=52(LOW_SVS_D2) + trailing 0 Hz idle level.
    private val kalamaV2DeviceDts = """
        /dts-v1/;
        / {
            model = "Qualcomm Technologies, Inc. Kalama v2";
            compatible = "qcom,kalama";
            qcom,kgsl-3d0@3d00000 {
                qcom,gpu-pwrlevel-bins {
                    qcom,gpu-pwrlevels-0 {
                        compatible = "qcom,gpu-pwrlevels";
                        qcom,initial-pwrlevel = <0x7>;
                        qcom,gpu-pwrlevel@0 {
                            reg = <0x0>;
                            qcom,gpu-freq = <0x2adb29c0>; /* 719 MHz */
                            qcom,level = <0xe0>; /* 224 = SVS_L2 */
                            qcom,bus-freq = <0x8>;
                            qcom,bus-min = <0x7>;
                            qcom,bus-max = <0xa>;
                        };
                        qcom,gpu-pwrlevel@1 {
                            reg = <0x1>;
                            qcom,gpu-freq = <0x28881e00>; /* 680 MHz */
                            qcom,level = <0xc0>; /* 192 = SVS_L1 */
                            qcom,bus-freq = <0x7>;
                            qcom,bus-min = <0x6>;
                            qcom,bus-max = <0x9>;
                        };
                        qcom,gpu-pwrlevel@2 {
                            reg = <0x2>;
                            qcom,gpu-freq = <0x24a827c0>; /* 615 MHz */
                            qcom,level = <0x90>; /* 144 = SVS_L0 */
                            qcom,bus-freq = <0x6>;
                            qcom,bus-min = <0x5>;
                            qcom,bus-max = <0x8>;
                        };
                        qcom,gpu-pwrlevel@3 {
                            reg = <0x3>;
                            qcom,gpu-freq = <0x20c85580>; /* 550 MHz */
                            qcom,level = <0x80>; /* 128 = SVS */
                            qcom,bus-freq = <0x5>;
                            qcom,bus-min = <0x4>;
                            qcom,bus-max = <0x7>;
                        };
                        qcom,gpu-pwrlevel@4 {
                            reg = <0x4>;
                            qcom,gpu-freq = <0x1c4fecc0>; /* 475 MHz */
                            qcom,level = <0x50>; /* 80 = LOW_SVS_L1 */
                            qcom,bus-freq = <0x4>;
                            qcom,bus-min = <0x3>;
                            qcom,bus-max = <0x6>;
                        };
                        qcom,gpu-pwrlevel@5 {
                            reg = <0x5>;
                            qcom,gpu-freq = <0x17e6c640>; /* 401 MHz */
                            qcom,level = <0x40>; /* 64 = LOW_SVS */
                            qcom,bus-freq = <0x3>;
                            qcom,bus-min = <0x2>;
                            qcom,bus-max = <0x5>;
                        };
                        qcom,gpu-pwrlevel@6 {
                            reg = <0x6>;
                            qcom,gpu-freq = <0x14be0f00>; /* 348 MHz */
                            qcom,level = <0x3c>; /* 60 = LOW_SVS_D0 */
                            qcom,bus-freq = <0x3>;
                            qcom,bus-min = <0x2>;
                            qcom,bus-max = <0x5>;
                        };
                        qcom,gpu-pwrlevel@7 {
                            reg = <0x7>;
                            qcom,gpu-freq = <0x119557c0>; /* 295 MHz */
                            qcom,level = <0x38>; /* 56 = LOW_SVS_D1 */
                            qcom,bus-freq = <0x2>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x3>;
                        };
                        qcom,gpu-pwrlevel@8 {
                            reg = <0x8>;
                            qcom,gpu-freq = <0xd1cef00>; /* 220 MHz */
                            qcom,level = <0x34>; /* 52 = LOW_SVS_D2 */
                            qcom,bus-freq = <0x1>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x2>;
                        };
                        qcom,gpu-pwrlevel@9 {
                            reg = <0x9>;
                            qcom,gpu-freq = <0x0>; /* Trailing 0 Hz idle level in Qualcomm DTB */
                            qcom,level = <0x0>;
                            qcom,bus-freq = <0x0>;
                            qcom,bus-min = <0x0>;
                            qcom,bus-max = <0x0>;
                        };
                    };
                };
            };
        };
    """.trimIndent()

    @Test
    fun kalamaV2_decodesExactRpmhLabels_52IsLowSvsD2_56IsLowSvsD1_60IsLowSvsD0() {
        val workspace = DtsParser.parseDts(kalamaV2DeviceDts)
        val labels = workspace.chipProfile.readVoltageLevels.map { it.label }
        val bin = workspace.bins.first()

        // Verify exact stock voltage levels read from Kalama v2 DTB
        assertEquals(
            listOf(
                "LOW_SVS_D2",
                "LOW_SVS_D1",
                "LOW_SVS_D0",
                "LOW_SVS",
                "LOW_SVS_L1",
                "SVS",
                "SVS_L0",
                "SVS_L1",
                "SVS_L2"
            ),
            labels
        )
        assertFalse("Should not assume MIN_SVS when absent from device DTS", labels.contains("MIN_SVS"))
        assertFalse("Should not assume NOM when absent from device DTS", labels.contains("NOM"))
        assertFalse("Should not assume TURBO_L1 when absent from device DTS", labels.contains("TURBO_L1"))

        // Verify 348MHz is LOW_SVS_D0 (60), 295MHz is LOW_SVS_D1 (56), and 220MHz is LOW_SVS_D2 (52)
        val row348 = bin.levels.first { it.frequencyMHz == 348L }
        val row295 = bin.levels.first { it.frequencyMHz == 295L }
        val row220 = bin.levels.first { it.frequencyMHz == 220L }
        assertEquals("LOW_SVS_D0", workspace.chipProfile.resolveLabel(row348.voltageLevelCode))
        assertEquals("LOW_SVS_D1", workspace.chipProfile.resolveLabel(row295.voltageLevelCode))
        assertEquals("LOW_SVS_D2", workspace.chipProfile.resolveLabel(row220.voltageLevelCode))

        // Also verify LOW_SVS_D0 is present in chipFamilyManualLevels list
        val manualLabels = workspace.chipProfile.chipFamilyManualLevels.map { it.label }
        assertTrue(manualLabels.contains("LOW_SVS_D0"))
        assertTrue(manualLabels.contains("LOW_SVS_D1"))
        assertTrue(manualLabels.contains("LOW_SVS_D2"))

        // Trailing 0 Hz level is hidden from visible levels and preserved in trailingZeroFreqLevelLines
        assertEquals(
            listOf(719L, 680L, 615L, 550L, 475L, 401L, 348L, 295L, 220L),
            bin.levels.map { it.frequencyMHz }
        )
        assertNotNull(bin.trailingZeroFreqLevelLines)

        // Serializing back splices into the original DTS and preserves the trailing 0 Hz level
        val serialized = DtsParser.serializeToDts(workspace)
        assertTrue(serialized.contains("qcom,gpu-freq = <0x0>;"))
    }

    @Test
    fun detectChipTypeStrict_matchesOnlyKalamaV2_outOfFourKalamaDtbs() {
        val dtb0KalamaV1 = listOf(
            "model = \"Qualcomm Technologies, Inc. Kalama\";",
            "compatible = \"qcom,kalama\";",
            "qcom,gpu-pwrlevels-0 {"
        )
        val dtb1KalamaV2 = listOf(
            "model = \"Qualcomm Technologies, Inc. Kalama v2\";",
            "compatible = \"qcom,kalama\";",
            "qcom,gpu-pwrlevels-0 {"
        )
        val dtb2KalamaIot = listOf(
            "model = \"Qualcomm Technologies, Inc. Kalama IOT\";",
            "compatible = \"qcom,kalama\";",
            "qcom,gpu-pwrlevels-0 {"
        )
        val dtb3KalamaHhg = listOf(
            "model = \"Qualcomm Technologies, Inc. Kalama HHG\";",
            "compatible = \"qcom,kalama\";",
            "qcom,gpu-pwrlevels-0 {"
        )

        assertEquals(KonaBessChipType.unknown, DtsParser.detectChipTypeStrict(dtb0KalamaV1).first)
        assertEquals(KonaBessChipType.kalama, DtsParser.detectChipTypeStrict(dtb1KalamaV2).first)
        assertEquals(KonaBessChipType.unknown, DtsParser.detectChipTypeStrict(dtb2KalamaIot).first)
        assertEquals(KonaBessChipType.unknown, DtsParser.detectChipTypeStrict(dtb3KalamaHhg).first)
    }

    @Test
    fun dropVoltageStepsOneLevel_shifts719MhzFromSvsL2ToSvsL1_andKeepsBottomTwo295And220Unchanged() {
        val workspace = DtsParser.parseDts(kalamaV2DeviceDts)
        val originalRows = workspace.bins.first().levels

        // Execute One-Tap Voltage Step Drop (-1 Step)
        val result = VoltageStepShifter.dropVoltageStepsOneLevel(originalRows)
        val dropped = result.updatedRows

        // 719MHz (index 0) drops from SVS_L2 (224) -> 680MHz's SVS_L1 (192)
        assertEquals("SVS_L1", workspace.chipProfile.resolveLabel(dropped[0].voltageLevelCode))
        // 680MHz (index 1) drops from SVS_L1 (192) -> 615MHz's SVS_L0 (144)
        assertEquals("SVS_L0", workspace.chipProfile.resolveLabel(dropped[1].voltageLevelCode))
        // 348MHz (index 6) drops from LOW_SVS_D0 (60) -> 295MHz's LOW_SVS_D1 (56)
        assertEquals("LOW_SVS_D1", workspace.chipProfile.resolveLabel(dropped[6].voltageLevelCode))

        // Bottom 2 frequencies (295MHz @ LOW_SVS_D1 and 220MHz @ LOW_SVS_D2) MUST remain strictly unchanged
        assertEquals(295L, dropped[7].frequencyMHz)
        assertEquals("LOW_SVS_D1", workspace.chipProfile.resolveLabel(dropped[7].voltageLevelCode))
        assertEquals(220L, dropped[8].frequencyMHz)
        assertEquals("LOW_SVS_D2", workspace.chipProfile.resolveLabel(dropped[8].voltageLevelCode))
    }
}

