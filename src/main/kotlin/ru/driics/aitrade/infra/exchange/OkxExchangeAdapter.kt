package ru.driics.aitrade.infra.exchange

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.*
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.springframework.stereotype.Service
import ru.driics.aitrade.common.traced
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.domain.types.OrderSide
import ru.driics.aitrade.domain.types.Symbol
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.domain.util.quantize
import ru.driics.aitrade.infra.cache.CachedIndicatorCalculator
import ru.driics.aitrade.infra.cache.SmartCacheStrategy
import ru.driics.aitrade.infra.cache.getTyped
import ru.driics.aitrade.service.OkxRestClient
import ru.driics.aitrade.service.okx.OkxCallOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class OkxExchangeAdapter(
    private val rest: OkxRestClient,
    private val tradingProperties: TradingProperties,
    private val tracer: Tracer,
    private val smartCache: SmartCacheStrategy,
    private val indicators: CachedIndicatorCalculator,
    private val clock: Clock,
    private val instrumentResolver: InstrumentResolver,
) : MarketDataPort, TradingPort {

    private val log = KotlinLogging.logger {}

    private val sessionStartTime = clock.millis()
    private val invocationCount = AtomicLong(0L)
    private val initialEquity = AtomicReference<BigDecimal?>(null)
    private val candles4HCache = ConcurrentHashMap<String, CachedCandles>()

    // =========================================================================
    // MarketDataPort Implementation
    // =========================================================================

    override suspend fun loadMarketState(symbols: List<Symbol>): MarketState = coroutineScope {
        val count = invocationCount.incrementAndGet()
        val fetchStart = clock.millis()

        log.debug { "Loading market state for ${symbols.size} symbols (#$count)" }

        val positionsDeferred = async(Dispatchers.IO) {
            tracer.traced("fetch_positions") { fetchPositions() }
        }

        val currenciesDeferred = async(Dispatchers.IO) {
            fetchAllCurrencyData(symbols.map(Symbol::value))
        }

        val positions = positionsDeferred.await()

        val accountDeferred = async(Dispatchers.IO) {
            tracer.traced("fetch_account") { fetchAccountInfo(positions) }
        }

        MarketState(
            timestamp = fetchStart,
            minutesSinceStart = (fetchStart - sessionStartTime) / MILLIS_PER_MINUTE,
            invocationCount = count,
            currencies = currenciesDeferred.await().associateBy(CurrencyMarketData::symbol),
            account = accountDeferred.await(),
            positions = positions
        )
    }

    override suspend fun loadInstrument(instrumentId: InstrumentId): TradeResult<OkxInstrumentInfo> {
        val instId = instrumentId.value

        return runCatching {
            val info = smartCache.getTyped<OkxInstrumentInfo>(
                key = "${CacheKeys.INSTRUMENT}$instId",
                level = SmartCacheStrategy.CacheLevel.L3,
                fetcher = {
                    rest.getSwapInstrument(instId)
                        ?: throw IllegalArgumentException("Instrument not found: $instId")
                }
            )
            TradeResult.Success(info)
        }.getOrElse { it.toTradeFailure(instId) }
    }

    override suspend fun getLastPrice(instrumentId: InstrumentId): TradeResult<BigDecimal?> {
        val instId = instrumentId.value

        return runCatching {
            val ticker = rest.fetchTicker(instId)
            val price = ticker?.run {
                sequenceOf(lastPrice, askPrice, bidPrice)
                    .firstNotNullOfOrNull { it.toPositiveBigDecimal() }
            }
            TradeResult.Success(price)
        }.getOrElse { it.toTradeFailure(instId) }
    }

    // =========================================================================
    // TradingPort Implementation
    // =========================================================================

    override suspend fun placeMarketOrderWithTpSl(
        instrumentId: InstrumentId,
        side: OrderSide,
        contracts: BigDecimal,
        tp: BigDecimal?,
        sl: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String,
        tag: String?,
        marginMode: MarginMode
    ): TradeResult<PlaceOrderOutcome> = when (
        val outcome = rest.placeMarketOrderWithAttach(
            instId = instrumentId.value,
            side = side.value,
            tdMode = marginMode.asOkxApiValue,
            szContracts = contracts.quantizeToString(),
            tpPx = tp?.quantizeToString(tickSz),
            slPx = sl?.quantizeToString(tickSz),
            posSide = null,
            clOrdId = clOrdId,
            tag = tag
        )
    ) {
        // Success implies the exchange accepted the order (sCode == "0") — see OkxPlaceOrderApiResponse.isSuccess().
        is OkxCallOutcome.Success -> TradeResult.Success(
            PlaceOrderOutcome(ok = true, ordId = outcome.value.ordId, message = "Placed")
        )
        is OkxCallOutcome.RejectedByExchange ->
            TradeResult.Failure.ApiError(code = outcome.code, message = outcome.message)
        // S4: a timeout on order placement is NOT a clean rejection — the order MAY exist. Surface
        // it distinctly so the caller treats it as an error (and the clOrdId guards any retry).
        is OkxCallOutcome.TimeoutUnknown ->
            TradeResult.Failure.ApiError(code = "TIMEOUT_UNKNOWN", message = outcome.message)
        is OkxCallOutcome.TransportError ->
            outcome.cause?.let { TradeResult.Failure.NetworkError(outcome.message, it) }
                ?: TradeResult.Failure.ApiError(code = "TRANSPORT_ERROR", message = outcome.message)
    }

    override suspend fun setLeverage(
        instrumentId: InstrumentId,
        leverage: Int,
        marginMode: MarginMode
    ): TradeResult<Boolean> = when (val outcome = rest.setLeverage(instrumentId.value, leverage, marginMode)) {
        is OkxCallOutcome.Success -> TradeResult.Success(true)
        // Clean rejection: leverage unchanged -> the caller skips the trade as a business decision.
        is OkxCallOutcome.RejectedByExchange -> TradeResult.Success(false)
        // Timeout/transport: do NOT assume the leverage stuck; surface as failure (trade skipped as error).
        is OkxCallOutcome.TimeoutUnknown -> TradeResult.Failure.ApiError("TIMEOUT_UNKNOWN", outcome.message)
        is OkxCallOutcome.TransportError -> outcome.cause?.let { TradeResult.Failure.NetworkError(outcome.message, it) }
            ?: TradeResult.Failure.ApiError("TRANSPORT_ERROR", outcome.message)
    }

    /**
     * Sums realized PnL across account bills generated since the start of the current UTC day
     * (matching [ru.driics.aitrade.application.risk.KillSwitchState]'s UTC auto-clear boundary).
     *
     * Pages backwards (newest-first) until a bill predates UTC midnight or the page is short.
     * A failed page read returns [TradeResult.Failure] so the caller can fail CLOSED (S1) rather
     * than mistaking an outage for "no loss today".
     *
     * !! UNVERIFIED against live OKX !! The per-bill contribution is isolated in
     * [realizedPnlContribution]; reconcile it in paper trading before trusting live funds (B0).
     */
    override suspend fun getTodaysRealizedPnlUsd(now: java.time.Instant): TradeResult<BigDecimal> {
        val dayStartMs = now.truncatedTo(ChronoUnit.DAYS).toEpochMilli()
        return try {
            var after: String? = null
            var sum = BigDecimal.ZERO
            var page = 0
            // Diagnostics only (never affect `sum`): which settlement currencies and bill types actually
            // contributed realized PnL today, so a real trading day auto-reveals whether funding/fees or
            // non-USD settlement pollute the cap — the one assumption still unverified against live OKX.
            val contributingCcys = mutableSetOf<String>()
            val pnlByType = mutableMapOf<String, BigDecimal>()
            while (page < MAX_BILL_PAGES) {
                page++
                val response = rest.fetchBills(after = after, limit = BILL_PAGE_LIMIT)
                    ?: return TradeResult.Failure.ApiError(
                        code = "BILLS_READ_FAILED",
                        message = "Failed to read account bills (page $page) — failing closed"
                    )
                if (!response.isSuccess()) {
                    return TradeResult.Failure.ApiError(
                        code = response.code,
                        message = response.message.ifBlank { "Bills read returned error code ${response.code}" }
                    )
                }

                val bills = response.data
                if (bills.isEmpty()) break

                for (bill in bills) {
                    val ts = bill.timestamp.toLongOrNull() ?: continue
                    if (ts < dayStartMs) continue
                    val contribution = bill.realizedPnlContribution()
                    sum += contribution
                    if (contribution.signum() != 0) {
                        contributingCcys += bill.currency.uppercase()
                        pnlByType.merge(bill.type, contribution) { a, b -> a + b }
                    }
                }

                val oldestTs = bills.last().timestamp.toLongOrNull() ?: Long.MIN_VALUE
                if (oldestTs < dayStartMs || bills.size < BILL_PAGE_LIMIT) break
                after = bills.last().billId
            }
            logDailyPnlComposition(sum, contributingCcys, pnlByType)
            TradeResult.Success(sum)
        } catch (e: Exception) {
            TradeResult.Failure.NetworkError("Error reading today's realized PnL: ${e.message}", e)
        }
    }

    /**
     * !! UNVERIFIED against live OKX — THE single money-handling assumption !!
     *
     * The daily-loss cap depends on this being correct. We assume each bill's `pnl` field carries
     * realized trading P&L in the settlement currency (negative = loss) and is zero for non-P&L
     * bills (transfers, etc.). Funding fees MAY also surface here. This is the ONE knob to reconcile
     * against the OKX account UI in paper trading before trusting live funds: if funding/fees must
     * be excluded, filter on [OkxBillData.type]/[OkxBillData.subType] here. See B0 in
     * docs/architecture-refactor-roadmap.md.
     */
    private fun OkxBillData.realizedPnlContribution(): BigDecimal =
        pnl.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /**
     * Surfaces the composition of today's realized-PnL sum so the (still-unverified) assumption that the
     * bills `pnl` field is clean realized trading P&L gets checked automatically the first time real
     * trades flow — without an operator running the capture/reconcile flow. Pure observability: it never
     * changes the summed value.
     */
    private fun logDailyPnlComposition(
        sum: BigDecimal,
        contributingCcys: Set<String>,
        pnlByType: Map<String, BigDecimal>,
    ) {
        if (pnlByType.isEmpty()) return // no realized PnL today — nothing to reconcile
        log.debug { "Daily realized PnL=$sum by bill type=$pnlByType ccys=$contributingCcys" }
        val nonUsd = contributingCcys - USD_QUOTE_CCYS
        if (nonUsd.isNotEmpty()) {
            log.warn {
                "Daily-loss cap summed non-USD-settled bills as USD (ccys=$nonUsd, pnlByType=$pnlByType). " +
                    "The cap may be miscounting losses — reconcile via the capture-okx profile (see " +
                    "PnlReconciliation) before trusting it for these instruments."
            }
        }
    }

    // =========================================================================
    // Currency Data Fetching
    // =========================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetchAllCurrencyData(symbols: List<String>): List<CurrencyMarketData> =
        symbols.asFlow()
            .flatMapMerge(concurrency = tradingProperties.maxConcurrentSymbols) { symbol ->
                flow { emit(fetchCurrencyDataSafe(symbol)) }
            }
            .toList()

    private suspend fun fetchCurrencyDataSafe(symbol: String): CurrencyMarketData =
        runCatching {
            withTimeout(Timeouts.CURRENCY_FETCH_MS) { fetchCurrencyData(symbol) }
        }.getOrElse { e ->
            log.error(e) { "Failed to fetch data for $symbol" }
            emptyCurrencyData(symbol)
        }

    private suspend fun fetchCurrencyData(symbol: String): CurrencyMarketData = coroutineScope {
        val instId = instrumentResolver.instrumentId(symbol).value

        // Parallel API calls
        val tickerDef = async { rest.fetchTicker(instId) }
        val candles3mDef = async { rest.fetchCandles(instId, Timeframe.M3, CandleCount.M3) }
        val candles4hDef = async { fetchCached4HCandles(symbol, instId) }
        val fundingDef = async { rest.fetchFundingRate(instId) }
        val openInterestDef = async { rest.fetchOpenInterest(instId) }

        val candles3m = candles3mDef.await()
        val prices3m = candles3m.extractClosePrices()

        val candles4h = candles4hDef.await()
        val prices4h = candles4h.extractClosePrices()

        // Calculate indicators in parallel
        val intradayDef = async(Dispatchers.Default) { calculateIntradayIndicators(prices3m) }
        val indicators4hDef = async(Dispatchers.Default) { calculate4HIndicators(candles4h, prices4h) }

        val ticker = tickerDef.await()
        val intraday = intradayDef.await()
        val ind4h = indicators4hDef.await()

        CurrencyMarketData(
            symbol = symbol,
            currentPrice = ticker?.bidPrice.toBigDecimalSafe(),
            currentEma20 = intraday.currentEma20,
            currentMacd = intraday.currentMacd,
            currentRsi7 = intraday.currentRsi7,
            openInterest = openInterestDef.await(),
            fundingRate = fundingDef.await(),
            intradayPrices = intraday.prices,
            intradayEma20 = intraday.ema20,
            intradayMacd = intraday.macd,
            intradayRsi7 = intraday.rsi7,
            intradayRsi14 = intraday.rsi14,
            ema20_4h = ind4h.ema20,
            ema50_4h = ind4h.ema50,
            atr3_4h = ind4h.atr3,
            atr14_4h = ind4h.atr14,
            volume4h = ind4h.volume,
            avgVolume4h = ind4h.avgVolume,
            macd4h = ind4h.macd,
            rsi14_4h = ind4h.rsi14
        )
    }

    // =========================================================================
    // Indicator Calculations
    // =========================================================================

    private suspend fun calculateIntradayIndicators(prices: List<BigDecimal>): IntradayIndicators =
        coroutineScope {
            val currentEma20 = async { indicators.calculateEMA(prices, Period.EMA_20) }
            val currentMacd = async { indicators.calculateMACD(prices) }
            val currentRsi7 = async { indicators.calculateRSI(prices, Period.RSI_7) }
            val progressiveEma20 = async { indicators.calculateProgressiveEMA(prices, Period.EMA_20) }
            val progressiveMacd = async { indicators.calculateProgressiveMACD(prices) }
            val progressiveRsi7 = async { indicators.calculateProgressiveRSI(prices, Period.RSI_7) }
            val progressiveRsi14 = async { indicators.calculateProgressiveRSI(prices, Period.RSI_14) }

            IntradayIndicators(
                currentEma20 = currentEma20.await(),
                currentMacd = currentMacd.await(),
                currentRsi7 = currentRsi7.await(),
                prices = prices.takeLast(SERIES_SIZE),
                ema20 = progressiveEma20.await().takeLast(SERIES_SIZE),
                macd = progressiveMacd.await().takeLast(SERIES_SIZE),
                rsi7 = progressiveRsi7.await().takeLast(SERIES_SIZE),
                rsi14 = progressiveRsi14.await().takeLast(SERIES_SIZE)
            )
        }

    private suspend fun calculate4HIndicators(
        candles: List<OkxCandleResponse>,
        prices: List<BigDecimal>
    ): Indicators4H = coroutineScope {
        val ema20 = async { indicators.calculateEMA(prices, Period.EMA_20) }
        val ema50 = async { indicators.calculateEMA(prices, Period.EMA_50) }
        val atr3 = async { indicators.calculateATR(candles, Period.ATR_3) }
        val atr14 = async { indicators.calculateATR(candles, Period.ATR_14) }
        val macd = async { indicators.calculateProgressiveMACD(prices) }
        val rsi14 = async { indicators.calculateProgressiveRSI(prices, Period.RSI_14) }

        Indicators4H(
            ema20 = ema20.await(),
            ema50 = ema50.await(),
            atr3 = atr3.await(),
            atr14 = atr14.await(),
            volume = candles.lastOrNull()?.volume.toBigDecimalSafe(),
            avgVolume = candles.averageVolume(Period.AVG_VOLUME),
            macd = macd.await().padToSize(SERIES_SIZE),
            rsi14 = rsi14.await().padToSize(SERIES_SIZE)
        )
    }

    // =========================================================================
    // Account & Position Fetching
    // =========================================================================

    private suspend fun fetchAccountInfo(positions: List<Position>): AccountInfo {
        val account = rest.fetchAccount()
        val totalEquity = account.totalEquity.toBigDecimalSafe()
        val availableBalance = account.availableBalance.toBigDecimalSafe()

        val baseline = initialEquity.updateAndGet { it ?: totalEquity } ?: totalEquity

        val totalReturnPct = if (baseline > BigDecimal.ZERO) {
            (totalEquity - baseline)
                .divide(baseline, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
        } else {
            BigDecimal.ZERO
        }

        val effectiveCash = when {
            positions.isEmpty() && availableBalance.signum() == 0 -> totalEquity
            else -> availableBalance
        }

        return AccountInfo(
            totalReturn = totalReturnPct,
            availableCash = effectiveCash,
            accountValue = totalEquity,
            sharpeRatio = null
        )
    }

    private suspend fun fetchPositions(): List<Position> =
        rest.fetchOpenPositions().mapNotNull { pos ->
            runCatching {
                Position(
                    symbol = pos.instrumentId.substringBefore("-"),
                    quantity = pos.quantity.toBigDecimalSafe(),
                    entryPrice = pos.averagePrice.toBigDecimalSafe(),
                    currentPrice = pos.markPrice.toBigDecimalSafe(),
                    liquidationPrice = pos.liquidationPrice.toPositiveBigDecimal(),
                    unrealizedPnl = pos.unrealizedPnl.toBigDecimalSafe(),
                    leverage = pos.leverage.toIntOrNull()
                )
            }.onFailure {
                log.warn { "Failed to parse position: ${pos.instrumentId}" }
            }.getOrNull()
        }

    // =========================================================================
    // Caching
    // =========================================================================

    private suspend fun fetchCached4HCandles(symbol: String, instId: String): List<OkxCandleResponse> {
        val newCandles = rest.fetchCandles(instId, Timeframe.H4, CandleCount.H4)

        if (newCandles.isEmpty()) {
            log.warn { "No 4H candles received for $symbol" }
            return emptyList()
        }

        val latestCandle = newCandles.first()
        val newKey = CandleCacheKey(
            timestamp = latestCandle.timestamp?.toLongOrNull() ?: 0L,
            closeHash = latestCandle.close.hashCode()
        )

        val cached = candles4HCache[symbol]
        if (cached?.key == newKey) {
            return cached.candles
        }

        return newCandles.also {
            candles4HCache[symbol] = CachedCandles(candles = it, key = newKey)
        }
    }

    // =========================================================================
    // Extensions & Helpers
    // =========================================================================

    private fun Throwable.toTradeFailure(context: String): TradeResult.Failure = when (this) {
        is ClientRequestException -> TradeResult.Failure.NetworkError(
            message = "HTTP ${response.status}",
            cause = this
        )
        is IllegalArgumentException -> TradeResult.Failure.ApiError(
            code = "BAD_ARGUMENT",
            message = message ?: "Invalid argument"
        )
        else -> TradeResult.Failure.ApiError(
            code = "UNKNOWN",
            message = "Error for $context: ${message ?: "Unknown"}",
            cause = this
        )
    }

    private fun String?.toBigDecimalSafe(): BigDecimal =
        this?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun String?.toPositiveBigDecimal(): BigDecimal? =
        this!!.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

    private fun BigDecimal.quantizeToString(tickSize: BigDecimal? = null): String =
        (tickSize?.let { quantize(it) } ?: this)
            .stripTrailingZeros()
            .toPlainString()

    private fun List<OkxCandleResponse>.extractClosePrices(): List<BigDecimal> =
        mapNotNull { it.close.toBigDecimalOrNull() }

    private fun List<OkxCandleResponse>.averageVolume(period: Int): BigDecimal {
        if (isEmpty()) return BigDecimal.ZERO

        val volumes = takeLast(period).mapNotNull { it.volume.toBigDecimalOrNull() }
        if (volumes.isEmpty()) return BigDecimal.ZERO

        return volumes.reduce(BigDecimal::add)
            .divide(volumes.size.toBigDecimal(), 10, RoundingMode.HALF_UP)
    }

    private fun List<BigDecimal>.padToSize(targetSize: Int): List<BigDecimal> {
        val tail = takeLast(targetSize)
        val padding = targetSize - tail.size
        return if (padding > 0) List(padding) { BigDecimal.ZERO } + tail else tail
    }

    private fun emptyCurrencyData(symbol: String) = CurrencyMarketData(
        symbol = symbol,
        currentPrice = BigDecimal.ZERO,
        currentEma20 = BigDecimal.ZERO,
        currentMacd = BigDecimal.ZERO,
        currentRsi7 = BigDecimal.ZERO
    )

    // =========================================================================
    // Data Classes
    // =========================================================================

    private data class IntradayIndicators(
        val currentEma20: BigDecimal,
        val currentMacd: BigDecimal,
        val currentRsi7: BigDecimal,
        val prices: List<BigDecimal>,
        val ema20: List<BigDecimal>,
        val macd: List<BigDecimal>,
        val rsi7: List<BigDecimal>,
        val rsi14: List<BigDecimal>
    )

    private data class Indicators4H(
        val ema20: BigDecimal,
        val ema50: BigDecimal,
        val atr3: BigDecimal,
        val atr14: BigDecimal,
        val volume: BigDecimal,
        val avgVolume: BigDecimal,
        val macd: List<BigDecimal>,
        val rsi14: List<BigDecimal>
    )

    private data class CandleCacheKey(
        val timestamp: Long,
        val closeHash: Int
    )

    private data class CachedCandles(
        val candles: List<OkxCandleResponse>,
        val key: CandleCacheKey
    )

    // =========================================================================
    // Constants
    // =========================================================================

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val SERIES_SIZE = 10
        val HUNDRED: BigDecimal = BigDecimal(100)

        // Realized-PnL pagination (B0). Bounded so a misbehaving cursor can't loop forever.
        const val MAX_BILL_PAGES = 20
        const val BILL_PAGE_LIMIT = 100

        // Settlement currencies the daily-loss cap may treat as ~1 USD. A contributing bill in any
        // OTHER currency means the cap is summing mixed units as if USD — surfaced as a WARN.
        val USD_QUOTE_CCYS = setOf("USDT", "USD", "USDC", "USB")
    }

    private object Timeframe {
        const val M3 = "3m"
        const val H4 = "4H"
    }

    private object CandleCount {
        const val M3 = 100
        const val H4 = 200
    }

    private object Period {
        const val EMA_20 = 20
        const val EMA_50 = 50
        const val RSI_7 = 7
        const val RSI_14 = 14
        const val ATR_3 = 3
        const val ATR_14 = 14
        const val AVG_VOLUME = 20
    }

    private object Timeouts {
        const val CURRENCY_FETCH_MS = 5_000L
    }

    private object CacheKeys {
        const val INSTRUMENT = "instrument:"
    }
}