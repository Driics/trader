package ru.driics.aitrade.controller

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.driics.aitrade.service.PromptSchedulerService

@RestController
@RequestMapping("/api/prompt")
class PromptController(
    private val promptSchedulerService: PromptSchedulerService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "OK", "message" to "AI Trader is running"))
    }

    @PostMapping("/update")
    fun updatePrompt(): ResponseEntity<Map<String, String>> {
        log.info("Received manual prompt update request")
        return try {
            val result = promptSchedulerService.triggerPromptUpdate()
            ResponseEntity.ok(mapOf("status" to "success", "message" to result))
        } catch (e: Exception) {
            log.error("Error updating prompt", e)
            ResponseEntity.internalServerError()
                .body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf(
            "status" to "running",
            "service" to "AI Trader Prompt Builder",
            "message" to "Service is active and scheduled updates are running"
        ))
    }
}