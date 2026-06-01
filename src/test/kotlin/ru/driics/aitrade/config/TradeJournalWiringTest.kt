package ru.driics.aitrade.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.infra.persistence.JdbcTradeJournal
import ru.driics.aitrade.infra.persistence.NoOpTradeJournal

/**
 * Proves the opt-in wiring: exactly one [TradeJournalPort] bean is active in each mode, with no
 * datasource required when disabled. Catches the duplicate-bean / ordering hazard the enabled path
 * would otherwise hide (the default-config suite only exercises the disabled branch).
 */
class TradeJournalWiringTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TradeJournalPersistenceConfig::class.java, TradeJournalDefaultConfig::class.java)

    @Test
    fun `disabled by default wires the NoOp journal and needs no datasource`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(TradeJournalPort::class.java)
            assertThat(ctx.getBean(TradeJournalPort::class.java)).isInstanceOf(NoOpTradeJournal::class.java)
        }
    }

    @Test
    fun `enabled wires a single JdbcTradeJournal and runs the changelog on H2`() {
        runner.withPropertyValues(
            "trade-journal.enabled=true",
            "spring.datasource.url=jdbc:h2:mem:wiretest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(TradeJournalPort::class.java)
            assertThat(ctx.getBean(TradeJournalPort::class.java)).isInstanceOf(JdbcTradeJournal::class.java)
        }
    }
}
