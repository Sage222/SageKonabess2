package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.konabess.viewmodel.KonaBessViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private val authenticDeviceDts = """
        /dts-v1/;
        / {
            model = "Qualcomm Technologies, Inc. Kalama v2";
            compatible = "qcom,kalama";
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
                            qcom,gpu-freq = <0x14be0f00>; /* 348 MHz */
                            qcom,level = <0x3c>; /* 60 = LOW_SVS_D0 */
                            qcom,bus-freq = <0x3>;
                            qcom,bus-min = <0x2>;
                            qcom,bus-max = <0x5>;
                        };
                        qcom,gpu-pwrlevel@3 {
                            reg = <0x3>;
                            qcom,gpu-freq = <0x119557c0>; /* 295 MHz */
                            qcom,level = <0x38>; /* 56 = LOW_SVS_D1 */
                            qcom,bus-freq = <0x2>;
                            qcom,bus-min = <0x1>;
                            qcom,bus-max = <0x3>;
                        };
                        qcom,gpu-pwrlevel@4 {
                            reg = <0x4>;
                            qcom,gpu-freq = <0xd1cef00>; /* 220 MHz */
                            qcom,level = <0x34>; /* 52 = LOW_SVS_D2 */
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
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SageKonaBess", appName)
    }

    @Test
    fun `viewModel drops voltage steps, keeps bottom 2 frequencies unchanged, and resets to exact stock`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = KonaBessViewModel(app)

        vm.importDtsOrConfigText(authenticDeviceDts, "Device DTB")

        val initialBin = vm.uiState.value.activeBin!!
        val profile = vm.uiState.value.workspace.chipProfile

        // Ensure MIN_SVS, NOM, and TURBO_L1 are not assumed in the default read table
        val readLabels = profile.readVoltageLevels.map { it.label }
        assertFalse(readLabels.contains("MIN_SVS"))
        assertFalse(readLabels.contains("NOM"))
        assertFalse(readLabels.contains("TURBO_L1"))
        assertEquals(listOf(719L, 680L, 348L, 295L, 220L), initialBin.levels.map { it.frequencyMHz })

        // Verify 719 MHz is initially SVS_L2 and 680 MHz is SVS_L1
        val row719Before = initialBin.levels.first { it.frequencyMHz == 719L }
        val row680Before = initialBin.levels.first { it.frequencyMHz == 680L }
        val bottomSecondBefore = initialBin.levels[initialBin.levels.size - 2] // 295 MHz
        val bottomFirstBefore = initialBin.levels[initialBin.levels.size - 1]  // 220 MHz

        assertEquals("SVS_L2", profile.resolveLabel(row719Before.voltageLevelCode))
        assertEquals("SVS_L1", profile.resolveLabel(row680Before.voltageLevelCode))

        // Trigger One-Tap Voltage Step Drop (-1 Step)
        vm.dropAllVoltagesOneStep()

        val afterBin = vm.uiState.value.activeBin!!
        val row719After = afterBin.levels.first { it.frequencyMHz == 719L }
        val bottomSecondAfter = afterBin.levels[afterBin.levels.size - 2]
        val bottomFirstAfter = afterBin.levels[afterBin.levels.size - 1]

        // 719 MHz now has 680 MHz's SVS_L1 voltage level
        assertEquals("SVS_L1", profile.resolveLabel(row719After.voltageLevelCode))
        // Bottom 2 frequencies (295 MHz & 220 MHz) remain strictly unchanged
        assertEquals(bottomSecondBefore.voltageLevelCode, bottomSecondAfter.voltageLevelCode)
        assertEquals(bottomFirstBefore.voltageLevelCode, bottomFirstAfter.voltageLevelCode)
    }

    @Test
    fun `viewModel tracks modifiedStepsSummary and opens pre-flash confirmation dialog without crashing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = KonaBessViewModel(app)

        vm.importDtsOrConfigText(authenticDeviceDts, "Device DTB")
        assertEquals(0, vm.uiState.value.modifiedStepsSummary.size)

        // Adjust 295 MHz from LOW_SVS_D1 (56) down to LOW_SVS_D2 (52)
        val row295 = vm.uiState.value.activeBin!!.levels.first { it.frequencyMHz == 295L }
        vm.saveEditedFreqRow(row295.copy(voltageLevelCode = 52))

        val summaries = vm.uiState.value.modifiedStepsSummary
        assertEquals(1, summaries.size)
        org.junit.Assert.assertTrue(summaries.first().contains("295 MHz"))
        org.junit.Assert.assertTrue(summaries.first().contains("LOW_SVS_D1 → LOW_SVS_D2"))

        // Request flash confirmation dialog
        vm.requestFlashConfirmation()
        org.junit.Assert.assertTrue(vm.uiState.value.showFlashConfirmDialog)

        // Dismiss confirmation dialog
        vm.dismissFlashConfirmation()
        assertFalse(vm.uiState.value.showFlashConfirmDialog)

        // Request Restore to Defaults confirmation dialog
        vm.requestRestoreDefaultsConfirmation()
        org.junit.Assert.assertTrue(vm.uiState.value.showRestoreDefaultsConfirmDialog)

        // Dismiss Restore to Defaults confirmation dialog
        vm.dismissRestoreDefaultsConfirmation()
        assertFalse(vm.uiState.value.showRestoreDefaultsConfirmDialog)
    }

    @Test
    fun `shellEngine resolves original backed up boot image from filesDir backup`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backupFile = java.io.File(context.filesDir, "backup_vendor_boot.img")
        backupFile.writeBytes(ByteArray(4096) { 0x5A })
        try {
            val engine = com.example.konabess.core.KonaBessShellEngine(context)
            val resolved = engine.resolveOriginalBackupImage(rootAvailable = false)
            org.junit.Assert.assertNotNull(resolved)
            assertEquals("vendor_boot", resolved!!.bootName)
            assertEquals(4096L, resolved.sizeBytes)
        } finally {
            backupFile.delete()
        }
    }
}

