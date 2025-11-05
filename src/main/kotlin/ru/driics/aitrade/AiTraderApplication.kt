package ru.driics.aitrade

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AiTraderApplication

fun main(args: Array<String>) {
    runApplication<AiTraderApplication>(*args)
}
