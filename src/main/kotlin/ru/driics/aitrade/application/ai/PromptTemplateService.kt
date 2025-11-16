package ru.driics.aitrade.application.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for loading and rendering prompt templates with versioning support.
 */
class PromptTemplateService(
    private val resourceLoader: ResourceLoader,
    private val clock: Clock,
    val version: String = "v1"
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Loads system prompt template for the specified version.
     */
    fun loadSystemPrompt(): String {
        return loadTemplate("prompts/$version/system.txt")
    }

    /**
     * Loads user prompt template for the specified version.
     */
    fun loadUserPrompt(): String {
        return loadTemplate("prompts/$version/user.txt")
    }

    /**
     * Renders user prompt template with variables.
     */
    fun renderUserPrompt(
        marketData: String,
        accountInfo: String,
        minutesSinceStart: Long,
        invocationCount: Long,
        maxLeverage: Int,
        minLeverage: Int,
        minConfidence: String,
        availableCashUsd: String
    ): String {
        val template = loadUserPrompt()
        val currentTimeUtc = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))

        return template
            .replace("{{minutesSinceStart}}", minutesSinceStart.toString())
            .replace("{{currentTimeUtc}}", currentTimeUtc)
            .replace("{{invocationCount}}", invocationCount.toString())
            .replace("{{marketData}}", marketData)
            .replace("{{accountInfo}}", accountInfo)
            .replace("{{maxLeverage}}", maxLeverage.toString())
            .replace("{{minLeverage}}", minLeverage.toString())
            .replace("{{minConfidence}}", minConfidence)
            .replace("{{availableCashUsd}}", availableCashUsd)
    }

    private fun loadTemplate(path: String): String {
        return try {
            val resource: Resource = resourceLoader.getResource("classpath:$path")
            if (!resource.exists()) {
                log.warn { "Template not found: $path, using fallback" }
                return getFallbackTemplate(path)
            }

            resource.inputStream.use { input: InputStream ->
                input.bufferedReader(StandardCharsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load template: $path" }
            getFallbackTemplate(path)
        }
    }

    private fun getFallbackTemplate(path: String): String {
        return when {
            path.contains("system") -> """
                You are an expert cryptocurrency trading analyst. 
                Analyze the provided market data and generate clear, actionable trading signals in JSON format.
            """.trimIndent()
            path.contains("user") -> """
                Analyze the market data and generate trading signals in JSON format.
            """.trimIndent()
            else -> ""
        }
    }
}

