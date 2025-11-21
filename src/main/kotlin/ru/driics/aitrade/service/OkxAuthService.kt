package ru.driics.aitrade.service

import org.springframework.stereotype.Service
import ru.driics.aitrade.config.OkxProperties
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class OkxAuthService(
    private val okxProperties: OkxProperties
) {
    private companion object {
        const val HMAC_ALGO = "HmacSHA256"

        // DateTimeFormatter is thread-safe and immutable
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
    }

    // Optimization: Create the KeySpec once, as it is immutable and thread-safe.
    // This avoids repeated byte array conversions and object allocations.
    private val secretKeySpec by lazy {
        SecretKeySpec(okxProperties.secretKey.toByteArray(Charsets.UTF_8), HMAC_ALGO)
    }

    /**
     * Generates the required headers for REST API calls.
     */
    fun createAuthHeaders(method: String, requestPath: String, body: String = ""): Map<String, String> {
        val timestamp = TIMESTAMP_FORMATTER.format(Instant.now())
        val signature = sign(timestamp, method, requestPath, body)

        return buildMap {
            put("OK-ACCESS-KEY", okxProperties.apiKey)
            put("OK-ACCESS-SIGN", signature)
            put("OK-ACCESS-TIMESTAMP", timestamp)
            put("OK-ACCESS-PASSPHRASE", okxProperties.passphrase)
            put("Content-Type", "application/json")
        }
    }

    /**
     * Compute OKX signature: Base64(HmacSHA256(timestamp + UPPER(method) + requestPath + body, secretKey)).
     * Used by both REST headers and WebSocket login.
     */
    fun sign(timestamp: String, method: String, requestPath: String, body: String = ""): String {
        // String templates are often more efficient and readable than StringBuilder for simple concatenations
        val preHash = "$timestamp${method.uppercase(Locale.ROOT)}$requestPath$body"

        // Mac is NOT thread-safe, so we must instantiate it per request.
        // However, getInstance is relatively cheap compared to key initialization.
        val mac = Mac.getInstance(HMAC_ALGO).apply {
            init(secretKeySpec)
        }

        val signatureBytes = mac.doFinal(preHash.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signatureBytes)
    }
}