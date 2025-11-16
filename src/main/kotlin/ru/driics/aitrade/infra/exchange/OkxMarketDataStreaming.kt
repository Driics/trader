package ru.driics.aitrade.infra.exchange

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import ru.driics.aitrade.domain.model.CurrencyMarketData

/**
 * Extension to load market data as a Flow, emitting results as they become ready.
 * Uses the internal streaming implementation for better performance.
 */
fun OkxExchangeAdapter.loadMarketStateAsFlow(symbols: List<String>): Flow<Result<CurrencyMarketData>> =
    streamCurrencyData(symbols)
        .map { data -> Result.success(data) }
        .catch { e -> emit(Result.failure(e)) }