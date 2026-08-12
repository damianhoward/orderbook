package com.damianhoward.orderbook.model

/**
 * A resting order. Identity is [id] — the book keys and matches by it, so two orders with the same
 * id are the same order regardless of remaining size. [size] is the *remaining* quantity and mutates
 * in place as the order partially fills, so the book never reallocates an `Order` on a size change;
 * accessors hand out detached [snapshot]s, never the live entity.
 */
class Order(
    val id: Long,
    val price: Price,
    val side: Side,
    size: Long,
) {
    var size: Long = size
        set(value) {
            require(value > 0) { "size must be positive, got $value" }
            field = value
        }

    init {
        require(size > 0) { "size must be positive, got $size" }
    }

    /** A detached copy at the current size — handed to callers so the live book entity never escapes. */
    fun snapshot(): Order = Order(id, price, side, size)

    override fun equals(other: Any?): Boolean = this === other || (other is Order && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Order(id=$id, price=$price, side=$side, size=$size)"
}
