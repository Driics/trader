package ru.driics.aitrade.config

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.driics.aitrade.application.journal.TradeCloseCollector
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.time.Clock

/**
 * Proves the trade-close collector is RELIABLY wired when the journal is enabled and absent when it is
 * not. The collaborators are supplied as beans so the collector resolves them by type at instantiation
 * time — the deterministic wiring this config replaced the order-fragile `@ConditionalOnBean` with.
 */
class TradeCloseCaptureConfigTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TradeCloseCaptureConfig::class.java)
        .withBean(ClosedPositionsPort::class.java, { mockk<ClosedPositionsPort>(relaxed = true) })
        .withBean(TradeJournalPort::class.java, { mockk<TradeJournalPort>(relaxed = true) })
        .withBean(TradeJournalQueryPort::class.java, { mockk<TradeJournalQueryPort>(relaxed = true) })
        .withBean(Clock::class.java, { Clock.systemUTC() })
        // @ConfigurationProperties data classes with all-default ctors — real instances wire cleanly.
        .withBean(TradingProperties::class.java, { TradingProperties() })
        .withBean(OkxProperties::class.java, { OkxProperties() })

    @Test
    fun `collector is wired when journal enabled`() {
        runner.withPropertyValues("trade-journal.enabled=true").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(TradeCloseCollector::class.java)
        }
    }

    @Test
    fun `no collector when journal disabled`() {
        runner.run { ctx -> // enabled unset -> config inactive
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(TradeCloseCollector::class.java)
        }
    }
}
