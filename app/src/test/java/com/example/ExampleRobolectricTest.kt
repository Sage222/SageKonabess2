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

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SageKonaBess", appName)
    }

    @Test
    fun `viewModel drops voltage steps and keeps bottom 2 frequencies unchanged`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = KonaBessViewModel(app)

        val initialBin = vm.uiState.value.activeBin!!
        val profile = vm.uiState.value.workspace.chipProfile

        // Ensure NOM and TURBO_L1 are not assumed in the default read table
        val readLabels = profile.readVoltageLevels.map { it.label }
        assertFalse(readLabels.contains("NOM"))
        assertFalse(readLabels.contains("TURBO_L1"))

        // Verify 719 MHz is initially SVS_L2 and 680 MHz is SVS_L1
        val row719Before = initialBin.levels.first { it.frequencyMHz == 719L }
        val row680Before = initialBin.levels.first { it.frequencyMHz == 680L }
        val bottomSecondBefore = initialBin.levels[initialBin.levels.size - 2]
        val bottomFirstBefore = initialBin.levels[initialBin.levels.size - 1]

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
        // Bottom 2 frequencies remain unchanged
        assertEquals(bottomSecondBefore.voltageLevelCode, bottomSecondAfter.voltageLevelCode)
        assertEquals(bottomFirstBefore.voltageLevelCode, bottomFirstAfter.voltageLevelCode)
    }
}
