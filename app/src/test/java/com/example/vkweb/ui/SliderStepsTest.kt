package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Значение ползунка с шагом (4.24, StepSeekBarPreference). */
class SliderStepsTest {

    @Test fun `к ближайшему шагу от начала`() {
        assertEquals(80, SliderSteps.snap(84, 80, 150, 10))
        assertEquals(90, SliderSteps.snap(85, 80, 150, 10))
        assertEquals(120, SliderSteps.snap(117, 80, 150, 10))
    }

    @Test fun `шаг отсчитывается от min, а не от нуля`() {
        // 75…100 с шагом 10: 75, 85, 95 — не 80 и 90.
        assertEquals(85, SliderSteps.snap(88, 75, 100, 10))
        assertEquals(95, SliderSteps.snap(97, 75, 100, 10))
    }

    @Test fun `диапазон не делится на шаг — последний шаг не выходит за max`() {
        assertEquals(100, SliderSteps.snap(99, 75, 100, 10))
        assertEquals(100, SliderSteps.snap(500, 75, 100, 10))
    }

    @Test fun `без шага — только пределы`() {
        assertEquals(113, SliderSteps.snap(113, 80, 150, 1))
        assertEquals(113, SliderSteps.snap(113, 80, 150, 0))
        assertEquals(80, SliderSteps.snap(10, 80, 150, 1))
    }
}
