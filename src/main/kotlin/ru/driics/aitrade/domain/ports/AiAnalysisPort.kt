package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.AiAnalysisResponse
import ru.driics.aitrade.domain.model.LastAiAnalysis

interface AiAnalysisPort {

    val last: LastAiAnalysis?
    suspend fun analyze(prompt: String): AiAnalysisResponse
}