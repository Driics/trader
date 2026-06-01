package ru.driics.aitrade.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import liquibase.integration.spring.SpringLiquibase
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import ru.driics.aitrade.infra.persistence.JdbcTradeJournal
import ru.driics.aitrade.infra.persistence.JdbcTradeJournalQuery
import ru.driics.aitrade.infra.persistence.NoOpTradeJournal
import ru.driics.aitrade.infra.persistence.NoOpTradeJournalQuery
import javax.sql.DataSource

/**
 * Opt-in wiring for the trade journal. Active only when `trade-journal.enabled=true`; it builds its own
 * HikariCP datasource, runs the Liquibase changelog, and provides a [JdbcTradeJournal]. Because
 * [org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration] and
 * [org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration] are excluded in
 * [ru.driics.aitrade.AiTraderApplication], none of this runs (and no datasource is required) by default.
 */
@Configuration
@ConditionalOnProperty(prefix = "trade-journal", name = ["enabled"], havingValue = "true")
class TradeJournalPersistenceConfig {

    @Bean(destroyMethod = "close")
    fun tradeJournalDataSource(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username:}") username: String,
        @Value("\${spring.datasource.password:}") password: String,
        @Value("\${spring.datasource.driver-class-name:org.postgresql.Driver}") driverClassName: String,
    ): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = url
            this.username = username
            this.password = password
            this.driverClassName = driverClassName
            maximumPoolSize = 3
            poolName = "trade-journal"
        }
        return HikariDataSource(config)
    }

    @Bean
    fun tradeJournalLiquibase(tradeJournalDataSource: DataSource): SpringLiquibase {
        val liquibase = SpringLiquibase()
        liquibase.dataSource = tradeJournalDataSource
        liquibase.changeLog = "classpath:db/changelog/db.changelog-master.yaml"
        return liquibase
    }

    @Bean
    fun tradeJournalJdbcTemplate(tradeJournalDataSource: DataSource): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(tradeJournalDataSource)

    @Bean
    fun jdbcTradeJournal(tradeJournalJdbcTemplate: NamedParameterJdbcTemplate): TradeJournalPort =
        JdbcTradeJournal(tradeJournalJdbcTemplate)

    @Bean
    fun tradeJournalQuery(tradeJournalJdbcTemplate: NamedParameterJdbcTemplate): TradeJournalQueryPort =
        JdbcTradeJournalQuery(tradeJournalJdbcTemplate)
}

/**
 * Fallback so [TradeJournalPort] always has a bean. Gated on the SAME property as
 * [TradeJournalPersistenceConfig] but inverted (`havingValue="false"`, `matchIfMissing=true`), so
 * exactly one of NoOp / Jdbc is ever active — deterministically, with no bean-ordering dependency
 * (`@ConditionalOnMissingBean` across user configs is order-fragile).
 */
@Configuration
class TradeJournalDefaultConfig {

    @Bean
    @ConditionalOnProperty(prefix = "trade-journal", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpTradeJournal(): TradeJournalPort = NoOpTradeJournal()

    @Bean
    @ConditionalOnProperty(prefix = "trade-journal", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpTradeJournalQuery(): TradeJournalQueryPort = NoOpTradeJournalQuery()
}
