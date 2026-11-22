package agent.ai

import kotlin.test.*

class SafeArithmeticTest {
    @Test fun arithmeticHonoursPrecedenceUnaryAndParentheses() {
        assertEquals(14.0,SafeArithmetic.evaluate("2 + 3 * 4"))
        assertEquals(20.0,SafeArithmetic.evaluate("(2 + 3) * 4"))
        assertEquals(-6.0,SafeArithmetic.evaluate("2 * -(1 + 2)"))
        assertEquals(8.0,SafeArithmetic.evaluate("2^3"))
        assertEquals(1.0,SafeArithmetic.evaluate("17 % 4"))
    }
    @Test fun invalidAndUnboundedExpressionsFailClosed() {
        for (expression in listOf("1/0","1+","NaN","1e999","2^99","1; Runtime.exec(1)","x*2"))
            assertFailsWith<IllegalArgumentException>(expression) { SafeArithmetic.evaluate(expression) }
    }
}
