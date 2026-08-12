package com.damianhoward.orderbook.model

/**
 * The two sides: [BID] (buy; best = highest price) and [OFFER] (sell; best = lowest). The [code]
 * char is retained for external serialization.
 */
enum class Side(
    val code: Char,
) {
    BID('B'),
    OFFER('O'),
    ;

    companion object {
        fun fromCode(code: Char): Side =
            when (code) {
                'B' -> BID
                'O' -> OFFER
                else -> throw IllegalArgumentException(
                    "Unknown side code '$code'. Expected 'B' (bid) or 'O' (offer).",
                )
            }
    }
}
