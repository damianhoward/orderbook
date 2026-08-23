package com.damianhoward.orderbook.model

/**
 * Builds an [Order] for a test that is about price, size or priority rather than identity.
 *
 * The owner defaults to one **derived from the id**, so every order in a test belongs to a
 * different party unless a test says otherwise. That is deliberate: a single shared default would
 * make every order in the book a self-match, and the matcher would cancel the liquidity these tests
 * expect it to trade with — every assertion about fills would fail, and worse, one written later
 * could pass for the wrong reason. A test about self-matching passes the same owner to two orders
 * and reads as being about that.
 */
internal fun order(
    id: Long,
    price: Price,
    side: Side,
    size: Long,
    owner: Owner = ownerOf(id),
    timeInForce: TimeInForce = TimeInForce.GoodTilCancelled,
): Order = Order(id, price, side, size, owner, timeInForce)

/**
 * The [Trade] [order] would produce, with each side's owner derived from its order id the same way.
 * Keeps a fill assertion about price, size and priority instead of restating two owners that follow
 * from the ids already in the line.
 */
internal fun trade(
    price: Price,
    size: Long,
    makerOrderId: Long,
    takerOrderId: Long,
    takerSide: Side,
): Trade =
    Trade(
        price = price,
        size = size,
        makerOrderId = makerOrderId,
        takerOrderId = takerOrderId,
        takerSide = takerSide,
        maker = ownerOf(makerOrderId),
        taker = ownerOf(takerOrderId),
    )

/** The party a test order with this id belongs to. */
internal fun ownerOf(orderId: Long): Owner = Owner("trader-$orderId")

/**
 * A participant for session-level tests, which do not choose their own order ids. Distinct from
 * [Owner.HOUSE] on purpose: the house owns the seeded ladder, and an order from the house would be
 * cancelled by self-match prevention rather than trading with it — so a test that means to trade
 * against the seeded book has to be someone else, or it silently stops testing what it claims to.
 */
internal val TRADER = Owner("test-trader")
