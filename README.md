# Sweet Crush

Sweet Crush is a Groovy + Swing match-3 game with track packs loaded from a user-selected folder of JSON files.

## Prerequisites
- JDK 25+
- Maven 3.9+

## Build and Test
```bash
mvn clean verify
```

## Run
```bash
mvn exec:java
```

Notes:
- The app runs with UI scale `2.0` by default (`sun.java2d.uiScale=2.0`) for HiDPI readability.
- The last selected track folder is remembered and auto-loaded on startup when available.

## In-Game UI Features
- Choose track folder and select a track.
- Create track dialog (writes a JSON track into the current track directory).
- Hint button (highlights one legal swap).
- Log Board States button (logs previous/current board snapshots for debugging).
- Live left-panel display of goal text, score, moves left, and remaining special budgets.

## Build Distributions (Local)
Distributions are platform-native and must be built on the target OS.

- Linux (`sweet-crush.sh` + slim runtime + zip):
```bash
mvn -Pdist-linux clean package
```
- macOS (`sweet-crush.app` + `sweet-crush.sh` + slim runtime + zip):
```bash
mvn -Pdist-macos clean package
```
- Windows (`sweet-crush.bat` + slim runtime + zip):
```powershell
mvn -Pdist-windows clean package
```

Artifacts are written to `target/dist/` as:
- `sweet-crush-linux-<arch>.zip`
- `sweet-crush-macos-<arch>.zip`
- `sweet-crush-windows-<arch>.zip`

Each bundle includes:
- app jar + runtime dependencies
- custom `jlink` runtime (modules derived from `jdeps`)
- bundled sample `tracks/`

## Build Distributions (GitHub Actions)
A CI workflow builds and uploads platform zips on:
- `ubuntu-latest`
- `macos-latest`
- `windows-latest`

Workflow file:
- `.github/workflows/distributions.yml`

Triggered on:
- push to `main`
- pull request
- manual dispatch (`workflow_dispatch`)

## Track JSON Format
Each track file contains one JSON object with these fields:

| Field           | Type                   | Required | Description |
|-----------------|------------------------|----------|-------------|
| `id`            | `String`               | yes      | Unique identifier across loaded tracks |
| `name`          | `String`               | yes      | Display name |
| `width`         | `int`                  | yes      | Board width (3-20) |
| `height`        | `int`                  | yes      | Board height (3-20) |
| `moves`         | `int`                  | yes      | Allowed moves (`> 0`) |
| `targetScore`   | `int`                  | yes      | Score needed to win (`> 0`) |
| `scoreColors`   | `String` or `String[]` | no       | Which colors score. Use `"ALL"` (or omit) for all colors, or an array like `["RED"]` |
| `spawnWeights`  | `Map<String,int>`      | no       | Candy spawn weights keyed by `CandyType` (`RED`, `BLUE`, `GREEN`, `YELLOW`, `PURPLE`, `ORANGE`) |
| `specialPieces` | `Map<String,int>`      | no       | Creation budget per special (`SWEEPER`, `SMALL_BOMB`, `BOMB`, `FISH`) |

### Validation Rules
- `id`: non-blank, max 64 chars, pattern `[a-zA-Z0-9][a-zA-Z0-9._-]*`
- `name`: non-blank
- `width`/`height`: integer 3..20
- `moves`: integer > 0
- `targetScore`: integer > 0
- `spawnWeights`:
  - omitted -> uniform across all colors
  - partial map -> omitted colors become `0`
  - non-negative integers only
  - total weight > 0
  - at least 3 colors with positive weight
- `scoreColors`:
  - omitted or `"ALL"` -> all colors score
  - array must contain known color names
- `specialPieces`:
  - omitted -> all budgets default to `0`
  - unknown keys or negative values are invalid

### Example Tracks
- [tracks/sample-track-01.json](tracks/sample-track-01.json)
- [tracks/test-track-01.json](tracks/test-track-01.json)
- [tracks/test-track-red-only.json](tracks/test-track-red-only.json)

## Gameplay Rules
### Core
- Swap must be orthogonally adjacent.
- For normal pieces, the swap must produce at least one match.
- Matches include horizontal/vertical 3+ lines and 2x2 squares.
- Overlapping runs are merged into unique-cell groups.
- Score is `10 * cleared eligible cells` (eligible = all colors or `scoreColors` subset).
- Win: `score >= targetScore` (checked before lose on final move).
- Lose: `movesLeft == 0` and target not reached.
- Initial board is pre-cleaned (no pre-match) and reshuffled when dead.

### Special Creation
- 4 in a line -> `SWEEPER` (orientation from line direction)
- 5 in T/L shape -> `SMALL_BOMB`
- 5 in a straight line -> `BOMB`
- 2x2 square -> `FISH`
- `specialPieces` only limits creation, not activation.

### Special Activation (single special)
- `SWEEPER`: clears full row/column, but only activates from a real 3+ line match.
- `SMALL_BOMB`: clears 5-cell cross (`+`) centered at trigger.
- `BOMB`: clears 3x3 area normally; when swap-triggered with a non-special, targets all candies of the swapped color.
- `FISH`: targets a "best" location (prefers `BOMB` > `SMALL_BOMB` > `SWEEPER` > `FISH` > normal).

### Special + Special Combo Activation
- `SWEEPER + SWEEPER`: sweep both directions from both origins.
- `SWEEPER + SMALL_BOMB`: sweep 3 parallel lines.
- `SWEEPER + FISH`: fish moves to least-promising target, then sweep there.
- `SWEEPER + BOMB`: all candies of sweeper color become sweeps and activate.
- `SMALL_BOMB + SMALL_BOMB`: enlarged blast area (combined with both centers).
- `SMALL_BOMB + FISH`: fish moves to least-promising target, then bomb blast there.
- `SMALL_BOMB + BOMB`: all candies of small-bomb color become small bombs and activate.
- `FISH + FISH`: two fish launches.
- `FISH + BOMB`: candies of fish color become fish launches.
- `BOMB + BOMB`: full-board clear effect.

## Track Loading Behavior
- Loads `*.json` files from selected folder.
- Returns structured per-file errors (`file`, `code`, `message`) for invalid tracks.
- Duplicate `id`: keeps first track in deterministic order, reports duplicates as load errors.
- Empty directory: emits `NO_TRACKS_FOUND`; UI shows informational message.

## Assets
- Candy piece art is generated in-repository and rendered from SVG resources in `src/main/resources/images/`.
- Special overlays (sweeper, small bomb, bomb, fish) are rendered in code by `BoardPanel`.
- App icon: `src/main/resources/images/app-icon.png`.
- Assets are project-created originals (not third-party pack assets). See `src/main/resources/images/README.md`.
