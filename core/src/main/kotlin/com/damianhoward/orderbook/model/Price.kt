package com.damianhoward.orderbook.model

import java.math.BigDecimal

/**
 * A price as an integer number of ticks at [SCALE] decimal places. Scaled `Long`s keep prices exact
 * (no float drift, so equal prices share a map key) and make comparison a primitive op; `BigDecimal`
 * is touched only when parsing or rendering, never on the hot path.
 */
@JvmInline
value class Price(
    val ticks: Long,
) : Comparable<Price> {
    init {
        require(ticks >= 0) { "price must be non-negative, got $ticks ticks" }
    }

    override fun compareTo(other: Price): Int = ticks.compareTo(other.ticks)

    override fun toString(): String = BigDecimal.valueOf(ticks, SCALE).toPlainString()

    companion object {
        const val SCALE: Int = 8

        /**
         * Parses a decimal string such as `"3.45"` into exact ticks.
         *
         * @throws ArithmeticException if [text] exceeds [SCALE] decimals (lossy) or overflows a `Long`.
         * @throws NumberFormatException if [text] is not a valid decimal.
         */
        fun of(text: String): Price = Price(BigDecimal(text).movePointRight(SCALE).longValueExact())
    }
}
