package com.example

import com.example.konabess.core.DtsParser
import com.example.konabess.core.VoltageStepShifter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private val sampleDeviceDts = """
        /dts-v1/;
        / {
            model = "Qualcomm Technologies, Inc. Kona SoC";
            compatible = "qcom,kona";
            qcom,kgsl-3d0@3d00000 {
                qcom,gpu-pwrlevel-bins {
                    qcom,gpu-pwrlevels-0 {
                        compatible = "qcom,gpu-pwrlevels";
                        qcom,initial-pwrlevel = <0x4>;
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
                            qcom,gpu-freq = <0x122dec40>; /* 305 MHz */
                            qcom,level = <0x40>; /* 64 = LOW_SVS */
                            qcom,bus-freq = <0x2>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x3>;
                        };
                        qcom,gpu-pwrlevel@4 {
                            reg = <0x4>;
                            qcom,gpu-freq = <0xaba9500>; /* 180 MHz */
                            qcom,level = <0x30>; /* 48 = MIN_SVS */
                            qcom,bus-freq = <0x1>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x2>;
                        };
                    };
                };
            };
        };
    """.trimIndent()

    @Test
    fun dynamicChipProfile_readsStrictlyFromExistingValues_neverAssumesNomOrTurboL1() {
        val workspace = DtsParser.parseDts(sampleDeviceDts)
        val labels = workspace.chipProfile.readVoltageLevels.map { it.label }

        // Only the 5 levels present in the unpacked DTS should exist in readVoltageLevels
        assertEquals(listOf("MIN_SVS", "LOW_SVS", "SVS", "SVS_L1", "SVS_L2"), labels)
        assertFalse("Should not assume NOM when absent from device DTS", labels.contains("NOM"))
        assertFalse("Should not assume TURBO_L1 when absent from device DTS", labels.contains("TURBO_L1"))
    }

    @Test
    fun dropVoltageStepsOneLevel_shifts719MhzFromSvsL2ToSvsL1_andKeepsBottomTwoUnchanged() {
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
        // 550MHz should now have 305MHz's voltage level (LOW_SVS)
        assertEquals("LOW_SVS", workspace.chipProfile.resolveLabel(dropped[2].voltageLevelCode))

        // Bottom 2 frequencies (305MHz and 180MHz) MUST remain strictly unchanged
        assertEquals("LOW_SVS", workspace.chipProfile.resolveLabel(dropped[3].voltageLevelCode))
        assertEquals("MIN_SVS", workspace.chipProfile.resolveLabel(dropped[4].voltageLevelCode))

        // Even if pressed a second time, bottom 2 frequencies remain unchanged
        val secondDrop = VoltageStepShifter.dropVoltageStepsOneLevel(dropped).updatedRows
        assertEquals("SVS", workspace.chipProfile.resolveLabel(secondDrop[0].voltageLevelCode))
        assertEquals("LOW_SVS", workspace.chipProfile.resolveLabel(secondDrop[1].voltageLevelCode))
        assertEquals("LOW_SVS", workspace.chipProfile.resolveLabel(secondDrop[2].voltageLevelCode))
        assertEquals("LOW_SVS", workspace.chipProfile.resolveLabel(secondDrop[3].voltageLevelCode))
        assertEquals("MIN_SVS", workspace.chipProfile.resolveLabel(secondDrop[4].voltageLevelCode))
        assertTrue(secondDrop.size == 5)
    }
}
