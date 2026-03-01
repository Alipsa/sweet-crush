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

When the app starts:
1. Click **Choose Track Folder**.
2. Select a directory containing one or more track JSON files.
3. Pick a track in the selection dialog and play.

## Build Distributions (M6)
Distributions are native-platform builds and must be created on the target OS.

- Linux distribution (slim runtime + `sweet-crush.sh` + zip):
```bash
mvn -Pdist-linux clean package
```
- macOS distribution (slim runtime + `sweet-crush.app` + `sweet-crush.sh` + zip):
```bash
mvn -Pdist-macos clean package
```
- Windows distribution (slim runtime + `sweet-crush.bat` + zip):
```powershell
mvn -Pdist-windows clean package
```

Output artifacts are written to `target/dist/`:
- `${artifactId}-linux-<arch>.zip`
- `${artifactId}-macos-<arch>.zip`
- `${artifactId}-windows-<arch>.zip`

Each distribution includes:
- a custom `jlink` runtime containing only required JDK modules (derived from `jdeps`)
- the application jar + runtime dependencies
- bundled `tracks/` sample content

## Track JSON Format
Each track file must contain one JSON object with the fields below.

| Field           | Type                   | Required | Description                                                                                                 |
|-----------------|------------------------|----------|-------------------------------------------------------------------------------------------------------------|
| `id`            | `String`               | yes      | Unique identifier across loaded tracks                                                                      |
| `name`          | `String`               | yes      | Display name                                                                                                |
| `width`         | `int`                  | yes      | Board width (3-20)                                                                                          |
| `height`        | `int`                  | yes      | Board height (3-20)                                                                                         |
| `moves`         | `int`                  | yes      | Allowed moves (`> 0`)                                                                                       |
| `targetScore`   | `int`                  | yes      | Score needed to win (`> 0`)                                                                                 |
| `scoreColors`   | `String` or `String[]` | no       | Which candy colors give points. Use `"ALL"` (or omit) for all colors, or an array like `["RED"]`            |
| `spawnWeights`  | `Map<String,int>`      | no       | Candy spawn weights keyed by `CandyType` (`RED`, `BLUE`, `GREEN`, `YELLOW`, `PURPLE`, `ORANGE`)             |
| `specialPieces` | `Map<String,int>`      | no       | Creation budget per special type (`SWEEPER`, `SMALL_BOMB`, `BOMB`, `FISH`). Omitted map/keys default to `0` |

### Spawn Weight Rules
- If `spawnWeights` is omitted, all candy types use uniform weight.
- If `spawnWeights` is present but partial, omitted candy types are treated as weight `0`.
- Weights must be non-negative.
- Total weight must be positive.
- At least 3 candy types must have positive weight.

### Scoring Rules
- Base points are still `10` per scoring candy cleared.
- If `scoreColors` is omitted or set to `"ALL"`, all cleared candies score.
- If `scoreColors` is an array (for example `["RED"]`), only those colors contribute points.
- Move limit and target score remain `moves` and `targetScore`.

### Special Piece Rules
- `specialPieces` controls how many special candies can be *created* during a track.
- Creation rules:
  - 4 in a row -> `SWEEPER`
  - T/L shape with 5 candies total -> `SMALL_BOMB`
  - 5 in a row -> `BOMB`
  - 2x2 square -> `FISH`
- Activation rules:
  - Swap a special with any adjacent piece to activate it.
  - If a special is cleared by another effect, it also activates (chain reaction).
- Effects:
  - `SWEEPER` clears a full row or column (orientation depends on creation line direction).
  - `SMALL_BOMB` clears a 5-cell cross (`+`) centered on itself.
  - `BOMB` clears a 3x3 area around itself.
  - `FISH` clears one random target candy.

### Example Track
Use [tracks/sample-track-01.json](tracks/sample-track-01.json) as a reference.
For restricted scoring, see [tracks/test-track-red-only.json](tracks/test-track-red-only.json).

## Track Loading Behavior
- Loads `*.json` files from the selected folder.
- Returns structured per-file errors (`file`, `code`, `message`) for malformed/invalid tracks.
- Duplicate track `id` handling keeps the first track in deterministic order and reports the duplicate.
- If no JSON files exist in the selected folder, the loader reports `NO_TRACKS_FOUND` and the UI shows an informational message.

## Core Gameplay Rules
- Swap must be orthogonally adjacent and create at least one match, unless one swapped piece is special.
- Matches are horizontal/vertical runs of 3+ candies, plus 2x2 square matches.
- Overlapping runs are merged into unique-cell groups.
- Score per cascade step: `group_size * 10` for each cleared group.
- Win condition: `score >= targetScore` after cascade resolution.
- Lose condition: `movesLeft == 0` and target not reached.
- Initial board contains no pre-existing matches and is reshuffled if no legal swaps exist.

## Candy Assets
- Candy images are stored in `src/main/resources/images/` and rendered by `BoardPanel`.
- The six candy PNG files (`red`, `blue`, `green`, `yellow`, `purple`, `orange`) are original in-repo assets.
- Application window icon: `src/main/resources/images/app-icon.png`.
- Asset license: CC0-1.0 (see `src/main/resources/images/README.md`).
