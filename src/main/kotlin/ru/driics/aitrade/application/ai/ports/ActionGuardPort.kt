package ru.driics.aitrade.application.ai.ports

import ru.driics.aitrade.application.ai.ActionGuard
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import java.math.BigDecimal

/**
 * Port for action guard validation.
 * Allows easy testing and swapping implementations.
 */
interface ActionGuardPort {
    fun validate(
        plan: AiTradeSignalArgs,
        instrumentInfo: OkxInstrumentInfo,
        lastPrice: BigDecimal,
        instrumentMaxLeverage: Int? = null
    ): ActionGuard.ValidationResult
}

/**
 * Default implementation of ActionGuardPort.
 */
class ActionGuardAdapter(
    private val actionGuard: ActionGuard
) : ActionGuardPort {
    override fun validate(
        plan: AiTradeSignalArgs,
        instrumentInfo: OkxInstrumentInfo,
        lastPrice: BigDecimal,
        instrumentMaxLeverage: Int?
    ): ActionGuard.ValidationResult {
        return actionGuard.validate(plan, instrumentInfo, lastPrice, instrumentMaxLeverage)
    }
}

