package agent.ai

import kotlin.math.pow

/** Small bounded arithmetic parser for the Agent calculator; never evaluates JVM/JavaScript code. */
object SafeArithmetic {
    private const val MAX_LENGTH = 256
    private const val MAX_NODES = 128
    private const val MAX_ABS = 1.0e12

    fun evaluate(expression: String): Double {
        require(expression.length in 1..MAX_LENGTH) { "Expression length must be 1..$MAX_LENGTH characters" }
        val parser = Parser(expression)
        val value = parser.expression()
        parser.space()
        require(parser.atEnd()) { "Unexpected token at position ${parser.position()}" }
        return parser.checked(value)
    }

    private class Parser(private val input: String) {
        private var index = 0
        private var nodes = 0
        fun atEnd() = index >= input.length
        fun position() = index
        fun space() { while (!atEnd() && input[index].isWhitespace()) index++ }
        private fun node() { require(++nodes <= MAX_NODES) { "Expression is too complex" } }
        fun checked(value: Double): Double {
            require(value.isFinite() && kotlin.math.abs(value) <= MAX_ABS) { "Result is outside the supported range" }
            return if (value == 0.0) 0.0 else value
        }
        private fun take(c: Char): Boolean { space(); if (!atEnd() && input[index] == c) { index++; return true }; return false }

        fun expression(): Double {
            var value = term()
            while (true) {
                val op = when { take('+') -> '+'; take('-') -> '-'; else -> return checked(value) }
                node()
                val right = term()
                value = checked(if (op == '+') value + right else value - right)
            }
        }
        private fun term(): Double {
            var value = unary()
            while (true) {
                val op = when { take('*') -> '*'; take('/') -> '/'; take('%') -> '%'; else -> return checked(value) }
                node()
                val right = unary()
                if (op == '/' || op == '%') require(right != 0.0) { "Division by zero" }
                value = checked(when (op) { '*' -> value * right; '/' -> value / right; else -> value % right })
            }
        }
        private fun unary(): Double {
            if (take('+')) { node(); return unary() }
            if (take('-')) { node(); return checked(-unary()) }
            return power()
        }
        private fun power(): Double {
            var value = primary()
            if (take('^')) {
                node()
                val exponent = unary()
                require(exponent == kotlin.math.floor(exponent) && kotlin.math.abs(exponent) <= 12.0) {
                    "Exponent must be an integer between -12 and 12"
                }
                value = checked(value.pow(exponent.toInt()))
            }
            return value
        }
        private fun primary(): Double {
            space()
            if (take('(')) {
                node()
                val value = expression()
                require(take(')')) { "Missing closing parenthesis" }
                return value
            }
            space()
            val start = index
            while (!atEnd() && (input[index].isDigit() || input[index] == '.')) index++
            if (!atEnd() && (input[index] == 'e' || input[index] == 'E')) {
                index++
                if (!atEnd() && (input[index] == '+' || input[index] == '-')) index++
                val expStart = index
                while (!atEnd() && input[index].isDigit()) index++
                require(index > expStart) { "Malformed exponent" }
            }
            require(index > start) { "Expected a number at position $start" }
            node()
            val raw = input.substring(start, index)
            require(raw.count { it == '.' } <= 1) { "Malformed number" }
            return raw.toDoubleOrNull()?.let(::checked) ?: throw IllegalArgumentException("Invalid number")
        }
    }

    private fun Double.pow(exponent: Int): Double = this.pow(exponent.toDouble())
}
