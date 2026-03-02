package se.alipsa.games.sc.model

import se.alipsa.games.sc.core.Blocker
import se.alipsa.games.sc.core.BlockerType
import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.FlowDirection
import se.alipsa.games.sc.core.IngredientType
import se.alipsa.games.sc.core.Position
import se.alipsa.games.sc.core.SpecialPieceType

import java.nio.file.Path

class Track {
  final String id
  final String name
  final int width
  final int height
  final int moves
  final int targetScore
  final Map<CandyType, Integer> spawnWeights
  final Set<CandyType> scoreColors
  final Map<SpecialPieceType, Integer> specialPieces
  final Map<Position, Blocker> blockers
  final List<Objective> objectives
  final List<String> boardMask
  final Map<Position, FlowDirection> oneWayTiles
  final Map<Position, Position> teleporters
  final IngredientConfig ingredientConfig
  final List<Position> spawnCells
  final List<Position> exitCells
  final List<SpawnerConfig> spawners
  Path sourcePath

  Track(String id,
        String name,
        int width,
        int height,
        int moves,
        int targetScore,
        Map<CandyType, Integer> spawnWeights = null,
        Set<CandyType> scoreColors = null,
        Map<SpecialPieceType, Integer> specialPieces = null,
        Map<Position, Blocker> blockers = null,
        List<Objective> objectives = null,
        List<String> boardMask = null,
        Map<Position, FlowDirection> oneWayTiles = null,
        Map<Position, Position> teleporters = null,
        IngredientConfig ingredientConfig = null,
        List<Position> spawnCells = null,
        List<Position> exitCells = null,
        List<SpawnerConfig> spawners = null) {
    this.id = id
    this.name = name
    this.width = width
    this.height = height
    this.moves = moves
    this.targetScore = targetScore
    this.spawnWeights = Collections.unmodifiableMap(normalizeSpawnWeights(spawnWeights))
    this.scoreColors = Collections.unmodifiableSet(normalizeScoreColors(scoreColors))
    this.specialPieces = Collections.unmodifiableMap(normalizeSpecialPieces(specialPieces))
    this.blockers = Collections.unmodifiableMap(normalizeBlockers(blockers))
    this.objectives = Collections.unmodifiableList(normalizeObjectives(objectives))
    this.boardMask = Collections.unmodifiableList(normalizeBoardMask(width, height, boardMask))
    this.oneWayTiles = Collections.unmodifiableMap(normalizeOneWayTiles(oneWayTiles))
    this.teleporters = Collections.unmodifiableMap(normalizeTeleporters(teleporters))
    this.ingredientConfig = normalizeIngredientConfig(ingredientConfig)
    this.spawnCells = Collections.unmodifiableList(normalizePositionList(spawnCells))
    this.exitCells = Collections.unmodifiableList(normalizePositionList(exitCells))
    this.spawners = Collections.unmodifiableList(normalizeSpawners(spawners))
  }

  static Track fromMap(Map<String, ?> data) {
    Map<CandyType, Integer> weights = null
    if (data.containsKey('spawnWeights') && data.spawnWeights != null) {
      Map<String, ?> rawWeights = data.spawnWeights as Map<String, ?>
      weights = normalizeSpawnWeights(rawWeights)
    }

    Set<CandyType> scoreColors = null
    if (data.containsKey('scoreColors') && data.scoreColors != null) {
      Object rawScoreColors = data.scoreColors
      if (rawScoreColors instanceof CharSequence && rawScoreColors.toString().equalsIgnoreCase('ALL')) {
        scoreColors = null
      } else {
        scoreColors = (rawScoreColors as Collection<?>).collect { Object value ->
          CandyType.valueOf(value.toString())
        } as Set<CandyType>
      }
    }

    Map<SpecialPieceType, Integer> specialPieces = null
    if (data.containsKey('specialPieces') && data.specialPieces != null) {
      specialPieces = normalizeSpecialPieces(data.specialPieces as Map<?, ?>)
    }

    Map<Position, Blocker> blockers = null
    if (data.containsKey('blockers') && data.blockers != null) {
      blockers = normalizeBlockersFromList(data.blockers as Collection<?>)
    }

    List<Objective> objectives = null
    if (data.containsKey('objectives') && data.objectives != null) {
      objectives = normalizeObjectivesFromList(data.objectives as Collection<?>)
    }

    List<String> boardMask = null
    Map<?, ?> boardConfig = data.containsKey('board') && data.board instanceof Map ? (Map<?, ?>) data.board : null
    if (boardConfig != null && boardConfig.containsKey('mask')) {
      Object mask = boardConfig.mask
      if (mask != null) {
        boardMask = (mask as Collection<?>).collect { Object row -> row.toString() }
      }
    } else if (data.containsKey('mask') && data.mask != null) {
      boardMask = (data.mask as Collection<?>).collect { Object row -> row.toString() }
    }

    Map<Position, FlowDirection> oneWayTiles = null
    if (boardConfig != null && boardConfig.containsKey('oneWay') && boardConfig.oneWay != null) {
      oneWayTiles = normalizeOneWayTilesFromList(boardConfig.oneWay as Collection<?>)
    }

    Map<Position, Position> teleporters = null
    if (boardConfig != null && boardConfig.containsKey('teleporters') && boardConfig.teleporters != null) {
      teleporters = normalizeTeleportersFromList(boardConfig.teleporters as Collection<?>)
    }

    IngredientConfig ingredientConfig = null
    if (data.containsKey('ingredients') && data.ingredients != null) {
      ingredientConfig = parseIngredientConfig(data.ingredients as Map<?, ?>)
    }

    List<Position> spawnCells = null
    if (boardConfig != null && boardConfig.containsKey('spawnCells') && boardConfig.spawnCells != null) {
      spawnCells = parsePositionList(boardConfig.spawnCells as Collection<?>)
    }

    List<Position> exitCells = null
    if (boardConfig != null && boardConfig.containsKey('exitCells') && boardConfig.exitCells != null) {
      exitCells = parsePositionList(boardConfig.exitCells as Collection<?>)
    }

    List<SpawnerConfig> spawners = null
    if (data.containsKey('spawners') && data.spawners != null) {
      spawners = parseSpawnersList(data.spawners as Collection<?>)
    }

    return new Track(
        data.id?.toString(),
        data.name?.toString(),
        ((Number) data.width).intValue(),
        ((Number) data.height).intValue(),
        ((Number) data.moves).intValue(),
        ((Number) data.targetScore).intValue(),
        weights,
        scoreColors,
        specialPieces,
        blockers,
        objectives,
        boardMask,
        oneWayTiles,
        teleporters,
        ingredientConfig,
        spawnCells,
        exitCells,
        spawners
    )
  }

  boolean scoresAllColors() {
    scoreColors.size() == CandyType.values().size()
  }

  int specialPieceBudget(SpecialPieceType type) {
    specialPieces.getOrDefault(type, 0)
  }

  boolean hasObjectives() {
    !objectives.isEmpty()
  }

  boolean hasMask() {
    boardMask.any { String row -> row.contains('#') }
  }

  boolean hasOneWayTiles() {
    !oneWayTiles.isEmpty()
  }

  boolean hasTeleporters() {
    !teleporters.isEmpty()
  }

  boolean hasIngredients() {
    ingredientConfig != null && ingredientConfig.enabled
  }

  boolean hasSpawners() {
    !spawners.isEmpty()
  }

  boolean isPlayable(int x, int y) {
    if (x < 0 || y < 0 || y >= boardMask.size() || x >= boardMask[y].length()) {
      return false
    }
    boardMask[y].charAt(x) == '.'
  }

  boolean[][] playableMask() {
    boolean[][] mask = new boolean[height][width]
    for (int y = 0; y < height; y++) {
      String row = boardMask[y]
      for (int x = 0; x < width; x++) {
        mask[y][x] = row.charAt(x) == '.'
      }
    }
    mask
  }

  private static Map<CandyType, Integer> normalizeSpawnWeights(Map<?, ?> rawWeights) {
    if (rawWeights == null) {
      return uniformSpawnWeights()
    }

    Map<CandyType, Integer> normalized = CandyType.values().collectEntries { CandyType type ->
      [(type): 0]
    }

    rawWeights.each { Object key, Object value ->
      CandyType type = CandyType.valueOf(key.toString())
      int weight = ((Number) value).intValue()
      normalized[type] = weight
    }

    return normalized
  }

  private static Map<CandyType, Integer> uniformSpawnWeights() {
    return CandyType.values().collectEntries { CandyType type ->
      [(type): 1]
    }
  }

  private static Set<CandyType> normalizeScoreColors(Set<CandyType> scoreColors) {
    if (scoreColors == null || scoreColors.isEmpty()) {
      return EnumSet.allOf(CandyType)
    }
    return EnumSet.copyOf(scoreColors)
  }

  private static Map<SpecialPieceType, Integer> normalizeSpecialPieces(Map<?, ?> rawSpecialPieces) {
    Map<SpecialPieceType, Integer> normalized = SpecialPieceType.values().collectEntries { SpecialPieceType type ->
      [(type): 0]
    } as Map<SpecialPieceType, Integer>

    if (rawSpecialPieces == null) {
      return normalized
    }

    rawSpecialPieces.each { Object key, Object value ->
      SpecialPieceType type = key instanceof SpecialPieceType
          ? (SpecialPieceType) key
          : SpecialPieceType.valueOf(key.toString())
      int count = ((Number) value).intValue()
      normalized[type] = Math.max(0, count)
    }

    normalized
  }

  private static Map<Position, Blocker> normalizeBlockers(Map<?, ?> rawBlockers) {
    if (rawBlockers == null) {
      return [:]
    }

    Map<Position, Blocker> normalized = [:]
    rawBlockers.each { Object key, Object value ->
      Position pos = key as Position
      Blocker blocker = value as Blocker
      if (pos != null && blocker != null) {
        normalized[pos] = blocker
      }
    }
    normalized
  }

  private static Map<Position, Blocker> normalizeBlockersFromList(Collection<?> rawBlockers) {
    if (rawBlockers == null) {
      return [:]
    }

    Map<Position, Blocker> normalized = [:]
    rawBlockers.each { Object item ->
      Map<?, ?> entry = item as Map<?, ?>
      int x = ((Number) entry.x).intValue()
      int y = ((Number) entry.y).intValue()
      BlockerType type = BlockerType.valueOf(entry.type.toString())
      int layers = ((Number) entry.layers).intValue()
      normalized[new Position(x, y)] = new Blocker(type, layers)
    }
    normalized
  }

  private static List<Objective> normalizeObjectives(List<Objective> rawObjectives) {
    if (rawObjectives == null) {
      return []
    }
    rawObjectives.findAll { it != null }
  }

  private static List<Objective> normalizeObjectivesFromList(Collection<?> rawObjectives) {
    if (rawObjectives == null) {
      return []
    }

    List<Objective> normalized = []
    rawObjectives.each { Object item ->
      Map<?, ?> entry = item as Map<?, ?>
      ObjectiveType type = ObjectiveType.valueOf(entry.type.toString())
      int target = ((Number) entry.target).intValue()
      CandyType color = entry.containsKey('color') && entry.color != null ? CandyType.valueOf(entry.color.toString()) : null
      BlockerType blockerType = entry.containsKey('blockerType') && entry.blockerType != null
          ? BlockerType.valueOf(entry.blockerType.toString())
          : null
      normalized << new Objective(type, target, color, blockerType)
    }
    normalized
  }

  private static List<String> normalizeBoardMask(int width, int height, List<String> rawMask) {
    if (rawMask == null || rawMask.isEmpty()) {
      return (0..<height).collect { '.' * width }
    }

    List<String> normalized = rawMask.collect { String row ->
      row ?: ''
    }
    if (normalized.size() != height) {
      throw new IllegalArgumentException("board.mask height ${normalized.size()} does not match track height ${height}")
    }
    normalized.eachWithIndex { String row, int y ->
      if (row.length() != width) {
        throw new IllegalArgumentException("board.mask row ${y} width ${row.length()} does not match track width ${width}")
      }
      for (int x = 0; x < row.length(); x++) {
        char cell = row.charAt(x)
        if (cell != '.' && cell != '#') {
          throw new IllegalArgumentException("board.mask contains invalid cell '${cell}' at (${x},${y}); allowed: '.' and '#'")
        }
      }
    }
    if (!normalized.any { String row -> row.contains('.') }) {
      throw new IllegalArgumentException('board.mask must contain at least one playable cell (\'.\')')
    }
    normalized
  }

  private static Map<Position, FlowDirection> normalizeOneWayTiles(Map<?, ?> rawOneWayTiles) {
    if (rawOneWayTiles == null) {
      return [:]
    }
    Map<Position, FlowDirection> normalized = [:]
    rawOneWayTiles.each { Object key, Object value ->
      Position pos = key as Position
      FlowDirection direction = value as FlowDirection
      if (pos != null && direction != null) {
        normalized[pos] = direction
      }
    }
    normalized
  }

  private static Map<Position, FlowDirection> normalizeOneWayTilesFromList(Collection<?> rawOneWayTiles) {
    if (rawOneWayTiles == null) {
      return [:]
    }
    Map<Position, FlowDirection> normalized = [:]
    rawOneWayTiles.each { Object item ->
      Map<?, ?> entry = item as Map<?, ?>
      int x = ((Number) entry.x).intValue()
      int y = ((Number) entry.y).intValue()
      FlowDirection direction = FlowDirection.valueOf(entry.direction.toString())
      normalized[new Position(x, y)] = direction
    }
    normalized
  }

  private static Map<Position, Position> normalizeTeleporters(Map<?, ?> rawTeleporters) {
    if (rawTeleporters == null) {
      return [:]
    }
    Map<Position, Position> normalized = [:]
    rawTeleporters.each { Object key, Object value ->
      Position from = key as Position
      Position to = value as Position
      if (from != null && to != null) {
        normalized[from] = to
      }
    }
    normalized
  }

  private static Map<Position, Position> normalizeTeleportersFromList(Collection<?> rawTeleporters) {
    if (rawTeleporters == null) {
      return [:]
    }
    Map<Position, Position> normalized = [:]
    rawTeleporters.each { Object item ->
      Map<?, ?> entry = item as Map<?, ?>
      Map<?, ?> from = entry.from as Map<?, ?>
      Map<?, ?> to = entry.to as Map<?, ?>
      Position fromPos = new Position(((Number) from.x).intValue(), ((Number) from.y).intValue())
      Position toPos = new Position(((Number) to.x).intValue(), ((Number) to.y).intValue())
      normalized[fromPos] = toPos
    }
    normalized
  }

  private static IngredientConfig normalizeIngredientConfig(IngredientConfig config) {
    if (config == null) {
      return new IngredientConfig(false, [], 1)
    }
    config
  }

  private static List<Position> normalizePositionList(List<Position> positions) {
    if (positions == null) {
      return []
    }
    positions.findAll { it != null }
  }

  private static List<SpawnerConfig> normalizeSpawners(List<SpawnerConfig> spawners) {
    if (spawners == null) {
      return []
    }
    spawners.findAll { it != null }
  }

  private static IngredientConfig parseIngredientConfig(Map<?, ?> raw) {
    if (raw == null) {
      return null
    }
    boolean enabled = raw.containsKey('enabled') ? Boolean.parseBoolean(raw.enabled.toString()) : false
    int spawnEveryTurns = raw.containsKey('spawnEveryTurns') ? ((Number) raw.spawnEveryTurns).intValue() : 1
    List<IngredientQueueEntry> queue = []
    if (raw.containsKey('queue') && raw.queue instanceof Collection) {
      (raw.queue as Collection<?>).each { Object item ->
        Map<?, ?> entry = item as Map<?, ?>
        IngredientType type = IngredientType.valueOf(entry.type.toString())
        int count = ((Number) entry.count).intValue()
        queue << new IngredientQueueEntry(type, count)
      }
    }
    new IngredientConfig(enabled, queue, spawnEveryTurns)
  }

  private static List<Position> parsePositionList(Collection<?> rawPositions) {
    if (rawPositions == null) {
      return null
    }
    List<Position> positions = []
    rawPositions.each { Object item ->
      Map<?, ?> entry = item as Map<?, ?>
      int x = ((Number) entry.x).intValue()
      int y = ((Number) entry.y).intValue()
      positions << new Position(x, y)
    }
    positions
  }

  private static List<SpawnerConfig> parseSpawnersList(Collection<?> rawSpawners) {
    if (rawSpawners == null) {
      return null
    }
    List<SpawnerConfig> spawners = []
    rawSpawners.each { Object item ->
      Map<?, ?> entry = item as Map<?, ?>
      int x = ((Number) entry.x).intValue()
      int y = ((Number) entry.y).intValue()
      int everyTurns = ((Number) entry.everyTurns).intValue()
      int maxActive = ((Number) entry.maxActive).intValue()
      List<SpawnTableEntry> table = []
      if (entry.containsKey('table') && entry.table instanceof Collection) {
        (entry.table as Collection<?>).each { Object tableItem ->
          Map<?, ?> tableEntry = tableItem as Map<?, ?>
          SpawnKind kind = SpawnKind.valueOf(tableEntry.kind.toString())
          String type = tableEntry.type?.toString()
          int layers = tableEntry.containsKey('layers') ? ((Number) tableEntry.layers).intValue() : 1
          int weight = ((Number) tableEntry.weight).intValue()
          table << new SpawnTableEntry(kind, type, layers, weight)
        }
      }
      spawners << new SpawnerConfig(new Position(x, y), everyTurns, maxActive, table)
    }
    spawners
  }
}
