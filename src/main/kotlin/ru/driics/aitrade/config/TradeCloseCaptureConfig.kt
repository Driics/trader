package ru.driics.aitrade.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.driics.aitrade.application.journal.TradeCloseCollector
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.time.Clock

/**
 * Enabled-gated wiring for trade-close capture. Active only when `trade-journal.enabled=true` (mirrors
 * [TradeJournalPersistenceConfig]), so the collector is created exactly when the journal it writes to is.
 *
 * The collector's collaborators are declared as ordinary `@Bean` method parameters and resolved by type
 * at instantiation time. This is deterministic and NOT order-fragile, unlike `@ConditionalOnBean`, which
 * Spring documents as unreliable outside auto-configuration (it only sees bean definitions registered so
 * far). With `@ConditionalOnBean(ClosedPositionsPort)` the condition could evaluate before the
 * component-scanned [ru.driics.aitrade.exchange...] adapter was registered, silently dropping the
 * collector and capturing no closes even with the journal enabled.
 */
@Configuration
@ConditionalOnProperty(prefix = "trade-journal", name = ["enabled"], havingValue = "true")
class TradeCloseCaptureConfig {

    @Bean
    fun tradeCloseCollector(
        closedPositions: ClosedPositionsPort,
        tradeJournal: TradeJournalPort,
        tradeJournalQuery: TradeJournalQueryPort,
        clock: Clock,
        tradingProperties: TradingProperties,
        okxProperties: OkxProperties,
    ): TradeCloseCollector = TradeCloseCollector(
        source = closedPositions,
        journal = tradeJournal,
        query = tradeJournalQuery,
        clock = clock,
        mode = TradingMode.resolve(tradingProperties.demoMode, okxProperties.paper),
        demo = tradingProperties.demoMode,
    )
}
