package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.model.MarketState

interface MarketDataPort {
    suspend fun loadMarketState(symbols: List<String>): MarketState
}