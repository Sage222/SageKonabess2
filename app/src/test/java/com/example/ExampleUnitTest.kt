package com.example

import com.example.konabess.core.DtsParser
import com.example.konabess.core.VoltageStepShifter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    // Authentic stock DTB with 719, 680, 550, 348, 295, 220 MHz + trailing 0 Hz idle level,
    // and NO MIN_SVS, NOM, or TURBO_L1.
    private val sampleDeviceDts = """
        /dts-v1/;
        / {
            model = "Qualcomm Technologies, Inc.Kalama SoC";
            compatible = "qcom,kalama";
            qcom,kgsl-3d0@3d00000 {
                qcom,gpu-pwrlevel-bins {
                    qcom,gpu-pwrlevels-0 {
                        compatible = "qcom,gpu-pwrlevels";
                        qcom,initial-pwrlevel = <0x5>;
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
                            qcom,gpu-freq = <0x20c85580>; /* 550 MHz */
                            qcom,level = <0x80>; /* 128 = SVS */
                            qcom,bus-freq = <0x5>;
                            qcom,bus-min = <0x4>;
                            qcom,bus-max = <0x7>;
                        };
                        qcom,gpu-pwrlevel@3 {
                            reg = <0x3>;
                            qcom,gpu-freq = <0x14be0f00>; /* 348 MHz */
                            qcom,level = <0x60>; /* 96 = LOW_SVS_L2 */
                            qcom,bus-freq = <0x3>;
                            qcom,bus-min = <0x2>;
                            qcom,bus-max = <0x5>;
                        };
                        qcom,gpu-pwrlevel@4 {
                            reg = <0x4>;
                            qcom,gpu-freq = <0x119557c0>; /* 295 MHz */
                            qcom,level = <0x50>; /* 80 = LOW_SVS_L1 */
                            qcom,bus-freq = <0x2>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x3>;
                        };
                        qcom,gpu-pwrlevel@5 {
                            reg = <0x5>;
                            qcom,gpu-freq = <0xd1cef00>; /* 220 MHz */
                            qcom,level = <0x40>; /* 64 = LOW_SVS */
                            qcom,bus-freq = <0x1>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x2>;
                        };
                        qcom,gpu-pwrlevel@6 {
                            reg = <0x6>;
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
    fun dynamicChipProfile_readsStrictlyFromExistingValues_neverAssumesMinSvsOrNomOrTurboL1() {
        val workspace = DtsParser.parseDts(sampleDeviceDts)
        val labels = workspace.chipProfile.readVoltageLevels.map { it.label }
        val bin = workspace.bins.first()

        // Only the 6 levels present in the unpacked DTS should exist in readVoltageLevels
        assertEquals(listOf("LOW_SVS", "LOW_SVS_L1", "LOW_SVS_L2", "SVS", "SVS_L1", "SVS_L2"), labels)
        assertFalse("Should not assume MIN_SVS when absent from device DTS", labels.contains("MIN_SVS"))
        assertFalse("Should not assume NOM when absent from device DTS", labels.contains("NOM"))
        assertFalse("Should not assume TURBO_L1 when absent from device DTS", labels.contains("TURBO_L1"))

        // Trailing 0 Hz level is hidden from visible levels and preserved in trailingZeroFreqLevelLines
        assertEquals(listOf(719L, 680L, 550L, 348L, 295L, 220L), bin.levels.map { it.frequencyMHz })
        assertNotNull(bin.trailingZeroFreqLevelLines)

        // Serializing back splices into the original DTS and preserves the trailing 0 Hz level
        val serialized = DtsParser.serializeToDts(workspace)
        assertTrue(serialized.contains("qcom,gpu-freq = <0x0>;"))
    }

    @Test
    fun dropVoltageStepsOneLevel_shifts719MhzFromSvsL2ToSvsL1_andKeepsBottomTwo295And220Unchanged() {
        val workspace = DtsParser.parseDts(sampleDeviceDts)
        val originalRows = workspace.bins.first().levels

        // Verify initial 719MHz is SVS_L2 (224) and 680MHz is SVS_L1 (192)
        assertEquals(719L, originalRows[0].frequencyMHz)
        assertEquals("SVS_L2", workspace.chipProfile.resolveLabel(originalRows[0].voltageLevelCode))
        assertEquals(680L, originalRows[1].frequencyMHz)
        assertEquals("SVS_L1", workspace.chipProfile.resolveLabel(originalRows[1].voltageLevelCode))

        // Execute One-Tap Voltage Step Drop (-1 Step)
        val result = VoltageStepShifter.dropVoltageStepsOneLevel(originalRows)
        val dropped = result.updatedRows

        // 719MHz should now have 680MHz's voltage level (SVS_L1)
        assertEquals("SVS_L1", workspace.chipProfile.resolveLabel(dropped[0].voltageLevelCode))
        // 680MHz should now have 550MHz's voltage level (SVS)
        assertEquals("SVS", workspace.chipProfile.resolveLabel(dropped[1].voltageLevelCode))
        // 550MHz should now have 348MHz's voltage level (LOW_SVS_L2)
        assertEquals("LOW_SVS_L2", workspace.chipProfile.resolveLabel(dropped[2].voltageLevelCode))
        // 348MHz should now have 295MHz's voltage level (LOW_SVS_L1)
        assertEquals("LOW_SVS_L1", workspace.chipProfile.resolveLabel(dropped[3].voltageLevelCode))

        // Bottom 2 frequencies (295MHz and 220MHz) MUST remain strictly unchanged
        assertEquals(295L, dropped[4].frequencyMHz)
        assertEquals("LOW_SVS_L1", workspace.chipProfile.resolveLabel(dropped[4].voltageLevelCode))
        assertEquals(220L, dropped[5].frequencyMHz)
        assertEquals("LOW_SVS", workspace.chipProfile.resolveLabel(dropped[5].voltageLevelCode))
    }
}
