package ru.driics.aitrade.infra.exchange

import kotlinx.coroutines.flow.Flow
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import ru.driics.aitrade.domain.ports.*
import ru.driics.aitrade.domain.types.Symbol
import ru.driics.aitrade.domain.types.asSymbol
import java.math.BigDecimal

@Primary
@Component
class HybridMarketDataAdapter(
    private val rest: OkxExchangeAdapter,
    private val streaming: OkxStreamingAdapter
) : MarketDataPort, StreamingMarketDataPort {

    // Delegate existing REST behavior
    override suspend fun loadMarketState(symbols: List<Symbol>) = rest.loadMarketState(symbols)

    // Provide streaming APIs
    override fun getRealtimePrice(instId: String): BigDecimal? = streaming.getRealtimePrice(instId)
    override fun observePriceUpdates(instId: String): Flow<PriceUpdate> = streaming.observePriceUpdates(instId)
    override fun observeOrderUpdates(): Flow<OrderEvent> = streaming.observeOrderUpdates()
    override fun observePositionUpdates(): Flow<PositionEvent> = streaming.observePositionUpdates()
}