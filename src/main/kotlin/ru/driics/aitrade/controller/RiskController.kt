package ru.driics.aitrade.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.driics.aitrade.application.risk.KillSwitchState
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot

@RestController
@RequestMapping("/api/trading/kill-switch")
class RiskController(
    private val killSwitchState: KillSwitchState,
) {
    data class KillSwitchRequest(val enabled: Boolean, val reason: String)

    @GetMapping
    fun get(): ResponseEntity<KillSwitchSnapshot> =
        ResponseEntity.ok(killSwitchState.snapshot())

    @PostMapping
    fun set(@RequestBody body: KillSwitchRequest): ResponseEntity<KillSwitchSnapshot> {
        val next = if (body.enabled) {
            killSwitchState.trip(body.reason, KillSwitchSnapshot.Source.MANUAL)
        } else {
            killSwitchState.clear()
        }
        return ResponseEntity.ok(next)
    }
}
