package ru.driics.aitrade.application.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.driics.aitrade.model.AiTradeExecutionResult
import ru.driics.aitrade.service.AiTradeExecutionService

class ExecuteAiDecisionsUseCase(
    private val legacy: AiTradeExecutionService
) {
    suspend fun execute(aiJson: String): List<AiTradeExecutionResult> = withContext(Dispatchers.IO) {
        legacy.execute(aiJson)
    }
}