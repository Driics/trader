package ru.driics.aitrade.domain.types

/**
 * Turns a trading [Symbol] (base asset, e.g. BTC) into the OKX instId for this deployment's configured
 * quote currency and instrument type. It is the single source of truth for symbol -> instId on the
 * production path, so adding a new pair is a config change (`trading.currencies`) rather than a code
 * change, and the quote/type live in exactly one place instead of being hardcoded at every call site.
 */
class InstrumentResolver(
    private val quoteCurrency: String,
    private val instrumentType: String,
) {
    fun instrumentId(symbol: Symbol): InstrumentId =
        InstrumentId.of(symbol, quoteCurrency, instrumentType)

    fun instrumentId(symbol: String): InstrumentId =
        instrumentId(Symbol.from(symbol))
}
