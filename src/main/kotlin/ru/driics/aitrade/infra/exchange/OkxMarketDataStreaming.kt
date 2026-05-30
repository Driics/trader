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
    // WIP / DEAD CODE: depends on a streamCurrencyData(symbols) source on OkxExchangeAdapter that does
    // not exist, and loadMarketStateAsFlow has no callers. Stubbed to unblock compilation without
    // guessing a streaming implementation. Either implement streamCurrencyData() or delete this file.
    // Original intent:
    //   streamCurrencyData(symbols).map { Result.success(it) }.catch { emit(Result.failure(it)) }
    TODO("streamCurrencyData() is not implemented; loadMarketStateAsFlow is unused WIP")