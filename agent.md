# Potato Player — Agent Rules

## Core Principle

Native first. Use Android platform APIs and mpv/libmpv capabilities before writing custom Kotlin logic. Add custom code only when native behavior is unavailable, insufficient, or demonstrably wrong for the requirement.

Follow the actual repository structure. Do not assume modules, classes, or architecture components exist unless confirmed by reading the source.

---

## Architecture Layers

### Models

- Simple Kotlin types: `data class`, `enum`, `sealed` for real domain concepts.
- No I/O, no Activity/View references, no JNI calls.
- Do not create model wrappers for every engine property without a concrete need.

### ViewModel

- Expose UI state via `StateFlow`. The authoritative source for each playback property must be explicit. Engine-derived track and pause state belongs to `EngineEventHandler`; the ViewModel combines the relevant state for the UI. Do not create competing sources of truth.
- Use `viewModelScope.launch` for async work.
- Never put Activity, Window, orientation APIs, Compose layout, or raw JNI in the ViewModel.
- `togglePlayPause()` and similar controls send the command only — state updates come from the MPV observer callback, not optimistic guesses.
- Does not register directly with `MPVLib`. Consumes state from `EngineEventHandler` only.

### UI / Composables

- Render state, send user actions. Never issue mpv commands from composition body.
- Collect high-frequency state (position, duration) only in the composable that needs it.
- Do not duplicate state across screen, ViewModel, and engine.
- Keep local state only for transient UI interaction (e.g. controls visibility toggle).
- `isPlaying` is `Boolean?`: `null` = engine state not yet known, `true` = playing, `false` = paused. Handle all three explicitly — do not default `null` to `false` or `true` silently.

### Engine / MPV Bridge — LOCKED BOUNDARY

This boundary is frozen. Do not restructure it.

```
MPVLib  →  MpvLifecycleGuard  →  MpvEngine + EngineEventHandler  →  PlayerViewModel
```

**`MPVLib`** — Native/JNI boundary. Do not modify.

- Sole path for all JNI and native calls.
- Dispatches raw property events to registered observers.

-

**`MpvEngine`** — Native lifecycle and commands.

- Owns: initialization, cleanup, observer registration, surface lifecycle, file loading, playback commands, geometry, and progress.
- Calls `eventHandler.reset()` at start of `playFile()` (file switch) and in `destroy()` (full teardown) — but only when native destroy actually succeeded; see `DestroyFailed` below.
- Routes all native create/destroy calls through `MpvLifecycleGuard`, never `MPVLib` directly.

**`EngineEventHandler`** — Event-to-state translation.

- Converts native property events into typed `StateFlow`s.
- Owns: audio/subtitle track state, pause state, media title, selected track IDs.
- Resets all state on `reset()`.
- `syncPauseState(isPaused: Boolean)` is the one sanctioned non-event write into pause state. Called only from `MpvEngine.loadFile()`, once, right after the `loadfile` command, to sync mpv's real current pause value — because `observeProperty` won't re-fire if the value hasn't changed since the last file. It reads mpv's actual state via `MPVLib.getPropertyBoolean`, not a guess; it is not polling and not a second owner. Do not add further callers or widen it into a general-purpose setter.
- `reset()` clears event-derived application state only. It must not initialize, destroy, or otherwise manage the native MPV instance.

**`EngineStatus.DestroyFailed` — native destruction failure handling:**

- If `MPVLib.destroy()` throws (during `initialize()`'s failure cleanup, or during `destroy()`), the engine's status becomes `DestroyFailed(reason, retried: Boolean)` instead of silently reporting `NotStarted` or `Failed`. The native instance's ownership state is uncertain at that point, and the engine must never claim otherwise.
- While `DestroyFailed`, `initialize()` refuses to run (logs and returns) and `destroy()` refuses to re-attempt native teardown (it still clears Kotlin-side state — callbacks, pending surface/file — so it stays safe to call repeatedly, but it does not touch the Guard again).
- `retryDestroy()` is the **only** recovery path out of `DestroyFailed`. It is capped at one retry (`retried: Boolean`, not a counter) — if the retry also fails, the engine stays `DestroyFailed` permanently until process restart. Do not add automatic or repeated retries; a native library that may still be partially alive must not be assumed safe to destroy repeatedly.
- **Known gap**: nothing in `PlayerViewModel` currently calls `retryDestroy()`. `DestroyFailed` is effectively terminal in the live app today. This is a deliberate fail-closed default, not an oversight — don't silently wire up a caller without deciding, as a product question, whether/how a destroy failure should surface to the user.
- JVM unit tests (`MpvEngineTest.kt`) prove the Kotlin-side contract (status transitions, state clearing, no stale callbacks/surfaces/files after recovery). They cannot prove that calling native `MPVLib.destroy()` a second time is actually safe on a real device after a partial failure — that depends on mpv's native internals and is outside what this layer can observe or guard against.

**Rules that cannot be changed without explicit justification:**

1. `MPVLib` stays unchanged. It is the JNI/native API boundary.
2. Exactly **two** native observers: `MpvEngine` and `EngineEventHandler`. No other class registers directly with `MPVLib`.
3. `MpvEngine` owns all native lifecycle. No other class calls initialization, cleanup, or surface management. All native destroy calls route through `MpvLifecycleGuard`, never `MPVLib.destroy()` directly.
4. `EngineEventHandler` owns all event-to-state translation. No parallel state owners for the same property.
5. One authoritative state per concern. `isEngineReady` derives from `engineStatus`; `isPlaying` is nullable `Boolean?` — `null` is a real state meaning "not yet known," not a bug.
6. Loading a new file resets stale track/playback state via `eventHandler.reset()` in `playFile()`.
7. No premature abstractions. Do not split out audio/subtitle controllers or add a new layer without a concrete failing requirement.
8.
9. `DestroyFailed` recovery goes through `retryDestroy()` only, capped at one attempt. Do not add automatic retries or a retry counter.

### Activity

- `PlayerActivity` owns lifecycle integration, orientation, and window/system UI settings.
- ViewModel exposes orientation preferences or video metadata — it never calls `requestedOrientation` or window APIs directly.

---

## MPV-Specific Rules

- Prefer mpv properties, commands, and events over reimplementing playback behavior in Kotlin.
- `observeProperty("pause", MPV_FORMAT_FLAG)` must be registered so play/pause state is always driven by real MPV state, never guessed.
- Do not replace mpv callbacks with polling, timers, or duplicate state owners.

---

## Coroutines and Threading

- No `runBlocking` on the main thread or in constructors.
- Blocking I/O off the main thread.
- Use `viewModelScope` for production ViewModel work. An explicitly injected scope is permitted for deterministic tests. Do not use `GlobalScope` or unmanaged scopes.
- Prefer callbacks, Flow, or mpv property observation over busy polling or arbitrary timers.

---

## Compose Performance

- High-frequency values (position tick) collected only where needed — not at screen level.
- No expensive work, file I/O, or mpv commands during composition.
- Do not recreate the native video surface on ordinary UI state updates.

---

## Code Quality

- Smallest coherent change that fixes the real problem.
- Inspect source, callers, state ownership, and lifecycle paths before changing behavior.
- Do not add modules, interfaces, use cases, or frameworks without a concrete benefit.
- No duplicate state, duplicate observers, or parallel command paths.
- Report changed files and verification results after any change.

---

## Error Handling

- Handle failures at the layer that can respond meaningfully.
- Do not silently ignore engine, file, or playback failures.
- Do not claim a build or behavior passed unless actually verified.
