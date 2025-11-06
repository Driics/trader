package ru.driics.aitrade.infra.exchange.mappers

import ru.driics.aitrade.domain.model.Candle
import ru.driics.aitrade.model.OkxCandleResponse
import java.math.BigDecimal

/**
 * Maps OKX wire DTOs to domain models.
 */
fun OkxCandleResponse.toDomain(): Candle = Candle(
    timestamp = this.timestamp.toLongOrNull() ?: 0L,
    open = this.open.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    high = this.high.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    low = this.low.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    close = this.close.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    volume = this.volume.toBigDecimalOrNull() ?: BigDecimal.ZERO
)