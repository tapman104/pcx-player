# Potato Player — Claude Context

## What This App Is

Android local video player. Kotlin + Jetpack Compose (Material 3) + mpv/libmpv via JNI.
No ExoPlayer. No XML layouts. No Hilt. No navigation framework.

---

## Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Video engine | mpv/libmpv via JNI (`MPVLib.kt`) |
| State | `StateFlow` in `PlayerViewModel` + `EngineEventHandler` |
| Async | Kotlin coroutines (`viewModelScope`) |
| DI | None / manual |
| Persistence | Not yet implemented |

---

## Key Files

| File | Role |
| --- | --- |
| `PlayerActivity.kt` | Entry point, lifecycle, sets Compose content |
| `PlayScreen.kt` | Root composable — `Box` stacking video + bars |
| `PlayerViewModel.kt` | Coordinates engine commands; consumes state from `EngineEventHandler`; does not register with `MPVLib` directly |
| `MpvEngine.kt` | MPV init, surface attach, observer registration, file loading, playback commands |
| `
| `EngineEventHandler.kt` | Translates native property events into typed `StateFlow`s; owns track state, pause state, media title |
| `MPVLib.kt` | JNI bridge — all native calls go here; do not modify |
| `PlayerTopBar.kt` | Top overlay bar composable |
| `PlayerBottomBar.kt` | Bottom overlay bar composable (play/pause + seek) |
| `VideoSurface.kt` | Embeds `MpvSurfaceView` into Compose via `AndroidView` |
| `MpvSurfaceView.kt` | `SurfaceView` subclass — the only non-Compose View |
| `BaseMPVView.kt` | Base for surface lifecycle |

---

## State Flow (how MPV events reach UI)

```
Native libmpv
  → JNI callback
  → MPVLib.eventProperty(property, value)
  → MPVLib dispatches to observers (exactly two: MpvEngine, EngineEventHandler)
  → EngineEventHandler updates typed StateFlows
  → PlayerViewModel combines/collects EngineEventHandler state
  → _uiState.update { ... }
  → PlayScreen collects state
  → Composables recompose
```

Native lifecycle (init/destroy) is a separate path from event dispatch above:

```
MpvEngine.initialize() / destroy() / retryDestroy()
  → MpvLifecycleGuard.create() / destroy()   (sole owner of native ownership state)
  → MPVLib.create() / MPVLib.destroy()
```

`MpvEngine` never calls `MPVLib.create()`/`MPVLib.destroy()` directly — always through `MpvLifecycleGuard`.

---

## Locked Architecture — MPV Engine Boundary

This boundary is frozen. Do not restructure it without explicit justification.

```
MPVLib  →  MpvLifecycleGuard  →  MpvEngine + EngineEventHandler  →  PlayerViewModel
```

| Component | Owns |
| --- | --- |
| `MPVLib` | JNI/native API boundary; unchanged |
| `
| `MpvEngine` | Native lifecycle, init/teardown, observer registration, surface, file loading, playback commands |
| `EngineEventHandler` | Event-to-state translation; track state, pause state, media title, selected IDs; resets on file switch |
| `PlayerViewModel` | Engine command dispatch; consumes `EngineEventHandler` state; no direct `MPVLib` registration |

**Hard rules:**

- Exactly two native observers: `MpvEngine` and `EngineEventHandler`. No others.
- `MPVLib` is not modified.
- No duplicate state owners for the same property.
- `eventHandler.reset()` is called in both `playFile()` (file switch) and `destroy()` (teardown) — but not when a destroy attempt fails; see `DestroyFailed` below.
- No class other than `MpvLifecycleGuard` calls `MPVLib.destroy()`. No second ownership flag duplicates the Guard's tracking.

---

## Known Architecture Decisions

- **Pause state**: driven by `observeProperty("pause", MPV_FORMAT_FLAG)` callback for all in-session changes. `togglePlayPause()` sends the command only — no optimistic state update. One narrow, deliberate exception: `MpvEngine.loadFile()` does a single synchronous `MPVLib.getPropertyBoolean("pause")` read immediately after the `loadfile` command and pushes it into `EngineEventHandler` via `syncPauseState()`. This exists because `observeProperty` fires the current value once on registration, then only again on *change* — if pause state doesn't change across a file load, `isPaused` would otherwise stay `null` indefinitely, not just briefly, permanently disabling the play/pause button. It still reads mpv's real current state, not a guess — one-shot, not polling. Do not generalize `syncPauseState()` into other call sites without the same justification.
- **`isPlaying` is `Boolean?`**: `null` = engine state not yet known (before first MPV pause event), `true` = playing, `false` = paused. The UI renders a loading spinner and disables the play/pause button when `null`. Do not collapse `null` to `false`.
- **Track state reset**: `EngineEventHandler.reset()` is called at the start of `MpvEngine.playFile()` to clear stale track list, selected IDs, and media title before a new file loads.
- **Controls visibility**: transient UI state, lives in `PlayScreen` (not ViewModel). Single tap toggles. Auto-hides after 5 seconds via coroutine in `LaunchedEffect`.
- **No polling**: all property updates via `observeProperty`. No timers or repeated reads for playback state.
- **Native destroy failure (`EngineStatus.DestroyFailed`)**: if `MPVLib.destroy()` throws, the engine does not assume the native instance is gone. Status becomes `DestroyFailed(reason, retried)` instead of `NotStarted`/`Failed`. `MpvLifecycleGuard` keeps ownership. The only way out is `retryDestroy()`, capped at exactly one attempt — if that also fails, the engine stays `DestroyFailed` until process restart. No automatic or repeated retries.

---

## What Does NOT Exist (do not assume)

- No Hilt or any DI framework
- No navigation graph or NavController
- No XML layouts
- No repository or data layer
- No use case classes
- No ExoPlayer or MediaSession
- No audio/subtitle controller classes (do not create without a concrete failing requirement)
- **No caller of `MpvEngine.retryDestroy()` in `PlayerViewModel` yet.** `DestroyFailed` is reachable but effectively terminal in the live app today — this is a deliberate fail-closed default, not a bug. Don't wire up a caller without first deciding, as a product decision, how a destroy failure should surface to the user.

---

## Audit Before Every Change

Before touching any file:

1. Read the file
2. Identify all callers/observers of what you're changing
3. Make the smallest change that solves the problem
4. Report what changed and confirm the build passes
