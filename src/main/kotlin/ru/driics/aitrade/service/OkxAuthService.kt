package ru.driics.aitrade.service

import org.springframework.stereotype.Service
import ru.driics.aitrade.config.OkxProperties
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
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
}