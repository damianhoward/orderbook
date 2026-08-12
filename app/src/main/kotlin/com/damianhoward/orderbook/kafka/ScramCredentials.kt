package com.damianhoward.orderbook.kafka

/**
 * SCRAM-SHA-256 credentials for the egress producer. Null (absent) means the broker listener
 * is unauthenticated plaintext.
 */
data class ScramCredentials(
    val username: String,
    val password: String,
) {
    init {
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }
    }

    /** Never leak the password through logs or error messages. */
    override fun toString(): String = "ScramCredentials(username=$username)"

    companion object {
        /**
         * Both variables set → credentials; neither → null (plaintext). Exactly one set is a
         * misconfiguration and fails fast — a producer silently falling back to plaintext
         * against an authenticated listener would just count failures forever.
         */
        fun fromEnv(env: Map<String, String>): ScramCredentials? {
            val username = env["KAFKA_SASL_USERNAME"]?.takeUnless { it.isBlank() }
            val password = env["KAFKA_SASL_PASSWORD"]?.takeUnless { it.isBlank() }
            return when {
                username != null && password != null -> ScramCredentials(username, password)
                username == null && password == null -> null
                else -> throw IllegalArgumentException("KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD must be set together")
            }
        }
    }
}
