# Carrom Auto Player

Standalone carrom-board auto-player engine, extracted from
[Auto-Agent5](https://github.com/faridahmed6682-bussiness/Auto-Agent5)
at commit `ea02d43`
("Fix carrom A07 baseline test: measure real baseline line instead of default 0.845f").

This repo is **PRIVATE** — it is Farid's own carrom engine, split out of the
TapPilot agent app so it can live, build and release on its own.

## What it does

- Sees the board from a screenshot: finds pockets, coins, the queen and the
  striker — even with custom striker skins (monster face, patterned, brown).
  Striker position comes from the bottom slider knob + the green turn-glow
  halo, not from the striker's colour, so any skin works.
- Solves the shot: ghost-ball aim, cut angles, blocked-path checks, bank
  (rail-bounce) and combo shots, 2D physics rollout, foul/scratch guards.
- Plays inside 5 seconds: probe cut-off 3.4 s, fire deadline 4.5 s,
  early-fire the moment a sure shot is found, safe shot when there is none.
- Learns: self-play trainer (direct / bank / combo grids), per-board
  calibration profiles, shot-memory tables, miss-cause learning,
  queen-cover urgency, striker-sweep pocket check.
- Board Calibration & Aim Lab UI: teach a new board from a screenshot
  (pockets, coin colours, test aim).

## What is inside

| Path | What it is |
| --- | --- |
| `app/src/main/java/com/example/game/Carrom*.kt` (9 files) | The whole engine: vision, geometry solver, physics, shot memory, turn memory (5 s clock), self-play trainer, watch trainer, board calibration, circle refiner |
| `app/src/main/java/com/example/agent/CarromLiveLoop.kt` | **The live auto-play loop** (1,405 lines, extracted from TapPilot's `AgentCoordinator`): turn detect → solve → probe within the 5 s budget → fire via a drag callback → evaluate the previous shot. Host feeds screenshots to `playTurn(screenshot, nowMs)` and supplies the drag callback |
| `app/src/main/java/com/example/service/CarromDragExecutor.kt` | Slingshot drag via accessibility `dispatchGesture` (extracted from TapPilot's `GestureExecutor`); signature matches the loop's drag callback exactly — host binds it to its own AccessibilityService |
| `app/src/main/java/com/example/agent/CarromSettingsStore.kt` | Carrom settings only (DataStore): the 5 carrom keys + 3 solver switches, key names identical to TapPilot so saved settings keep working |
| `app/src/main/java/com/example/agent/AgentSettings.kt` | **Slimmed stub** — only the settings fields carrom code reads (same names, same defaults as the original) |
| `app/src/main/java/com/example/agent/ScreenNode.kt` | `ScreenBounds` + `ScreenNode` copied verbatim (used to tell a match HUD from a lobby menu) |
| `app/src/main/java/com/example/ai/AiVisionStrategy.kt` | **Extracted stub** — `AiTaskProfile` + `GeminiAiPlanner.resolveVisionStrategy` verbatim (forces a screenshot during a match even with many HUD buttons) |
| `app/src/main/java/com/example/ui/CarromHud.kt` | Carrom telemetry pill + Watch & Learn trainer card as `@Composable`s with explicit parameters, plus `CarromHudState` |
| `app/src/main/java/com/example/ui/CarromCalibrationDialog.kt` | Board Calibration & Aim Lab UI |
| `app/src/main/java/com/example/ui/theme/` | App theme (colours used by the dialog) |
| `app/src/main/java/com/example/agent/CarromAgentTypes.kt` | Small carrom types extracted verbatim from Auto-Agent5 `AgentModels.kt`: `CarromDifficulty`, `CarromRuleMode`, `TaskPlaybook`, `CarromContextHelper` (including `isLikelyGameLobbyMenu`) |
| `app/src/main/java/com/example/agent/AgentKnowledgeStore.kt` | **Slimmed stub** — only the 3 companion functions the engine uses (`serializePlaybooksJson`, `parsePlaybooksJson`, `findBestMatchingPlaybook`). The full knowledge store stays in TapPilot |
| `app/src/main/res/values/carrom_strings.xml` | 17 strings: carrom-named + dialog strings |
| `app/src/test/java/com/example/CarromRobolectricTest.kt` | 13 carrom Robolectric tests (vision, memory, calibration) |
| `app/src/test/java/com/example/CarromLogicUnitTest.kt` | **19 carrom unit tests** (solver, trainer, fouls, queen, lobby-menu, freestyle, playbook, forceVision, post-training auto-play) — all 5 previously dropped tests are now included |
| `.github/workflows/android-auto-release.yml` | Build + test + auto-release (APK on every push to main, release tag `v1.0.<run_number>`) |
| `gradle/`, `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts` | Minimal Gradle setup for this repo only (includes DataStore for the settings store) |

## How the pieces fit (host app's job)

The engine + live loop are now all in this repo. The host app only has to
provide the two Android-side things a library cannot do itself:

1. **Screenshot feed** — capture the screen (screenshot / MediaProjection)
   and call `CarromLiveLoop.playTurn(screenshot, nowMs)` once per cycle.
   The loop detects the turn, solves, probes within the 5 s budget and
   fires; it returns `CarromTurnResult` (`SliderMoved` / `ShotFired` /
   `Waiting` / `NotCarromTurn` — on `NotCarromTurn` the host decides what
   to do, e.g. fall back to its own AI).
2. **Drag execution** — bind `CarromDragExecutor` to the host's own
   AccessibilityService and pass its `performSlingshotDrag` as the loop's
   drag callback. The loop decides start/end/duration; the executor only
   performs the gesture.
3. **Settings + HUD** — `CarromSettingsStore` (DataStore) holds the carrom
   settings; `CarromHud` (`CarromTelemetryPill`, `CarromWatchTrainCard`)
   shows memory / turn state — wire them like TapPilot did
   (see the header comment in `CarromHud.kt`).

Entry points in the engine: `CarromLiveLoop.playTurn`,
`CarromVisionDetector.analyzeAndSolve`, `observeBoardForTraining`,
`autoDetectCalibrationFromBitmap`, `CarromGeometrySolver.solveBestShot` /
`solveSafetyShot` / `sweepStrikerAim`,
`CarromShotMemoryStore.exportTrainingBundleJson` /
`parseTrainingBundleJson`, `CarromSelfPlayTrainer`.

## What is NOT in this repo (on purpose)

- **TapPilot agent app** — the AI planner (`GeminiAiPlanner` apart from
  the one extracted vision function), screen capture service,
  launcher/notification/messaging, WhatsApp/Messenger bots, and the
  `runWatchTraining` (Watch & Learn) mode. The carrom loop does not depend
  on any of it; `NotCarromTurn` is where the host's own AI would take over.
- **The "real screenshot" Robolectric test skips on CI** — it looks for
  `public/assets/aistudio/Screenshot_20261119_013520_Carrom_Disc_Pool_66343f37.jpg`,
  which was never in the repo. Add that file to run it for real.
- **Trainer save fix is NOT included** — it was written after `ea02d43`
  (atomic write + `.bak`, "Train now" button, trained-count display,
  merge-on-import) but never pushed to Auto-Agent5; its patch is kept
  separately. Apply it here once it lands.

## Build & release — honest status

The GitHub Actions workflow is included and adapted
(`.github/workflows/android-auto-release.yml`):
push to `main` → unit + Robolectric tests (32 carrom tests: 19 unit + 13
Robolectric) → debug APK → GitHub Release.
**The repo can now build a debug APK.** The two missing shell files were
added: `app/src/main/AndroidManifest.xml` (package `com.example`, one
launcher Activity, no permissions) and the launcher Activity
`app/src/main/java/com/example/game/CarromMainActivity.kt` (home screen +
Board Calibration & Aim Lab dialog). The live auto-play loop, slingshot
drag, carrom settings and HUD are all in the repo now — the only things
the host still supplies are the screenshot feed and its own
AccessibilityService binding (see "How the pieces fit" above). The shell
still hosts calibration only; wiring the loop into `CarromMainActivity`
is the one remaining step to play from this app.

For a **signed release APK**, add these GitHub secrets (never commit them):
`KEYSTORE_BASE64` (base64 of your upload keystore), `STORE_PASSWORD`,
`KEY_PASSWORD`. Without them the workflow still releases the debug APK.
`GOOGLE_SERVICES_JSON` is **not** needed — the carrom engine uses no
Firebase. No API keys, tokens or `.env` files are in this repo.
