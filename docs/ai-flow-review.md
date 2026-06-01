# AI Flow — Code Review & Score

Scope: the live AI decision pipeline (prompt → LLM → parse → guard → execute) and the new
backtest-AI layer (record → replay). Reviewed by reading every component end-to-end.

## The pipeline

```
UpdateCycleOrchestrator (per cycle)
  stageBuildPrompt      BuildPromptUseCase → PromptTemplateService / PromptBuilder      (MarketState → prompt)
  stageAiAnalysis       AnalyzePromptUseCase  (AiBudgetLimiter → retry+withTimeout)
                          → AiAnalysisPort → KoogAiAdapter → KoogAiService
                          → RotatingOpenRouterClient (key rotation + retries) → LLM      (prompt → raw JSON)
  stageParseResponse    AiSchemaValidator.validateAndParse  (JSON + Bean Validation + rules)  (→ AiTradeDecisionMap)
  stageGuardAndNormalize SignalNormalizer.normalize → ConfidenceCalibrator.shouldAccept (minConf + cooldown)
  stageExecute (autoExecute) fail-closed PnL gate + kill-switch → ExecuteAiDecisionsUseCase
                          → ActionGuard (TP/SL direction, tick distance, leverage, lot) → OrderSizingPolicy → order
```

Backtest-AI layer (mirrors the deterministic-able part of the above):
`AiDecisionRecorder` (buildPrompt → analyze → validateAndParse, over historical bars) → `AiDecisionLog`
(JSONL) → `RecordedAiStrategy` (deterministic replay) → engine.

## Score

**Overall: 8.0 / 10** — production-grade, conservative, heavily instrumented; no stability bugs found.
Deductions are maintainability, determinism, and testability, not runtime safety.

| Dimension | Score | Note |
|---|---|---|
| Architecture / layering | 9 | Clean ports/adapters (`AiAnalysisPort` ← `KoogAiAdapter` ← `KoogAiService`); pure domain services |
| Resilience / stability | 8.5 | Budget + timeout + key rotation + retries + circuit-breaker patterns; correct `CancellationException` handling |
| Trading safety / guardrails | 9 | Fail-closed PnL gate, kill-switch, idempotency, multi-stage validation, atomic TP/SL attach |
| Observability | 9 | Micrometer + OpenTelemetry spans + BusinessEventLogger throughout |
| Determinism / reproducibility | 5 | LLM temperature not pinned; no persistent decision history (only `lastAnalysis`) |
| Maintainability / DRY | 6 | Same rule (confidence, leverage, TP/SL) validated in 3–4 places; spread-out authority |
| Testability | 6.5 | `KoogAiService` hard-wires its client; `BuildPromptUseCase` couples market-state load |

## Strengths

- **Defense-in-depth for money.** Five independent gates (schema → normalize → calibrate → guard → size)
  before an order. Exactly right for a system that spends real funds.
- **Fail-closed risk gating.** `stageExecute` refuses to trade when today's realized PnL is unreadable
  (when risk gating is on) and does not advance the dedup hash, so the next cycle retries. Conservative.
- **Resilience layering.** Budget token-bucket (thread-safe CAS), `withTimeout` with genuine-cancellation
  rethrow, API-key rotation with its own retries, word-boundary HTTP-code retry classification.
- **Hexagonal boundaries.** The port/adapter split is what made the backtest recorder possible at all
  (it reuses `AiAnalysisPort` + `AiSchemaValidator` + `PromptBuilder` without touching the LLM client).

## Findings & recommendations (prioritised)

### Stability / correctness
1. **Pin LLM temperature (→ 0 or low) and a seed if the provider supports it.** `KoogAiService` declares
   `LLMCapability.Temperature` but never sets a value → provider default → run-to-run variance in a
   *trading* decision. Pinning improves consistency live AND makes a single backtest recording more
   representative of the policy. *(stability + DX, low effort)*
2. **Recorder prompt-template gap (new code).** `AiDecisionRecorder`/`AiRecorderHarnessTest` build the
   prompt with the pure `PromptBuilder` (data sections only). If the live JSON-schema instructions live in
   the **template** (`PromptTemplateService`) rather than the system prompt, recorded responses may not be
   schema-valid → all skipped. Verify on the first real run; if so, wire `PromptTemplateService` into the
   recorder (mirror `BuildPromptUseCase.buildTemplatePrompt`). *(correctness of the AI backtest path)*

### Performance / cost
3. **Nested retries can multiply.** `AnalyzePromptUseCase` retries `analyze()` up to `aiMaxRetries+1`, and
   each call's `RotatingOpenRouterClient` retries internally — worst case (persistent outage) is the
   product of the two. Consolidate retry responsibility into one layer (prefer the rotation client, which
   knows about keys) and let the use case do at most a thin outer attempt. *(latency + $ on failures)*

### DX / maintainability
4. **Consolidate duplicated validation.** Confidence is checked in `AiSchemaValidator`,
   `ConfidenceCalibrator`, and `ExecuteAiDecisionsUseCase`; leverage and TP/SL in `AiSchemaValidator` and
   `ActionGuard`. Define ONE authoritative gate per rule and document the intended defense-in-depth layers,
   so "where do I change the min-confidence rule?" has one answer.
5. **`ConfidenceCalibrator` is a misnomer** — it *filters* (threshold + cooldown), it does not calibrate.
   Rename to `SignalGate`/`SignalFilter`, or make it actually calibrate. (Matches the known fact that it
   does not learn.)
6. **Persist a live AI decision log.** Live keeps only a single `lastAnalysis` `AtomicReference`. Reusing
   the new `AiDecisionLog` to append every live decision would make the live flow auditable/replayable and
   is a prerequisite for any future learning loop.
7. **Make `KoogAiService` unit-testable.** Inject `RotatingOpenRouterClient` (or a small seam) so the
   success/failure → `AiAnalysisResponse` mapping can be tested without the real provider.

## Stability verdict

Production-grade and deliberately conservative. The real residual risks are *external* (LLM
non-determinism, latency, cost, provider outages) and are well mitigated. No internal stability bug was
found. The single most valuable change is #1 (pin temperature) for both live consistency and backtest
fidelity; #2 is the one correctness item to verify before trusting an AI backtest.
