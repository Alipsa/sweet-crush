package se.alipsa.games.sc.io

import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.IngredientType
import se.alipsa.games.sc.core.SpecialPieceType
import se.alipsa.games.sc.core.BlockerType
import se.alipsa.games.sc.core.FlowDirection
import se.alipsa.games.sc.model.ObjectiveType
import se.alipsa.games.sc.model.SpawnKind

import java.nio.file.Path
import java.util.regex.Pattern

class TrackValidator {

  private static final int MIN_BOARD_SIZE = 3
  private static final int MAX_BOARD_SIZE = 20
  private static final int MAX_ID_LENGTH = 64
  private static final Pattern TRACK_ID_PATTERN = ~/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/

  List<LoadError> validate(Path file, Map<String, ?> rawTrack) {
    List<LoadError> errors = []
    String fileName = file?.toString() ?: '<unknown>'

    validateId(fileName, rawTrack, errors)
    validateName(fileName, rawTrack, errors)
    validateDimensions(fileName, rawTrack, errors)
    validateGameplaySettings(fileName, rawTrack, errors)
    validateBoardMask(fileName, rawTrack, errors)
    validateBoardGeometry(fileName, rawTrack, errors)
    validateSpawnWeights(fileName, rawTrack, errors)
    validateSpecialPieces(fileName, rawTrack, errors)
    validateScoreColors(fileName, rawTrack, errors)
    validateBlockers(fileName, rawTrack, errors)
    validateObjectives(fileName, rawTrack, errors)
    validateIngredients(fileName, rawTrack, errors)
    validateSpawners(fileName, rawTrack, errors)

    return errors
  }

  private static void validateId(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('id')) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Missing required field: id')
      return
    }

    String id = rawTrack.id?.toString()?.trim()
    if (!id) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_TRACK_ID, 'Track id must be non-blank')
      return
    }
    if (id.length() > MAX_ID_LENGTH) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_TRACK_ID, "Track id must be at most ${MAX_ID_LENGTH} characters")
      return
    }
    if (!(id ==~ TRACK_ID_PATTERN)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_TRACK_ID,
          'Track id must start with an alphanumeric character and then use only letters, digits, dot, underscore, or hyphen')
    }
  }

  private static void validateName(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('name')) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Missing required field: name')
      return
    }

    String name = rawTrack.name?.toString()?.trim()
    if (!name) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Track name must be non-blank')
    }
  }

  private static void validateDimensions(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('width')) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Missing required field: width')
    }
    if (!rawTrack.containsKey('height')) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Missing required field: height')
    }

    Integer width = asInteger(rawTrack.width)
    Integer height = asInteger(rawTrack.height)
    if (width == null || height == null) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS,
          'Width and height must be integer values in the allowed range 3-20')
      return
    }

    if (width < MIN_BOARD_SIZE || width > MAX_BOARD_SIZE || height < MIN_BOARD_SIZE || height > MAX_BOARD_SIZE) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS,
          "Board dimensions must be within ${MIN_BOARD_SIZE}-${MAX_BOARD_SIZE}")
    }
  }

  private static void validateGameplaySettings(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('moves')) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Missing required field: moves')
      return
    }
    if (!rawTrack.containsKey('targetScore')) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'Missing required field: targetScore')
      return
    }

    Integer moves = asInteger(rawTrack.moves)
    Integer targetScore = asInteger(rawTrack.targetScore)

    if (moves == null || moves <= 0) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD, 'moves must be an integer greater than zero')
    }
    if (targetScore == null || targetScore <= 0) {
      errors << new LoadError(fileName, LoadErrorCode.MISSING_REQUIRED_FIELD,
          'targetScore must be an integer greater than zero')
    }
  }

  private static void validateSpawnWeights(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('spawnWeights') || rawTrack.spawnWeights == null) {
      return
    }

    if (!(rawTrack.spawnWeights instanceof Map)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWN_WEIGHTS, 'spawnWeights must be a JSON object')
      return
    }

    Map<CandyType, Integer> normalizedWeights = CandyType.values().collectEntries { CandyType type ->
      [(type): 0]
    }

    (rawTrack.spawnWeights as Map<?, ?>).each { Object key, Object value ->
      CandyType type
      try {
        type = CandyType.valueOf(key.toString())
      } catch (IllegalArgumentException ignored) {
        errors << new LoadError(fileName, LoadErrorCode.UNKNOWN_CANDY_TYPE,
            "Unknown candy type in spawnWeights: ${key}")
        return
      }

      Integer intValue = asInteger(value)
      if (intValue == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWN_WEIGHTS,
            "Weight for ${key} must be an integer")
        return
      }
      if (intValue < 0) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWN_WEIGHTS,
            "Weight for ${key} must be non-negative")
        return
      }

      normalizedWeights[type] = intValue
    }

    int totalWeight = normalizedWeights.values().sum() as int
    int positiveCandyTypes = normalizedWeights.values().count { int weight -> weight > 0 }

    if (totalWeight <= 0) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWN_WEIGHTS,
          'spawnWeights must have total positive weight')
    }
    if (positiveCandyTypes < 3) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWN_WEIGHTS,
          'spawnWeights must have at least 3 candy types with positive weight')
    }
  }

  private static void validateScoreColors(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('scoreColors') || rawTrack.scoreColors == null) {
      return
    }

    Object rawScoreColors = rawTrack.scoreColors
    if (rawScoreColors instanceof CharSequence) {
      if (!rawScoreColors.toString().equalsIgnoreCase('ALL')) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SCORING_RULES,
            "scoreColors string value must be 'ALL' or an array of candy type names")
      }
      return
    }

    if (!(rawScoreColors instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SCORING_RULES,
          'scoreColors must be either "ALL" or an array of candy type names')
      return
    }

    Collection<?> colors = rawScoreColors as Collection<?>
    if (colors.isEmpty()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SCORING_RULES,
          'scoreColors array must contain at least one candy type or use "ALL"')
      return
    }

    colors.each { Object value ->
      try {
        CandyType.valueOf(value?.toString())
      } catch (Exception ignored) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SCORING_RULES,
            "Unknown candy type in scoreColors: ${value}")
      }
    }
  }

  private static void validateSpecialPieces(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('specialPieces') || rawTrack.specialPieces == null) {
      return
    }

    if (!(rawTrack.specialPieces instanceof Map)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SPECIAL_PIECES, 'specialPieces must be a JSON object')
      return
    }

    (rawTrack.specialPieces as Map<?, ?>).each { Object key, Object value ->
      try {
        SpecialPieceType.valueOf(key.toString())
      } catch (Exception ignored) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPECIAL_PIECES,
            "Unknown special piece type in specialPieces: ${key}")
        return
      }

      Integer count = asInteger(value)
      if (count == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPECIAL_PIECES,
            "Count for ${key} must be an integer")
        return
      }
      if (count < 0) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPECIAL_PIECES,
            "Count for ${key} must be non-negative")
      }
    }
  }

  private static void validateBlockers(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('blockers') || rawTrack.blockers == null) {
      return
    }
    if (!(rawTrack.blockers instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS, 'blockers must be a JSON array')
      return
    }

    Integer width = asInteger(rawTrack.width)
    Integer height = asInteger(rawTrack.height)
    boolean[][] mask = playableMask(rawTrack, width, height)
    Set<String> seenPositions = new HashSet<>()

    (rawTrack.blockers as Collection<?>).each { Object item ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS, 'Each blocker must be a JSON object')
        return
      }
      Map<?, ?> blocker = item as Map<?, ?>

      Integer x = asInteger(blocker.x)
      Integer y = asInteger(blocker.y)
      Integer layers = asInteger(blocker.layers)
      if (x == null || y == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS, 'Blocker x and y must be integers')
        return
      }
      if (width != null && height != null && (x < 0 || x >= width || y < 0 || y >= height)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS,
            "Blocker position (${x},${y}) is outside board bounds")
      } else if (mask != null && !mask[y][x]) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS,
            "Blocker position (${x},${y}) is on a hole cell in board.mask")
      }

      String positionKey = "${x}:${y}"
      if (!seenPositions.add(positionKey)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS,
            "Duplicate blocker position (${x},${y})")
      }

      if (!blocker.containsKey('type') || blocker.type == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS, 'Blocker type is required')
      } else {
        try {
          BlockerType.valueOf(blocker.type.toString())
        } catch (Exception ignored) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS,
              "Unknown blocker type: ${blocker.type}")
        }
      }

      if (layers == null || layers < 1 || layers > 3) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BLOCKERS,
            'Blocker layers must be an integer between 1 and 3')
      }
    }
  }

  private static void validateObjectives(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('objectives') || rawTrack.objectives == null) {
      return
    }
    if (!(rawTrack.objectives instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES, 'objectives must be a JSON array')
      return
    }

    Collection<?> objectives = rawTrack.objectives as Collection<?>
    if (objectives.isEmpty()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES,
          'objectives array must contain at least one objective')
      return
    }

    objectives.each { Object item ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES, 'Each objective must be a JSON object')
        return
      }
      Map<?, ?> objective = item as Map<?, ?>
      if (!objective.containsKey('type') || objective.type == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES, 'Objective type is required')
        return
      }

      ObjectiveType type
      try {
        type = ObjectiveType.valueOf(objective.type.toString())
      } catch (Exception ignored) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES,
            "Unknown objective type: ${objective.type}")
        return
      }

      Integer target = asInteger(objective.target)
      if (target == null || target <= 0) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES,
            "Objective target must be an integer greater than zero (${objective.type})")
      }

      if (type == ObjectiveType.COLLECT_COLOR) {
        if (!objective.containsKey('color') || objective.color == null) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES,
              'COLLECT_COLOR objective requires color')
        } else {
          try {
            CandyType.valueOf(objective.color.toString())
          } catch (Exception ignored) {
            errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES,
                "Unknown candy type in COLLECT_COLOR objective: ${objective.color}")
          }
        }
      }

      if (type == ObjectiveType.CLEAR_BLOCKER && objective.containsKey('blockerType') && objective.blockerType != null) {
        try {
          BlockerType.valueOf(objective.blockerType.toString())
        } catch (Exception ignored) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_OBJECTIVES,
              "Unknown blocker type in CLEAR_BLOCKER objective: ${objective.blockerType}")
        }
      }
    }
  }

  private static void validateBoardMask(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    Integer width = asInteger(rawTrack.width)
    Integer height = asInteger(rawTrack.height)
    if (width == null || height == null) {
      return
    }

    Object boardObj = rawTrack.containsKey('board') ? rawTrack.board : null
    Object rawMask = null
    if (boardObj instanceof Map && (boardObj as Map<?, ?>).containsKey('mask')) {
      rawMask = (boardObj as Map<?, ?>).mask
    } else if (rawTrack.containsKey('mask')) {
      rawMask = rawTrack.mask
    }

    if (rawMask == null) {
      return
    }
    if (!(rawMask instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS, 'board.mask must be a JSON array of strings')
      return
    }

    Collection<?> rows = rawMask as Collection<?>
    if (rows.size() != height) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS,
          "board.mask must have exactly ${height} rows")
      return
    }

    int playableCount = 0
    rows.eachWithIndex { Object rowObj, int y ->
      String row = rowObj?.toString() ?: ''
      if (row.length() != width) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS,
            "board.mask row ${y} must have width ${width}")
        return
      }
      for (int x = 0; x < row.length(); x++) {
        char cell = row.charAt(x)
        if (cell == '.') {
          playableCount++
        } else if (cell != '#') {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS,
              "board.mask contains invalid cell '${cell}' at (${x},${y}); allowed '.' or '#'")
        }
      }
    }

    if (playableCount == 0) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_DIMENSIONS,
          'board.mask must include at least one playable cell (\'.\')')
    }
  }

  private static void validateBoardGeometry(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!(rawTrack.board instanceof Map)) {
      return
    }

    Integer width = asInteger(rawTrack.width)
    Integer height = asInteger(rawTrack.height)
    if (width == null || height == null) {
      return
    }
    boolean[][] mask = playableMask(rawTrack, width, height)
    Map<?, ?> board = rawTrack.board as Map<?, ?>

    validateOneWayTiles(fileName, board.oneWay, mask, width, height, errors)
    validateTeleporters(fileName, board.teleporters, mask, width, height, errors)
  }

  private static void validateOneWayTiles(String fileName,
                                          Object rawOneWay,
                                          boolean[][] mask,
                                          int width,
                                          int height,
                                          List<LoadError> errors) {
    if (rawOneWay == null) {
      return
    }
    if (!(rawOneWay instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY, 'board.oneWay must be a JSON array')
      return
    }

    Set<String> seenPositions = new HashSet<>()
    (rawOneWay as Collection<?>).each { Object item ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY, 'Each board.oneWay entry must be a JSON object')
        return
      }
      Map<?, ?> entry = item as Map<?, ?>
      Integer x = asInteger(entry.x)
      Integer y = asInteger(entry.y)
      if (x == null || y == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY, 'board.oneWay x and y must be integers')
        return
      }
      if (x < 0 || x >= width || y < 0 || y >= height) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "board.oneWay position (${x},${y}) is outside board bounds")
        return
      }
      if (mask != null && !mask[y][x]) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "board.oneWay position (${x},${y}) is on a hole cell")
        return
      }

      String key = "${x}:${y}"
      if (!seenPositions.add(key)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "Duplicate board.oneWay position (${x},${y})")
      }

      if (!entry.containsKey('direction') || entry.direction == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "board.oneWay direction is required at (${x},${y})")
        return
      }

      try {
        FlowDirection.valueOf(entry.direction.toString())
      } catch (Exception ignored) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "Unknown board.oneWay direction '${entry.direction}' at (${x},${y})")
      }
    }
  }

  private static void validateTeleporters(String fileName,
                                          Object rawTeleporters,
                                          boolean[][] mask,
                                          int width,
                                          int height,
                                          List<LoadError> errors) {
    if (rawTeleporters == null) {
      return
    }
    if (!(rawTeleporters instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY, 'board.teleporters must be a JSON array')
      return
    }

    Set<String> seenFrom = new HashSet<>()
    (rawTeleporters as Collection<?>).each { Object item ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            'Each board.teleporters entry must be a JSON object')
        return
      }
      Map<?, ?> entry = item as Map<?, ?>
      if (!(entry.from instanceof Map) || !(entry.to instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            'board.teleporters entries require from/to objects')
        return
      }

      Map<?, ?> from = entry.from as Map<?, ?>
      Map<?, ?> to = entry.to as Map<?, ?>
      Integer fx = asInteger(from.x)
      Integer fy = asInteger(from.y)
      Integer tx = asInteger(to.x)
      Integer ty = asInteger(to.y)
      if (fx == null || fy == null || tx == null || ty == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            'board.teleporters from/to coordinates must be integers')
        return
      }
      if (fx < 0 || fx >= width || fy < 0 || fy >= height || tx < 0 || tx >= width || ty < 0 || ty >= height) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "board.teleporters from/to must be within board bounds (${fx},${fy}) -> (${tx},${ty})")
        return
      }
      if (mask != null && (!mask[fy][fx] || !mask[ty][tx])) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "board.teleporters from/to must be on playable cells (${fx},${fy}) -> (${tx},${ty})")
        return
      }

      String fromKey = "${fx}:${fy}"
      if (!seenFrom.add(fromKey)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_BOARD_GEOMETRY,
            "Duplicate board.teleporters source (${fx},${fy})")
      }
    }
  }

  private static boolean[][] playableMask(Map<String, ?> rawTrack, Integer width, Integer height) {
    if (width == null || height == null) {
      return null
    }

    Object boardObj = rawTrack.containsKey('board') ? rawTrack.board : null
    Object rawMask = null
    if (boardObj instanceof Map && (boardObj as Map<?, ?>).containsKey('mask')) {
      rawMask = (boardObj as Map<?, ?>).mask
    } else if (rawTrack.containsKey('mask')) {
      rawMask = rawTrack.mask
    }

    boolean[][] mask = new boolean[height][width]
    if (!(rawMask instanceof Collection)) {
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          mask[y][x] = true
        }
      }
      return mask
    }

    List<String> rows = (rawMask as Collection<?>).collect { Object row -> row?.toString() ?: '' }
    if (rows.size() != height || rows.any { String row -> row.length() != width }) {
      return null
    }
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        mask[y][x] = rows[y].charAt(x) == '.'
      }
    }
    return mask
  }

  private static void validateIngredients(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    boolean requiresIngredients = hasDropIngredientObjective(rawTrack)
    if (!rawTrack.containsKey('ingredients') || rawTrack.ingredients == null) {
      if (requiresIngredients) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            'Objective DROP_INGREDIENT requires ingredients.enabled=true and a valid ingredients configuration')
      }
      return
    }
    if (!(rawTrack.ingredients instanceof Map)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS, 'ingredients must be a JSON object')
      return
    }

    Map<?, ?> ingredients = rawTrack.ingredients as Map<?, ?>
    boolean enabled = ingredients.containsKey('enabled') ? Boolean.parseBoolean(ingredients.enabled?.toString()) : false

    if (requiresIngredients && !enabled) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
          'Objective DROP_INGREDIENT requires ingredients.enabled=true and a valid ingredients configuration')
    }

    if (enabled) {
      Integer width = asInteger(rawTrack.width)
      Integer height = asInteger(rawTrack.height)
      boolean[][] mask = playableMask(rawTrack, width, height)

      if (ingredients.containsKey('spawnEveryTurns')) {
        Integer spawnEvery = asInteger(ingredients.spawnEveryTurns)
        if (spawnEvery == null || spawnEvery < 1) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
              'ingredients.spawnEveryTurns must be an integer >= 1')
        }
      }

      if (!ingredients.containsKey('queue') || !(ingredients.queue instanceof Collection) || (ingredients.queue as Collection<?>).isEmpty()) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            'ingredients.queue must be a non-empty array when ingredients are enabled')
      } else {
        (ingredients.queue as Collection<?>).each { Object item ->
          if (!(item instanceof Map)) {
            errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS, 'Each queue entry must be a JSON object')
            return
          }
          Map<?, ?> entry = item as Map<?, ?>
          if (!entry.containsKey('type') || entry.type == null) {
            errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS, 'Queue entry type is required')
            return
          }
          try {
            IngredientType.valueOf(entry.type.toString())
          } catch (Exception ignored) {
            errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
                "Unknown ingredient type: ${entry.type}")
          }
          Integer count = asInteger(entry.count)
          if (count == null || count < 1) {
            errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
                'Queue entry count must be an integer >= 1')
          }
        }
      }

      Object boardObj = rawTrack.containsKey('board') ? rawTrack.board : null
      Map<?, ?> boardConfig = boardObj instanceof Map ? (Map<?, ?>) boardObj : null

      validatePositionCells(fileName, 'spawnCells', boardConfig, mask, width, height, errors, true)
      validatePositionCells(fileName, 'exitCells', boardConfig, mask, width, height, errors, true)
    }
  }

  private static boolean hasDropIngredientObjective(Map<String, ?> rawTrack) {
    if (!(rawTrack.objectives instanceof Collection)) {
      return false
    }
    for (Object objectiveItem : (rawTrack.objectives as Collection<?>)) {
      if (!(objectiveItem instanceof Map)) {
        continue
      }
      Object typeValue = (objectiveItem as Map<?, ?>).type
      if (typeValue == null) {
        continue
      }
      try {
        if (ObjectiveType.valueOf(typeValue.toString()) == ObjectiveType.DROP_INGREDIENT) {
          return true
        }
      } catch (Exception ignored) {
        // Invalid objective types are reported in validateObjectives.
      }
    }
    false
  }

  private static void validatePositionCells(String fileName, String fieldName, Map<?, ?> boardConfig,
                                             boolean[][] mask, Integer width, Integer height,
                                             List<LoadError> errors, boolean required) {
    if (boardConfig == null || !boardConfig.containsKey(fieldName) || boardConfig[fieldName] == null) {
      if (required) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            "board.${fieldName} is required when ingredients are enabled")
      }
      return
    }

    Object raw = boardConfig[fieldName]
    if (!(raw instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
          "board.${fieldName} must be a JSON array")
      return
    }

    Collection<?> cells = raw as Collection<?>
    if (cells.isEmpty()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
          "board.${fieldName} must contain at least one position")
      return
    }

    cells.each { Object item ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            "Each ${fieldName} entry must be a JSON object")
        return
      }
      Map<?, ?> entry = item as Map<?, ?>
      Integer x = asInteger(entry.x)
      Integer y = asInteger(entry.y)
      if (x == null || y == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            "${fieldName} x and y must be integers")
        return
      }
      if (width != null && height != null && (x < 0 || x >= width || y < 0 || y >= height)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            "${fieldName} position (${x},${y}) is outside board bounds")
      } else if (mask != null && !mask[y][x]) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_INGREDIENTS,
            "${fieldName} position (${x},${y}) is on a hole cell")
      }
    }
  }

  private static void validateSpawners(String fileName, Map<String, ?> rawTrack, List<LoadError> errors) {
    if (!rawTrack.containsKey('spawners') || rawTrack.spawners == null) {
      return
    }
    if (!(rawTrack.spawners instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS, 'spawners must be a JSON array')
      return
    }

    Integer width = asInteger(rawTrack.width)
    Integer height = asInteger(rawTrack.height)
    boolean[][] mask = playableMask(rawTrack, width, height)

    Set<String> seenPositions = [] as Set<String>

    (rawTrack.spawners as Collection<?>).each { Object item ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS, 'Each spawner must be a JSON object')
        return
      }
      Map<?, ?> spawner = item as Map<?, ?>

      Integer x = asInteger(spawner.x)
      Integer y = asInteger(spawner.y)
      if (x == null || y == null) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS, 'Spawner x and y must be integers')
        return
      }
      String posKey = "${x},${y}"
      if (!seenPositions.add(posKey)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            "Duplicate spawner position (${x},${y})")
      }
      if (width != null && height != null && (x < 0 || x >= width || y < 0 || y >= height)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            "Spawner position (${x},${y}) is outside board bounds")
      } else if (mask != null && !mask[y][x]) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            "Spawner position (${x},${y}) is on a hole cell")
      }

      Integer everyTurns = asInteger(spawner.everyTurns)
      if (everyTurns == null || everyTurns < 1) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            'Spawner everyTurns must be an integer >= 1')
      }

      Integer maxActive = asInteger(spawner.maxActive)
      if (maxActive == null || maxActive < 1) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            'Spawner maxActive must be an integer >= 1')
      }

      if (!spawner.containsKey('table') || !(spawner.table instanceof Collection)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            'Spawner table is required and must be a JSON array')
        return
      }

      Collection<?> table = spawner.table as Collection<?>
      if (table.isEmpty()) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            'Spawner table must contain at least one entry')
        return
      }

      int totalWeight = 0
      table.each { Object tableItem ->
        if (!(tableItem instanceof Map)) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
              'Each spawner table entry must be a JSON object')
          return
        }
        Map<?, ?> tableEntry = tableItem as Map<?, ?>
        if (!tableEntry.containsKey('kind') || tableEntry.kind == null) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
              'Spawner table entry kind is required')
          return
        }
        SpawnKind kindEnum
        try {
          kindEnum = SpawnKind.valueOf(tableEntry.kind.toString())
        } catch (Exception ignored) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
              "Unknown spawn kind: ${tableEntry.kind}")
          return
        }

        if (!tableEntry.containsKey('type') || tableEntry.type == null) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
              'Spawner table entry type is required')
        } else {
          String typeValue = tableEntry.type.toString()
          if (kindEnum == SpawnKind.BLOCKER) {
            try {
              BlockerType.valueOf(typeValue)
            } catch (Exception ignored) {
              errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
                  "Unknown blocker type in spawner table: ${typeValue}")
            }
            if (tableEntry.containsKey('layers')) {
              Integer layers = asInteger(tableEntry.layers)
              if (layers == null || layers < 1) {
                errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
                    'Spawner table entry layers must be an integer >= 1 for BLOCKER kind')
              }
            }
          } else if (kindEnum == SpawnKind.SPECIAL) {
            try {
              SpecialPieceType.valueOf(typeValue)
            } catch (Exception ignored) {
              errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
                  "Unknown special piece type in spawner table: ${typeValue}")
            }
          }
        }

        Integer weight = asInteger(tableEntry.weight)
        if (weight == null || weight < 1) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
              'Spawner table entry weight must be an integer >= 1')
        } else {
          totalWeight += weight
        }
      }

      if (totalWeight <= 0) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_SPAWNERS,
            'Spawner table must have total positive weight')
      }
    }
  }

  private static Integer asInteger(Object value) {
    if (value == null) {
      return null
    }
    if (value instanceof Number) {
      return ((Number) value).intValue()
    }
    if (value instanceof CharSequence && value.toString().isInteger()) {
      return Integer.parseInt(value.toString())
    }
    return null
  }
}
