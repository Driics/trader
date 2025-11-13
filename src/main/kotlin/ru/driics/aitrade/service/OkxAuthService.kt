package ru.driics.aitrade.service

import org.springframework.stereotype.Service
import ru.driics.aitrade.config.OkxProperties
import java.nio.charset.StandardCharsets
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
    fun createAuthHeaders(method: String, requestPath: String, body: String = ""): Map<String, String> {
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

        val message = "$timestamp${method.uppercase()}$requestPath$body"

        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(okxProperties.secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)

        val signature = Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))

        return mapOf(
            "OK-ACCESS-KEY" to okxProperties.apiKey,
            "OK-ACCESS-SIGN" to signature,
            "OK-ACCESS-TIMESTAMP" to timestamp,
            "OK-ACCESS-PASSPHRASE" to okxProperties.passphrase,
            "Content-Type" to "application/json"
        )
    }

    /**
     * Compute OKX signature: Base64(HmacSHA256(timestamp + UPPER(method) + requestPath + body, secretKey)).
     * Use requestPath ONLY (e.g., "/users/self/verify"), not full URL. Body is "" for WS login.
     */
    fun sign(timestamp: String, method: String, requestPath: String, body: String = ""): String {
        val prehash = buildString {
            append(timestamp)
            append(method.uppercase(Locale.ROOT))
            append(requestPath)
            append(body)
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(okxProperties.secretKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(prehash.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}