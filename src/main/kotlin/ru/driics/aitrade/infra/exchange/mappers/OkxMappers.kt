package ru.driics.aitrade.infra.exchange.mappers

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.domain.model.Candle
import ru.driics.aitrade.model.OkxCandleResponse
import java.math.BigDecimal

private val logger = KotlinLogging.logger("OkxMapper")

/**
 * Maps OKX wire DTOs to domain models.
 */
fun OkxCandleResponse.toDomain(): Candle {
    val parsedTimestamp = this.timestamp.toLongOrNull()

    if (parsedTimestamp == null) {
        logger.warn { "Invalid timestamp received from OKX API: '$timestamp' for candle data" }
    }

    return Candle(
        timestamp = parsedTimestamp ?: Long.MAX_VALUE,
        open = this.open.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        high = this.high.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        low = this.low.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        close = this.close.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        volume = this.volume.toBigDecimalOrNull() ?: BigDecimal.ZERO
    )
}