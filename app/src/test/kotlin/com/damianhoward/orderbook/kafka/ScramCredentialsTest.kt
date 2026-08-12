package com.damianhoward.orderbook.kafka

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScramCredentialsTest {
    @Test
    fun `both variables set yields credentials`() {
        val credentials =
            ScramCredentials.fromEnv(
                mapOf("KAFKA_SASL_USERNAME" to "orderbook-egress", "KAFKA_SASL_PASSWORD" to "s3cret"),
            )
        assertEquals(ScramCredentials("orderbook-egress", "s3cret"), credentials)
    }

    @Test
    fun `neither variable set yields null - the plaintext listener`() {
        assertNull(ScramCredentials.fromEnv(emptyMap()))
        assertNull(ScramCredentials.fromEnv(mapOf("KAFKA_SASL_USERNAME" to "", "KAFKA_SASL_PASSWORD" to " ")))
    }

    @Test
    fun `exactly one variable set fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScramCredentials.fromEnv(mapOf("KAFKA_SASL_USERNAME" to "orderbook-egress"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScramCredentials.fromEnv(mapOf("KAFKA_SASL_PASSWORD" to "s3cret"))
        }
    }

    @Test
    fun `blank fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ScramCredentials(" ", "s3cret") }
        assertThrows(IllegalArgumentException::class.java) { ScramCredentials("user", "") }
    }

    @Test
    fun `toString never carries the password`() {
        assertFalse(ScramCredentials("user", "s3cret").toString().contains("s3cret"))
    }
}
