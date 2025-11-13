package ru.driics.aitrade.domain.model

interface AiService {
    suspend fun analyzePrompt(prompt: String): AiAnalysisResponse
    fun getProviderName(): String
    fun getModel(): String
    fun getLastAnalysis(): LastAiAnalysis?
}

data class AiAnalysisResponse(
    val provider: String,
    val model: String,
    val response: String,
    val executionTimeMs: Long,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

data class LastAiAnalysis(
    val provider: String,
    val model: String,
    val timestamp: Long,
    val executionTimeMs: Long,
    val success: Boolean,
    val response: String?,
    val error: String?
)