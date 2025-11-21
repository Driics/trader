package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.types.Symbol

/**
 * Port for loading market data.
 */
interface MarketDataPort {
    suspend fun loadMarketState(symbols: List<Symbol>): MarketState
}