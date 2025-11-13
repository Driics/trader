package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.MarketState

interface MarketDataPort {
    suspend fun loadMarketState(symbols: List<String>): MarketState
}