package com.nyx.ai

import com.nyx.ai.agent.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class AgentToolsTest {
    @Test
    fun calculatorHonorsPrecedenceAndParentheses() {
        assertEquals(BigDecimal("14"), AgentTools.Calculator.eval("2 + 3 * 4"))
        assertEquals(BigDecimal("-4"), AgentTools.Calculator.eval("-(2 + 2)"))
        assertEquals(BigDecimal("2.5"), AgentTools.Calculator.eval("10 / 4"))
    }

    @Test
    fun calculatorRejectsDivisionByZeroAndTrailingCode() {
        assertThrows(IllegalArgumentException::class.java) { AgentTools.Calculator.eval("1 / 0") }
        assertThrows(IllegalArgumentException::class.java) { AgentTools.Calculator.eval("1 + 2; Runtime.exec()") }
    }
}
