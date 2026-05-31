package ru.driics.aitrade

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The JDBC starter is on the classpath only for the opt-in trade journal (see
 * [ru.driics.aitrade.config.TradeJournalPersistenceConfig]). Its auto-configuration is excluded so the
 * app boots with NO datasource by default — the journal wires its own datasource and Liquibase runner
 * only when `trade-journal.enabled=true`.
 */
@SpringBootApplication(
    exclude = [DataSourceAutoConfiguration::class, LiquibaseAutoConfiguration::class]
)
@EnableScheduling
class AiTraderApplication

fun main(args: Array<String>) {
    runApplication<AiTraderApplication>(*args)
}
