package ru.driics.aitrade.application.ai.ports

import ru.driics.aitrade.application.ai.SignalNormalizer
import ru.driics.aitrade.domain.model.AiTradeSignalArgs

/**
 * Port for signal normalization and sanitization.
 */
interface SignalNormalizationPort {
    fun normalize(signal: AiTradeSignalArgs): SignalNormalizer.NormalizedSignal?
}

