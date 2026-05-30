package ru.driics.aitrade.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.orchestrator.UpdateCycleResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P1: the cycle runs off the scheduler thread, and overlapping ticks must be skipped (single-flight)
 * rather than running concurrently.
 */
class PromptSchedulerServiceTest {

    @Test
    fun `overlapping tick is skipped while a cycle is in flight`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val orchestrator = mockk<UpdateCycleOrchestrator>()
        coEvery { orchestrator.runOnce() } coAnswers {
            started.countDown()
            release.await(5, TimeUnit.SECONDS) // hold the first cycle "in flight"
            UpdateCycleResult(success = true, message = "ok", executionTimeMs = 0, promptSize = 0)
        }

        val service = PromptSchedulerService(orchestrator)
        try {
            service.scheduledUpdate() // launches; inFlight set synchronously before the launch
            assertTrue(started.await(5, TimeUnit.SECONDS), "first cycle should start")

            service.scheduledUpdate() // must be skipped — first cycle is still in flight

            release.countDown()
            Thread.sleep(300) // let the first cycle finish; no second invocation should occur

            coVerify(exactly = 1) { orchestrator.runOnce() }
        } finally {
            release.countDown()
            service.shutdown()
        }
    }

    @Test
    fun `a later tick runs once the previous cycle has completed`() {
        val orchestrator = mockk<UpdateCycleOrchestrator>()
        coEvery { orchestrator.runOnce() } returns
            UpdateCycleResult(success = true, message = "ok", executionTimeMs = 0, promptSize = 0)

        val service = PromptSchedulerService(orchestrator)
        try {
            service.scheduledUpdate()
            Thread.sleep(200)
            service.scheduledUpdate()
            Thread.sleep(200)
            coVerify(exactly = 2) { orchestrator.runOnce() }
        } finally {
            service.shutdown()
        }
    }
}
