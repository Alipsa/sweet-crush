# Sweet Crush Project Plan

## Goal
Build a Candy Crush-style game in Groovy using Swing, with track packs loaded from a user-selected directory containing JSON track files.

## Assumptions
- A "track" is a playable level definition.
- JSON parsing uses Groovy's built-in `JsonSlurper` — `groovy-json` is available transitively via `groovy-all`. Verify this during Task 1b to catch silent transitive-dependency gaps.
- Base package is `se.alipsa.games.sc`; all source lives under `src/main/groovy/se/alipsa/games/sc/` and tests under `src/test/groovy/se/alipsa/games/sc/`. Maven coordinates: groupId `se.alipsa.games`, artifactId `sweet-crush`.
- Java baseline is `25`. Java 25 became the current LTS in September 2025, so targeting Java 25 is the correct and settled JVM choice for this project unless explicitly changed by the user.
- MVP candy types are plain enum values only (no striped/wrapped/bomb specials). `spawnWeights` in the track JSON are keyed by enum name (e.g. `"RED": 3`). If `spawnWeights` is omitted from the track JSON, all `CandyType` values receive uniform weight (equal probability). If `spawnWeights` is present but omits some `CandyType` keys, omitted keys are treated as weight `0` (not auto-filled with uniform values). Post-MVP special pieces are planned in **M7** (sweeper, bomb, fish) with per-track configurable counts via `specialPieces`.
- No animation in MVP: game logic runs on a single-thread `ExecutorService` game worker, while Swing UI updates are posted back to the EDT via `SwingUtilities.invokeLater` after each resolved cascade step. Input is disabled while the worker is resolving. The game worker lifecycle is explicit: create once for the active session and shut it down on app exit/recreate it when starting a fresh session if needed.
- `Board.clone()` exists to support preview-swap validation in `GameEngine` (clone → apply swap → check matches → discard if no match found).
- `GameEngine` delegates match detection to `MatchFinder`; a swap is legal only if the two cells are orthogonally adjacent and `MatchFinder` finds a match on the resulting board.
- Board dimensions are constrained: minimum 3×3, maximum 20×20. Tracks exceeding these bounds are rejected with a validation error indicating the allowed range.
- Boards must always stay playable: initial fill must avoid pre-existing matches, and dead boards (no legal swaps) are reshuffled. Reshuffle uses 100 bounded attempts per seed, then retries with a new seed up to 5 times (500 total attempts). If all retries are exhausted, the engine emits a `reshuffleExhausted` event via `GameListener`; the UI displays a dialog: *"Unable to continue this track (exhausted all attempts); would you like to restart or continue to the next track?"* and acts on the player's choice.
- Randomness is injectable (seeded `Random` or equivalent) for initial fill, refill, and reshuffle so all board-generation/cascade tests are deterministic.
- `TrackLoader` returns a `LoadResult` value object with `List<Track> tracks` and `List<LoadError> errors`; each `LoadError` includes file path plus machine-readable code/message where code is a value from the `LoadErrorCode` enum.
- If duplicate track `id` values are found across files, keep the first track in deterministic load order (source filename lowercased with `Locale.ROOT`, then exact source filename, then `id`, then normalized absolute file path) and ignore later duplicates; log a warning for each ignored duplicate and include a corresponding `LoadError`.
- If the selected directory contains zero `.json` files, `TrackLoader` returns an empty `tracks` list with a `LoadError` carrying code `NO_TRACKS_FOUND`; the UI displays a message informing the player that no tracks could be loaded.
- Match scoring uses unique-cell groups per cascade step: detect all horizontal/vertical runs (3+), union overlapping runs into connected groups, clear each group once, then score `group_size × 10` per group.
- Win/lose precedence is explicit: after a valid swap and full cascade resolution, evaluate win first (`score >= targetScore`), otherwise lose if `movesLeft == 0`; no further swaps allowed after terminal state.
- Track progression: when the player wins a track, show a "Congratulations, you made it!" message, then advance to the next track in deterministic order by source filename lowercased with `Locale.ROOT`, then exact source filename, then track `id`, then normalized absolute file path. After the last track, return to the track selection screen. When the player loses (moves exhausted without reaching target score), show a dialog offering to **retry the same track** or **skip to the next track**.
- Engine-to-UI communication uses a listener/callback interface: `GameEngine` emits state-change events (board updated, score changed, game over, reshuffle exhausted) on the game worker thread only; the UI layer is solely responsible for marshalling render/update work to the EDT.
- Logging uses Log4j 2. Add `log4j-api` and `log4j-core` as dependencies in Task 1b; configure `log4j2.xml` in Task 1d with console and file appenders using the pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %c{1}.%M - %msg%n` (timestamp, level, class, method — no thread info). Use loggers in engine, resolver, and loader classes for debugging cascade steps, reshuffle attempts, and track loading.

## Track JSON Schema

Each track file is a single JSON object with the following fields:

| Field          | Type              | Required | Description                                                                                                |
|----------------|-------------------|----------|------------------------------------------------------------------------------------------------------------|
| `id`           | `String`          | yes      | Unique identifier across all loaded tracks                                                                 |
| `name`         | `String`          | yes      | Display name shown in the track selection UI                                                               |
| `width`        | `int`             | yes      | Board width in cells (min 3, max 20)                                                                       |
| `height`       | `int`             | yes      | Board height in cells (min 3, max 20)                                                                      |
| `moves`        | `int`             | yes      | Number of moves allowed                                                                                    |
| `targetScore`  | `int`             | yes      | Score the player must reach to win                                                                         |
| `spawnWeights` | `Map<String,int>` | no       | Candy spawn weights keyed by `CandyType` name. Defaults to uniform weights for all candy types if omitted; if present, omitted candy keys are treated as weight `0`. |
| `specialPieces`| `Map<String,int>` | no       | Per-track creation budget for special pieces (`SWEEPER`, `SMALL_BOMB`, `BOMB`, `FISH`). Values are non-negative integers. Omitted map or omitted keys default to `0` (disabled). |

**Example** (`sample-track-01.json`):
```json
{
  "id": "classic-01",
  "name": "Classic Level 1",
  "width": 7,
  "height": 9,
  "moves": 25,
  "targetScore": 3000,
  "specialPieces": {
    "SWEEPER": 3,
    "SMALL_BOMB": 2,
    "BOMB": 2,
    "FISH": 1
  },
  "spawnWeights": {
    "RED": 3,
    "BLUE": 3,
    "GREEN": 3,
    "YELLOW": 2,
    "PURPLE": 2,
    "ORANGE": 1
  }
}
```

## Task Board

### M1 — Build + Track Loading (with tests)
- [x] 1a. `pom.xml` — coordinates and versions: fix groupId (currently `se.alipsa.matrix` — change to `se.alipsa.games`); align Groovy version to `5.0.4` everywhere (runtime dep currently pins `5.0.0`); target Java `25`.
- [x] 1b. `pom.xml` — dependencies: verify `groovy-json` is available transitively; add `groovy-swing` dependency (needed for M3 UI); add Log4j 2 dependencies (`log4j-api`, `log4j-core`); add Spock Framework as test dependency.
- [x] 1c. `pom.xml` — plugins and lifecycle: add `maven-enforcer-plugin` to lock required Maven runtime version; switch build from `gmavenplus:execute` script mode to normal Groovy compile/test lifecycle (`compile`, `testCompile`); add `exec-maven-plugin` with `mainClass=se.alipsa.games.sc.SweetCrush` so the app can be launched via `mvn exec:java`; configure Surefire to run Spock tests.
- [x] 1d. `src/main/resources/log4j2.xml` — Log4j 2 configuration with two appenders: **Console** (stdout) and **RollingFile** (`sweet-crush.log`). Pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %c{1}.%M - %msg%n` (timestamp, level, short class name, method name — no thread info). Root logger level: `INFO`; set `se.alipsa.games.sc` logger to `DEBUG` during development.
- [x] 2. `README.md` (minimal): document build/run steps so contributors can get the project running during M1. Expand to full docs in M4.
- [x] 3. `src/main/groovy/se/alipsa/games/sc/SweetCrush.groovy`: bootstrap app startup and dependency initialization. In M1 scope: simple `main()` that validates the build compiles and runs (e.g. prints a startup banner). Full Swing window composition is deferred to M3.
- [x] 4. `src/main/groovy/se/alipsa/games/sc/model/Track.groovy`: define track schema object (`id`, `name`, `width`, `height`, `moves`, `targetScore`, `spawnWeights`). `spawnWeights` is optional and defaults to uniform weights for all `CandyType` values when absent; when present but partial, missing `CandyType` entries are normalized to `0`. See **Track JSON Schema** section above for the full field specification.
- [x] 5. `src/main/groovy/se/alipsa/games/sc/core/CandyType.groovy`: plain enum of base candy colors/types; names must match keys used in `spawnWeights`. Defined before `TrackValidator` so the validator can check weight keys against known enum values.
- [x] 5a. `src/main/groovy/se/alipsa/games/sc/io/LoadErrorCode.groovy`: enum defining stable, machine-readable error codes for track loading. Codes include: `MISSING_REQUIRED_FIELD`, `INVALID_TRACK_ID`, `INVALID_DIMENSIONS`, `INVALID_SPAWN_WEIGHTS`, `MALFORMED_JSON`, `DUPLICATE_TRACK_ID`, `UNKNOWN_CANDY_TYPE`, `NO_TRACKS_FOUND`.
- [x] 6. `src/main/groovy/se/alipsa/games/sc/io/LoadError.groovy`: structured per-file load error (`file`, `code`, `message`) where `code` is a `LoadErrorCode` enum value.
- [x] 7. `src/main/groovy/se/alipsa/games/sc/io/LoadResult.groovy`: loader result object (`List<Track> tracks`, `List<LoadError> errors`).
- [x] 8. `src/main/groovy/se/alipsa/games/sc/io/TrackValidator.groovy`: validate required fields, board feasibility (gameplay solvability), and `spawnWeights` integrity:
  - `id` format: must be non-null, non-blank (after trimming), and match the pattern `[a-zA-Z0-9][a-zA-Z0-9._-]*` (start with alphanumeric, then alphanumerics, dots, hyphens, or underscores; max 64 characters). Reject with `INVALID_TRACK_ID` error code.
  - `name` must be non-null and non-blank (after trimming). Reject with `MISSING_REQUIRED_FIELD`.
  - dimensions `width >= 3` and `height >= 3`; dimensions `width <= 20` and `height <= 20` (reject with `INVALID_DIMENSIONS` error and a message indicating the allowed range 3–20)
  - gameplay-solvability guardrails: `moves > 0` and `targetScore > 0`; validator enforces configuration-level solvability preconditions only, while runtime board solvability is enforced by `BoardResolver` initial-fill + dead-board reshuffle
  - when `spawnWeights` is present: keys must map to known `CandyType` enum names; values must be non-negative; omitted `CandyType` keys are treated as `0`; total weight > 0; at least 3 candy types with positive weight
  - when `spawnWeights` is absent: validation passes (uniform defaults applied by `Track`)
- [x] 9. `src/main/groovy/se/alipsa/games/sc/io/TrackLoader.groovy`: load all `*.json` from a user-selected directory using `JsonSlurper`; enforce duplicate ID checks across files (keep first in deterministic order, ignore later duplicates, log warning, emit `LoadError`); if the directory contains zero `.json` files, return an empty `tracks` list with a `LoadError` carrying code `NO_TRACKS_FOUND`; return `LoadResult`; order tracks deterministically by source filename lowercased with `Locale.ROOT`, then exact source filename, then track `id`, then normalized absolute file path.
- [x] 10. `src/test/groovy/se/alipsa/games/sc/io/TrackValidatorTest.groovy`: test all validation rules — invalid track IDs (null, blank, whitespace-only, starts with non-alphanumeric, contains illegal characters, exceeds 64 characters), blank track names, missing fields, bad dimensions (below minimum 3 and above maximum 20), feasibility checks (gameplay solvability), invalid weight maps (unknown keys, negative values, zero total, fewer than 3 positive-weight candy types), partial `spawnWeights` maps (omitted keys treated as zero), and omitted `spawnWeights` (should pass validation).
- [x] 11. `src/test/groovy/se/alipsa/games/sc/io/TrackLoaderTest.groovy`: test valid/invalid folders, empty directory (no `.json` files — expect `NO_TRACKS_FOUND` error), malformed JSON parse errors, duplicate ID handling across files (keep first in deterministic order, ignore later duplicates, warning + structured `LoadError`), deterministic ordering by filename lowercased with `Locale.ROOT` + exact filename + `id` + normalized path tie-breakers (including case-collision filename scenarios), and that `LoadResult` returns per-file structured errors correctly.

### M2 — Playable Core Loop (with tests)
- [x] 12. `src/main/groovy/se/alipsa/games/sc/core/Board.groovy`: board data structure and helpers (get/set, bounds check, clone for swap preview).
- [x] 13. `src/main/groovy/se/alipsa/games/sc/core/MatchFinder.groovy`: detect horizontal/vertical 3+ runs and build connected unique-cell match groups (for overlap-safe clear/scoring); used by both `GameEngine` (swap validation) and `BoardResolver` (cascade detection).
- [x] 13a. `src/main/groovy/se/alipsa/games/sc/core/GravityRefill.groovy`: apply gravity (drop candies down into empty cells) and refill empty cells from the top using spawn weights and injected RNG. Extracted from `BoardResolver` to keep each class focused on a single responsibility.
- [x] 14. `src/main/groovy/se/alipsa/games/sc/core/BoardResolver.groovy`: create initial board without pre-existing matches; clear grouped matches, delegate gravity and refill to `GravityRefill`, and cascade until stable; reshuffle when no legal swaps exist (100 attempts per seed, up to 5 seed retries); use injected RNG for deterministic initial fill/refill/reshuffle in tests. When all reshuffle retries are exhausted, signal via `GameListener.reshuffleExhausted()`. Note: internally decompose into focused methods (e.g. `initialFill`, `resolve`, `reshuffle`) to keep the class manageable.
- [x] 15. `src/main/groovy/se/alipsa/games/sc/core/GameEngine.groovy`: enforce orthogonal-adjacent swap validation (clone board → call `MatchFinder` → reject if no match), move counting, scoring formula (`group_size × 10` per matched group per cascade step), and explicit win/lose precedence (win check before lose when moves reach zero). On lose, emit a game-over event that the UI uses to offer retry or skip. Expose a `GameListener` callback interface for state-change events consumed by the UI. `GameEngine` stays Swing-agnostic and does not call EDT APIs directly.
- [x] 15a. `src/main/groovy/se/alipsa/games/sc/core/GameListener.groovy`: callback interface with methods for board-updated, score-changed, game-over, and reshuffle-exhausted events.
- [x] 16. `src/test/groovy/se/alipsa/games/sc/core/MatchFinderTest.groovy`: test match detection scenarios.
- [x] 17. `src/test/groovy/se/alipsa/games/sc/core/BoardResolverTest.groovy`: test initial-fill constraints (no pre-match), gravity/refill/cascade behavior (including `GravityRefill` integration), dead-board reshuffle, reshuffle-exhausted signal (after 100 attempts × 5 seeds), grouped-clear behavior, and deterministic behavior via seeded/injected RNG.
- [x] 18. `src/test/groovy/se/alipsa/games/sc/core/GameEngineTest.groovy`: test orthogonal-adjacent legal swaps, illegal swap rejection (non-adjacent and no-match), move decrement, overlap-safe grouped scoring, win/lose precedence outcomes, and lose-state event emission.
- [x] 18a. `src/test/groovy/se/alipsa/games/sc/core/IntegrationTest.groovy`: end-to-end smoke test — load a track JSON, create a board, play a scripted sequence of moves via seeded RNG, and verify final score/state through the full pipeline.
- [x] 18b. `src/test/groovy/se/alipsa/games/sc/core/ThreadingContractTest.groovy`: verify the UI/engine threading contract — engine resolution and emitted listener callbacks run on the game worker thread, UI observers marshal updates to EDT, move input is rejected/ignored while resolution is in progress, and the game worker is shut down cleanly on lifecycle end.

### M3 — Desktop Playable UI
- [x] 19. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy`: frame shell and top-level layout. Wire `SweetCrush.groovy` to launch `MainFrame` with full Swing composition (replacing the minimal M1 bootstrap). Own and manage game-worker lifecycle (initialize, reuse/recreate as needed, and shut down on window close).
- [x] 20. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: board rendering and mouse interactions for selecting/swapping candies; cell size is computed dynamically from the track's `width`/`height` to fit the available panel area. Dispatches moves to the single-thread `ExecutorService` game worker, disables input while resolving, and triggers repaint/state updates on EDT via `SwingUtilities.invokeLater`. On reshuffle-exhausted, display a dialog: *"Unable to continue this track (exhausted all attempts); would you like to restart or continue to the next track?"*.
- [x] 21. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy`: score, moves left, restart, and "Choose Track Folder".
- [x] 22. `src/main/groovy/se/alipsa/games/sc/ui/TrackSelectionDialog.groovy`: show loaded tracks in deterministic order by source filename lowercased with `Locale.ROOT`, then exact source filename, then track `id`, then normalized absolute file path, plus per-file load errors (`file`, `code`, `message`) from `LoadResult`. When `LoadResult` contains zero tracks (e.g. `NO_TRACKS_FOUND`), display an informational message: *"No tracks could be loaded from the selected directory. Please choose a directory containing valid track JSON files."*
- [x] 22a. `src/main/groovy/se/alipsa/games/sc/ui/GameOverDialog.groovy`: game-over dialog as a separate class. On win, show "Congratulations, you made it!" and advance to the next track. On lose, show a dialog offering **Retry** (restart the same track) or **Skip** (advance to the next track). After the last track, return to the track selection screen.
- [x] 22b. `src/test/groovy/se/alipsa/games/sc/ui/BoardPanelTest.groovy`: test board rendering and interaction — verify cell size is computed correctly from track dimensions, verify candy colors map to the expected `CandyType`, verify mouse click coordinates translate to the correct board cell, and verify that move input is rejected while the engine is resolving.
- [x] 22c. `src/test/groovy/se/alipsa/games/sc/ui/TrackSelectionDialogTest.groovy`: test track selection UI — verify tracks are displayed in deterministic order (lowercased filename + exact filename + id + normalized path), verify load errors are shown with file path and error code/message, and verify the empty-directory message is displayed when `LoadResult` contains zero tracks.
- [x] 22d. `src/test/groovy/se/alipsa/games/sc/ui/GameOverDialogTest.groovy`: test game-over dialog — verify win state shows congratulations message, verify lose state offers Retry and Skip options, and verify that after the last track the dialog returns to the track selection screen.
- [x] 22e. `src/test/groovy/se/alipsa/games/sc/ui/ControlPanelTest.groovy`: test control panel — verify score and moves-left labels update correctly when `GameListener` events fire, and verify the restart button resets the current track.
- [x] 22f. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy`: update in-game layout so the track list is shown on the right side and goal/score/moves are shown on the left side; selecting a track from the right-side list starts that track.
- [x] 22g. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy` + `ControlPanel.groovy`: move **Choose Track Folder** from the left control panel to the right-side track panel next to the track list.
- [x] 22h. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy`: on app startup, automatically load tracks from the last remembered track folder when it still exists.
- [x] 22i. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy`: wrap goal text to 20 characters per line for improved readability on narrow side panels/high-DPI displays.
- [x] 22j. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy`: increase spacing between goal, score, moves-left, and restart controls for better legibility.
- [x] 22k. `src/main/groovy/se/alipsa/games/sc/ui/WinCelebrationDialog.groovy` + `GameOverDialog.groovy`: on level win, show a 3-second fireworks celebration overlay with the message "Level completed, well done!" before continuing track progression.
- [x] 22l. `src/main/groovy/se/alipsa/games/sc/ui/TrackCreationDialog.groovy` + `MainFrame.groovy`: add a **Create Track** dialog in the current track directory with ID auto-generated from Name (and duplicate-safe counter suffix), spinner inputs for board/move/weight/special counts, and per-color scoring checkboxes.
- [x] 22m. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy` + `ControlPanel.groovy`: place track-folder action buttons on one row with compact sizing, emphasize score increases in bold for 3 seconds, and render `Specials` on a separate goal line for readability.
- [x] 22n. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy` + `ControlPanel.groovy`: show **live remaining special-piece counts** during play (updated after each resolved move), separate from static goal text.
- [x] 22o. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy`: render live specials as a vertical list with one row per special (`Sweeper`, `Small Bomb`, `Bomb`, `Fish`) to reduce horizontal crowding on the left panel.
- [x] 22p. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: improve blue-candy contrast against purple by slightly lightening the blue render color and applying a subtle brighten pass to the blue sprite at load time.
- [x] 22q. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: separate clear effects so sweeper activations show only sweep-beam animation (no burst pop), normal line clears use smaller per-piece pop effects, and large explosion effect is reserved for `SMALL_BOMB` activations.
- [x] 22r. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy`: keep left-panel text bold and use temporary font-size emphasis (+2pt for 3 seconds) when score increases or live special counts change.
- [x] 22s. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy` + `ControlPanel.groovy` + `BoardPanel.groovy`: apply dark-gray UI surfaces and set the right-side tracks list box to gray with readable contrast for text/selection.
- [x] 22t. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy`: tune right-side tracks list background to the same dark panel gray so it blends with the rest of the dark-mode layout.
- [x] 22u. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: restore prominent `SMALL_BOMB` explosion feedback by rendering large blast effects on the full cross-shaped clear area and suppressing tiny standard clear pops during small-bomb activations.
- [x] 22v. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: remove oval/ring callout styling from all special-piece overlays so icon shapes stay distinct without a uniform circular marker.
- [x] 22w. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: remove the shared upper-left gloss/blob from candy piece sprites at load time to avoid a uniform highlight artifact across all icons.
- [x] 22x. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: broaden candy-sprite gloss filtering to strip all semi-transparent near-white highlight dots globally (not just upper-left region) so no residual shared dot artifacts remain.
- [x] 22y. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: replace removed gloss pixels with nearby candy color (instead of transparency) so no dark background holes/dots appear at the former highlight positions.
- [x] 22z. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: keep recolored gloss replacement pixels fully opaque (using donor alpha) so no faint dark cap remains from semi-transparent replacement pixels.
- [x] 22aa. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: further tighten top-cap highlight cleanup (broader near-white threshold + top-zone targeting + expanding donor search) to eliminate residual cap artifacts across all candy icons.
- [x] 22ab. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: switch to broader top-cap artifact detection (neutral bright/dark pixels in top-left zone) with expanding donor replacement so residual highlight caps are removed consistently across all candy icons.
- [x] 22ac. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: replace destructive sprite pixel filtering with a non-destructive render-time top-cap cover patch (using candy base color) so residual cap artifacts are hidden without degrading icon details.
- [x] 22ad. `pom.xml` + `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: migrate SVG rendering from Batik to `com.github.weisj:jsvg:2.0.0` and update the SVG icon load path accordingly.
- [x] 22ae. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy`: widen the main app window by `+100px` (from `950` to `1050`) to improve fit for left panel + board + right panel layout.
- [x] 22af. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy` + `MainFrame.groovy` + `GameEngine.groovy`: add a left-panel **Log Board States** debug button that immediately logs the latest previous and current board states (plus score and moves left) for debugging piece-clear inconsistencies.
- [x] 22ag. `src/main/groovy/se/alipsa/games/sc/core/MatchFinder.groovy` + core tests: fix `SMALL_BOMB` detection for intersecting `T/L` groups larger than 5 cells (previously missed and could surface only `FISH` candidates), and add regression coverage for overlapping/intersecting patterns.
- [x] 22ah. `src/main/groovy/se/alipsa/games/sc/core/BoardResolver.groovy` + `GameListener.groovy` + UI integration/tests: implement fish target-priority behavior (`BOMB` > `SMALL_BOMB` > `SWEEPER` > `FISH` > normal candy), emit fish launch origin/target callbacks, and render fish swim animation path in `BoardPanel`.
- [x] 22ai. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: redraw the `SMALL_BOMB` icon to a cleaner cartoon-bomb silhouette (solid black body, angled cap, curved fuse, star spark) and clip/scale it so the entire icon stays inside the colored candy circle.
- [x] 22aj. `src/main/groovy/se/alipsa/games/sc/core/BoardResolver.groovy` + tests: restrict special creation to a **single winning candidate per cascade step** (highest priority first, still destination/source preferred when valid) so overlapping matches do not spawn multiple specials in one resolution.
- [x] 22ak. `src/main/groovy/se/alipsa/games/sc/ui/ControlPanel.groovy` + `MainFrame.groovy` + `GameEngine.groovy` + `BoardPanel.groovy`: add a left-panel **Hint** button that finds one legal swap and visually highlights both suggested cells on the board.
- [x] 22al. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: slow fish-swim animation timing by 50% for improved readability.
- [x] 22am. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: add a dedicated fish-impact burst on the target cell so fish activations always show a visible hit/explosion effect, even when standard clear bursts are suppressed by other special effects.
- [x] 22an. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: force fall-animation source tracking for sweep/fish-cleared cells so sweep combos (including `SWEEPER + FISH`) visibly remove cells and show resulting drops even when before/after candy values coincide by chance.
- [x] 22ao. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: retime transition phases for fish+sweeper combos so sweep beams render before movement/clear hiding, then falling begins after the sweep window (fixing the “sweep appears to do nothing” illusion).

### M4 — Sample Content + Full Docs
- [x] 23. `tracks/sample-track-01.json`: add example track pack for manual testing (use the example from the **Track JSON Schema** section as a starting point).
- [x] 23a. Rebalance bundled tracks in `tracks/` by halving `targetScore` values to make goals reachable during normal playtesting.
- [x] 24. `README.md` (full): expand with JSON format spec, track directory loading steps, and full run instructions.

### M5 — Candy Art Assets
- [x] 25. Source free-to-use candy/gem images (one per `CandyType` enum value). Search for openly licensed sprite assets (CC0, MIT, or similar) suitable for a match-3 game. Integrate images into `src/main/resources/images/` and update `BoardPanel` to render candy images instead of plain colored shapes.
- [x] 25a. Replace candy PNG visuals with custom SVG icons (`fire`, `water`, `tree`, `mountain`, `sword`) and map piece colors to East Asian five-element (Wu Xing) associations in `BoardPanel`.
- [x] 25b. Refine Wu Xing icon readability: lighten `YELLOW`/earth tones to better separate from brown and redraw the `GREEN`/wood icon as an oak tree silhouette (broad canopy + trunk) to avoid confusion with sword-like shapes.
- [x] 25c. Improve special `BOMB` clarity in `BoardPanel.groovy`: replace the ambiguous small marker with a full cartoon bomb icon (black bomb body + burning fuse) while preserving the candy color as the background.
- [x] 25d. Differentiate bomb tiers visually in `BoardPanel.groovy`: `SMALL_BOMB` now uses the cartoon bomb icon, while `BOMB` renders as a mushroom-cloud icon (atom-bomb style) to make the stronger special immediately recognizable.
- [x] 25e. Refresh `src/main/resources/images/app-icon.png` to match the new icon set: remove `SC` text and feature `mountain` + `water` candies/colors instead of the old red/blue pair.
- [x] 25f. Apply full-replacement special icon treatment to `FISH` in `BoardPanel.groovy`: hide the base candy symbol and render a dedicated fish graphic on the colored candy background for clearer recognition.
- [x] 25g. Remove glossy white dot highlights from the two candy pieces used in `src/main/resources/images/app-icon.png` to keep the app icon flat and free of dot artifacts.
- [x] 25h. Re-tune `app-icon.png` outer ring palette back toward the earlier warm pink/red look while keeping the mountain/water symbols and sparkles (and keeping white highlight dots removed from the two piece icons).
- [x] 25i. Further tune `app-icon.png` toward a dimmer, darker earthy mood (reduced ring brightness/saturation, darker center field, subtler sparkles) while preserving the mountain/water symbol composition.
- [x] 25j. Shift `app-icon.png` outer rings further from pink toward brown/amber tones (while keeping the darker center, mountain/water symbols, and sparkle accents) per visual feedback iteration.
- [x] 25k. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: redraw the `FISH` special overlay as a clearer fish silhouette (distinct body, tail, fins, and facial lines) with higher-contrast stroke/fill so it reads as fish at board scale.

### M6 — Platform Distributions
- [x] 26. Create platform-specific distributions for **Windows**, **Linux**, and **macOS** with bundled JRE (using `jlink`/`jpackage` or equivalent). Each distribution includes a launch script (`sweet-crush.bat` for Windows, `sweet-crush.sh` for Linux/macOS) and a self-contained Java runtime so end users do not need a pre-installed JDK.
- [x] 26a. Create GitHub repository `Alipsa/sweet-crush`, connect local project `origin`, and push the `main` branch.
- [x] 26b. Add GitHub Actions workflow (`.github/workflows/distributions.yml`) that builds zipped platform distributions on `ubuntu-latest`, `macos-latest`, and `windows-latest` using Maven dist profiles.

### M7 — Special Pieces (Sweepers, Small Bombs, Bombs, Fishes) + Track-Configurable Counts
- [x] 27. `src/main/groovy/se/alipsa/games/sc/core/SpecialPieceType.groovy`: add enum with `SWEEPER`, `SMALL_BOMB`, `BOMB`, `FISH`.
- [x] 27a. `src/main/groovy/se/alipsa/games/sc/core/Piece.groovy`: add board cell value object (`CandyType color`, optional `SpecialPieceType special`, optional orientation metadata for sweeper axis).
- [x] 28. `src/main/groovy/se/alipsa/games/sc/core/Board.groovy` + dependent core classes: refactor board storage from plain `CandyType` to `Piece` while preserving helper APIs for tests and UI convenience.
- [x] 29. `src/main/groovy/se/alipsa/games/sc/model/Track.groovy`: add `Map<SpecialPieceType, Integer> specialPieces` with defaults (`0` for all keys when omitted or partial).
- [x] 29a. `src/main/groovy/se/alipsa/games/sc/io/LoadErrorCode.groovy`: add `INVALID_SPECIAL_PIECES` for invalid special-piece configuration.
- [x] 30. `src/main/groovy/se/alipsa/games/sc/io/TrackValidator.groovy`: validate `specialPieces` map:
  - keys must be known `SpecialPieceType` names
  - values must be integers >= 0
  - omitted keys normalize to `0`
- [x] 31. `src/main/groovy/se/alipsa/games/sc/io/TrackLoader.groovy` + docs/examples: parse `specialPieces` map from JSON into `Track`.
- [x] 32. `src/main/groovy/se/alipsa/games/sc/core/MatchFinder.groovy`: detect special-creation patterns during resolution:
  - `4` in a straight line -> create `SWEEPER`
  - `T`/`L` shape with `5` cells total -> create `SMALL_BOMB`
  - `5` in a straight line -> create `BOMB`
  - `2x2` square of same color -> create `FISH`
- [x] 33. `src/main/groovy/se/alipsa/games/sc/core/BoardResolver.groovy`: create special piece at creation anchor when pattern is detected and per-track budget allows it; decrement corresponding budget on creation.
- [x] 34. `src/main/groovy/se/alipsa/games/sc/core/BoardResolver.groovy`: implement special activation behavior in cascade:
  - `SWEEPER`: clears full row (horizontal sweeper) or column (vertical sweeper)
  - `SMALL_BOMB`: clears a 5-cell cross (`+`) centered on trigger cell
  - `BOMB`: clears 3x3 area centered on trigger cell
  - `FISH`: clears one random valid target cell (deterministic under seeded RNG), then repeats for fish count triggered
- [x] 35. `src/main/groovy/se/alipsa/games/sc/core/GameEngine.groovy`: swap/trigger rules for specials:
  - swapping a special with any adjacent piece activates it
  - special cleared by another effect also activates (chain reactions)
  - budget limits only creation, not activation of already-created specials
- [x] 36. `src/main/groovy/se/alipsa/games/sc/ui/BoardPanel.groovy`: render special overlays/icons on top of candy art and add clear visual effects for sweeper/bomb/fish activations.
- [x] 37. `src/main/groovy/se/alipsa/games/sc/ui/MainFrame.groovy` + `ControlPanel.groovy`: goal text support for tracks containing special budgets (optional hint text such as `"Specials: Sweeper 3, Bomb 2, Fish 1"`).
- [x] 38. `src/test/groovy/se/alipsa/games/sc/io/TrackValidatorTest.groovy` + `TrackLoaderTest.groovy`: cover valid/invalid `specialPieces` maps (unknown keys, negative counts, omitted keys, omitted map).
- [x] 39. `src/test/groovy/se/alipsa/games/sc/core/MatchFinderTest.groovy` + `BoardResolverTest.groovy`: cover special creation detection, precedence, budget decrementing, and deterministic fish targeting.
- [x] 40. `src/test/groovy/se/alipsa/games/sc/core/GameEngineTest.groovy` + `IntegrationTest.groovy`: cover special activation via swap and cascade, chain reactions, and score/move correctness with specials.
- [x] 41. `src/test/groovy/se/alipsa/games/sc/ui/BoardPanelTest.groovy`: verify special rendering and that input locking still holds during larger special-effect cascades.
- [x] 42. `tracks/` + `README.md`: add at least one sample track that configures all three specials and document JSON semantics for `specialPieces`.
- [x] 42a. `GameEngine.groovy` + `BoardResolver.groovy` + `BoardPanel.groovy`: refine **sweeper** behavior so it only activates when part of a real 3+ line match (not by direct swap trigger/chain trigger), and render a directional sweep-beam animation expanding both ways from the activated sweeper.
- [x] 42b. `GameEngine.groovy` + `BoardResolver.groovy`: create match-formed special pieces at the player's **swap destination** when valid for the detected special pattern (fallback to default anchor when destination is not eligible).
- [x] 42c. `MatchFinder.groovy` + `BoardResolver.groovy`: add debug logging for special-piece candidate detection and creation decisions (candidate type/origin, anchor selection, budget-exhausted skips, and creation results) to simplify intermittent sweeper diagnostics.
- [x] 42d. `GameEngine.groovy` + `BoardResolver.groovy` + core tests: implement special-vs-special combo swap rules (`SWEEPER`/`SMALL_BOMB`/`FISH`/`BOMB`) including least-promising fish targeting for specific combos, color-convert mass activations for `* + BOMB`, and full-board clear for `BOMB + BOMB`.
- [x] 42e. `BoardResolver.groovy` + `GameListener.groovy` + `BoardPanel.groovy`: implement normal `BOMB` swap behavior to target the swapped candy color globally (clear all matching-color pieces), emit bomb-beam callbacks per target, and render beam feedback during transition animation.

### M8+ — Advanced Modes Expansion Plan (Blockers, Geometry, Objectives, Ingredients, Spawners, Campaign, Telemetry)

This section is the implementation plan for:
1. Blockers/obstacles with 1-3 hit layers (`JELLY`, `CRATE`, `LICORICE`, `ICE`, `HONEY`)
2. Board geometry (`HOLE`, one-way tiles, teleporters, split/irregular boards)
3. Multi-objective levels (score + clear + collect combinations)
4. Ingredient/drop mode (spawn cherries/nuts, drop to exits)
5. Spawner tiles (spawn blockers/specials every N turns)
6. Progression map (chapters/themes/unlocks)
7. Difficulty tuning by telemetry (auto-adjust score/moves by fail rate)

### M9 
- Store last used track directory in user preferences and auto-load on startup.
- Add a Edit track button that opens the current track in the same dialog as the create track editor dialog.
- increase fornt size for score and moves left labels and data for better readability.
- If a track in not completed, the only option should be to retry, not skip. Skip should only be offered after a track has been completed at least once.
- brown and yellow have the same icon. THe sword should be on yellow. And the smokey white where the sword used to be should have a cloud icon. 

#### JSON Schema Changes (Track Schema v2 + Campaign Schema v1)

- [ ] 43. `Track` schema versioning and compatibility:
  - Add `schemaVersion` (default `1` when omitted; new features require `2`).
  - Keep all existing v1 fields backwards-compatible.
- [x] 44. Add board topology and geometry fields to track JSON (M8b subset):
  - `board.mask: String[]` with row-wise topology where `.` = playable cell and `#` = hole.
  - `board.oneWay: [{x,y,direction}]` where direction is `UP|RIGHT|DOWN|LEFT`.
  - `board.teleporters: [{id, from:{x,y}, to:{x,y}}]`.
  - `board.spawnCells: [{x,y}]` and `board.exitCells: [{x,y}]` (used by ingredient/drop mode).
- [x] 45. Add blockers config:
  - `blockers: [{x,y,type,layers}]`.
  - `type` in `JELLY|CRATE|LICORICE|ICE|HONEY`.
  - `layers` constrained to `1..3`.
- [x] 46. Add multi-objective config:
  - `objectives: [{type,target,...}]`.
  - M8a objective types: `SCORE`, `CLEAR_BLOCKER`, `COLLECT_COLOR` (`DROP_INGREDIENT` deferred to ingredient phase).
  - `objectiveMode: "ALL"` for MVP (all objectives required to win).
- [ ] 47. Add ingredient/drop mode config:
  - `ingredients.enabled: boolean`.
  - `ingredients.queue: [{type,count}]` where `type` in `CHERRY|NUT`.
  - `ingredients.spawnEveryTurns: int >= 1`.
  - Ingredient movement uses board gravity and teleport topology.
- [ ] 48. Add spawner config:
  - `spawners: [{x,y,everyTurns,maxActive,table}]`.
  - `table: [{kind,type,layers,weight}]` where `kind` in `BLOCKER|SPECIAL`.
- [ ] 49. Add telemetry-driven tuning policy per track:
  - `difficultyTuning: {enabled,targetFailRate,minMoves,maxMoves,minTargetScore,maxTargetScore,adjustStepMoves,adjustStepScore}`.
  - Track definitions remain deterministic; tuning applies at session start and is persisted separately.
- [ ] 50. Add campaign schema file `tracks/campaign.json`:
  - `campaignId`, `name`, `chapters`.
  - Chapters contain ordered level IDs, theme metadata, and unlock conditions (`previousLevelWin`, optional star threshold).
  - Optional per-level mechanic gates (which blockers/geometry/special systems are enabled).
- [ ] 51. Add runtime persistence files (outside track JSON):
  - `progress.json` for campaign unlock state and stars.
  - `telemetry.jsonl` for anonymized local outcomes (track id, attempts, win/loss, moves used, score reached, timestamp).

#### Engine and UI Tasks

- [ ] 52. Core model refactor:
  - Introduce `CellType`, `BlockerType`, `ObjectiveType`, `IngredientType`.
  - Represent board as topology-aware grid (`playable`, `hole`, directional constraints, teleport links).
- [x] 53. Resolver changes for blockers (M8a baseline):
  - Resolve clear effects as damage events against blockers (decrement layers until removed).
  - Baseline in M8a: all blocker types (`JELLY`, `CRATE`, `LICORICE`, `ICE`, `HONEY`) use layered-hit behavior.
  - Type-specific advanced behaviors are deferred to a follow-up phase.
- [x] 54. Geometry-aware gravity/pathing (M8b subset):
  - Replace column-only gravity with mask-aware falling for hole/split layouts.
  - Support split/irregular boards naturally through `board.mask`.
  - Full graph/path one-way+teleporter pathing remains in M8c+.
- [x] 55. Objective engine:
  - Evaluate objective progress events (`onScore`, `onBlockerDamaged`, `onColorCollected`, `onIngredientDropped`).
  - Win condition becomes `all objectives complete` (with score-only fallback for legacy tracks).
- [ ] 56. Ingredient/drop engine:
  - Spawn ingredient entities from configured source cells.
  - Ingredients do not match; they fall/move until reaching exit cells.
  - Resolve blocked exits and deadlock detection with reshuffle-safe handling.
- [ ] 57. Spawner engine:
  - Turn counter and per-spawner cadence.
  - Spawn selection by weighted table with occupancy and `maxActive` guards.
  - Prevent impossible states (no spawn target available -> skip and log).
- [ ] 58. Campaign/progression engine:
  - Add campaign service for chapter navigation, unlock rules, and star calculation.
  - Persist progress on win and load on startup.
- [ ] 59. Telemetry + auto-tuning engine:
  - Record attempt outcomes locally.
  - At level start, compute rolling fail rate and adjust `moves`/`targetScore` within per-track bounds.
  - Use deterministic adjustment formula; always log applied adjustments.
- [x] 60. UI changes (M8a subset):
  - Left panel: multi-objective tracker with live per-objective progress.
  - Board: blocker layer badges/overlays.
  - Geometry-specific overlays, campaign map screen, and telemetry post-level summary are deferred.

#### Test Matrix

| Area             | Unit Tests                                              | Integration Tests                                 | UI Tests                                             | Property/Stress Tests                                                    |
|------------------|---------------------------------------------------------|---------------------------------------------------|------------------------------------------------------|--------------------------------------------------------------------------|
| Blockers         | layer decrement, remove rules, special-hit interactions | cascades with mixed blockers and specials         | blocker icon/layer rendering updates                 | randomized blocker layouts never produce invalid cell states             |
| Geometry         | hole bounds, one-way legality, teleporter mapping       | full resolve on split/irregular boards            | arrows/teleporter overlays and selection constraints | gravity/path solver termination on random masks                          |
| Multi-objective  | objective progress increments and completion logic      | score+clear+collect combined win conditions       | objective panel updates and win transition           | invariant: win only when all objectives complete                         |
| Ingredient mode  | spawn cadence, exit detection, blocked exit logic       | ingredient drop with cascades/specials/teleports  | ingredient icon movement and counters                | no stuck ingredient without either valid move, reshuffle, or fail signal |
| Spawners         | cadence and weighted spawn selection                    | long-run levels with repeated spawns              | spawner marker and spawn feedback                    | bounded active spawns and no infinite resolve loop                       |
| Campaign map     | unlock condition evaluation, persistence I/O            | chapter progression and replay behavior           | map navigation and lock state visuals                | persistence corruption fallback and migration handling                   |
| Telemetry tuning | rolling fail-rate computation and clamp bounds          | repeated attempts change start params as expected | adjustment disclosure in pre-level/post-level UI     | monotonic bounded adjustments under extreme win/loss streaks             |

#### Phased Rollout (Quick Wins First)

- [x] Phase 1 (Quick Wins): `M8a`
  - Deliver blockers (`JELLY`, `CRATE`, `ICE`) with 1-3 layers.
  - Deliver multi-objective engine with `SCORE`, `CLEAR_BLOCKER`, `COLLECT_COLOR`.
  - Keep rectangular boards only in this phase.
  - Exit criteria: stable playability, objective UI, full regression green.
- [x] Phase 2: `M8b`
  - Add irregular/split boards via `board.mask` and `HOLE`.
  - Add geometry-aware gravity for masked boards.
  - Exit criteria: no deadlocks introduced by holes/splits in soak tests.
- [x] Phase 3: `M8c`
  - Add one-way tiles and teleporters.
  - Add board debug visualization and path diagnostics logging.
  - Exit criteria: deterministic pathing and legal-swap validation on all geometry types.
- [ ] Phase 4: `M8d`
  - Add ingredient/drop mode (`CHERRY`, `NUT`) and exit cells.
  - Add spawner tiles for blockers and specials.
  - Exit criteria: ingredient and spawner interaction tests green with specials enabled.
- [ ] Phase 5: `M8e`
  - Add campaign map (`campaign.json`) with chapters, themes, unlock rules, and progress persistence.
  - Exit criteria: complete chapter flow from locked start to unlock progression.
- [ ] Phase 6: `M8f`
  - Add telemetry capture and bounded auto-tuning (moves/target score).
  - Add user-visible adjustment messaging and opt-out toggle in settings.
  - Exit criteria: tuning changes are reproducible, bounded, and transparent.
- [ ] Phase 7 (Polish/Balancing): `M8g`
  - Content balancing pass, tutorial tooltips for new mechanics, and performance optimization.
  - Exit criteria: target FPS/UI responsiveness maintained on HiDPI and standard displays.

## Task Dependencies
- Tasks 1a/1b/1c/1d (pom.xml + log4j config) block everything — nothing compiles without them. Execute in order: 1a → 1b → 1c → 1d.
- M1 ordering: 1a–1d → 2, 1a–1d → 4 → 8, 1a–1d → 5 → 5a → 8, 5a/6/7/8 → 9, 8 → 10, 9 → 11. Task 3 depends only on 1a–1d.
- M2 ordering: 12 → 13 → 13a → 14 → 15/15a → 16, 17, 18, 18a, 18b. `MatchFinder` feeds both `BoardResolver` and `GameEngine`. `GravityRefill` feeds `BoardResolver`.
- M3 ordering: 19 → 20, 21, 22, 22a (panels and dialogs plug into frame). 20 → 22b, 22 → 22c, 22a → 22d, 21 → 22e (UI tests follow the component they test). All M3 tasks depend on M2 completion plus 15a (`GameListener`), and Task 3 (`SweetCrush`) is fully wired to Swing here.
- M5 depends on M3 completion (need `BoardPanel` to integrate images into).
- M6 depends on M4 completion (full docs and sample content finalized before packaging).
- M7 depends on M2 completion (core resolver/engine in place), and on M3 for special rendering/UI feedback.
- M7 suggested implementation order: 27 → 27a → 28 → 29/29a/30/31 → 32 → 33 → 34 → 35 → 38/39/40 → 36/41 → 37/42.
- M8+ depends on M7 completion (special pipeline stable). Suggested execution order: Phase 1 blockers+multi-objective → Phase 2 mask/holes/split gravity → Phase 3 one-way+teleporters → Phase 4 ingredients+spawners → Phase 5 campaign map → Phase 6 telemetry auto-tuning → Phase 7 balancing.

## Milestone Gates
- [X] M1: Items 1a–11 complete (build works, log4j configured, `CandyType` defined, `LoadErrorCode` enum defined, load result/error types in place, track pack loading fully tested, minimal README in place). `mvn clean verify` passes, and there are zero compiler warnings from project sources (`src/main` + `src/test`).
- [X] M2: Items 12–18b complete (playable core loop with separated gravity/refill, engine/resolver/integration/threading-contract tests passing). `mvn clean verify` passes, and there are zero compiler warnings from project sources (`src/main` + `src/test`).
- [X] M3: Items 19–22l complete (desktop playable UI with win/lose/reshuffle-exhausted/empty-directory dialogs, UI component tests passing).
- [X] M4: Items 23–24 complete (sample content, full docs).
- [X] M5: Item 25 complete (candy art assets integrated into board rendering).
- [x] M6: Item 26 complete (platform distributions for Windows, Linux, macOS with bundled JRE).
- [x] M7: Items 27–42 complete (special pieces created from 4-line/T-or-L-5/5-line/2x2 patterns, activations implemented, track-configurable counts supported, and tests/docs/sample tracks updated).
- [x] M8a: Phase 1 complete (blockers + multi-objective quick wins).
- [x] M8b: Phase 2 complete (holes/split/irregular board geometry via mask + gravity updates).
- [x] M8c: Phase 3 complete (one-way tiles + teleporters).
- [ ] M8d: Phase 4 complete (ingredient/drop mode + spawner tiles).
- [ ] M8e: Phase 5 complete (campaign map + progression persistence).
- [ ] M8f: Phase 6 complete (telemetry collection + bounded difficulty auto-tuning).
- [ ] M8g: Phase 7 complete (balancing/performance/polish).
- [ ] M9: 
## Definition Of Done (MVP)
- [ ] `mvn clean verify` passes with all tests green and zero compiler warnings from project sources (`src/main` + `src/test`).
- [ ] User can pick any folder of track JSON files and play any valid track.
- [ ] Core Candy Crush mechanics work reliably (adjacent swap/match/clear/gravity/refill/cascade/reshuffle).
- [ ] Invalid track files are reported clearly without crashing.
- [ ] Empty track directories are handled gracefully with an informational UI message.
- [ ] Core engine and loader are covered by automated tests.
- [ ] Board start-state has no pre-existing matches, and gameplay never remains in a dead-board state.
- [ ] When reshuffle retries are exhausted (100 attempts × 5 seeds), the player is prompted to restart or skip.
- [ ] On lose, the player can retry the track or skip to the next one.
- [ ] UI remains responsive during cascade resolution by running game logic off the EDT.

## Out of Scope (MVP)
- Special candy types (striped, wrapped, color bomb).
- Animations or timed transitions.
- Persistent high scores or save state.
- Sound effects.
- Networked or online multiplayer.
