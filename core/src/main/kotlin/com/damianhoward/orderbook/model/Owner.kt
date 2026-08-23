package com.damianhoward.orderbook.model

/**
 * Who an order belongs to: the account to credit a fill to, and the party to tell about it.
 *
 * The book itself stays anonymous — matching never reads an owner, and price–time priority is
 * decided without it. It is carried so that the layers above can answer two questions the book
 * cannot: whose position a fill moves, and who to notify. A quote-driven layer needs both, because
 * a solicited quote rests publicly and the party who asked for it is not the only one who can hit
 * it.
 *
 * **Every order has one, and there is no value meaning "nobody".** Ownership and anonymity are
 * different properties, and conflating them is the mistake this type exists to prevent. A central
 * limit order book is *anonymous* in the sense that counterparties do not see each other — a
 * disclosure property, carried by what the tape and depth publish. It is not unowned: a real venue
 * always knows which member an order belongs to, because that is who the fill books to. A
 * quote-driven market differs by being *disclosed*, not by having owners where the book had none.
 *
 * So there is no default and no sentinel. An order carries an owner because a caller named one.
 *
 * There is also no constant for the venue, because a venue is not a counterparty. An exchange
 * matches orders; it does not take the other side of them. The book's opening liquidity belongs to
 * the market makers quoting it — see [com.damianhoward.orderbook.market.SeedLiquidity].
 */
@JvmInline
value class Owner(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "owner id must not be blank" }
    }

    override fun toString(): String = id
}
