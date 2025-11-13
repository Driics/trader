package ru.driics.aitrade.infra.exchange

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.driics.aitrade.domain.model.CurrencyMarketData

/**
 * Extension to load market data as a Flow, emitting results as they become ready.
 */
fun OkxExchangeAdapter.loadMarketStateAsFlow(symbols: List<String>): Flow<Result<CurrencyMarketData>> = flow {
    coroutineScope {
        symbols.map { symbol ->
            async {
                runCatching {
                    fetchCurrencyData(symbol)
                }
            }
        }.forEach { deferred ->
            emit(deferred.await())
        }
    }
}.buffer(capacity = symbols.size)