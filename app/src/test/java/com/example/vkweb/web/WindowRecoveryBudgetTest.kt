package com.example.vkweb.web

import org.junit.Assert.*
import org.junit.Test

class WindowRecoveryBudgetTest {
    @Test fun repeatedRendererFailuresHaveBoundedDelayedRestarts() {
        val budget = WindowRecoveryBudget()
        assertEquals(1000L, budget.nextDelay(0)); assertEquals(3000L, budget.nextDelay(1000)); assertNull(budget.nextDelay(2000))
    }
    @Test fun laterIndependentFailureCanRecoverAgain() {
        val budget = WindowRecoveryBudget(); budget.nextDelay(0); budget.nextDelay(1000)
        assertEquals(1000L, budget.nextDelay(61000))
    }
}
