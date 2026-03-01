package se.alipsa.games.sc.io

import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.SpecialPieceType

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
    validateSpawnWeights(fileName, rawTrack, errors)
    validateSpecialPieces(fileName, rawTrack, errors)
    validateScoreColors(fileName, rawTrack, errors)

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
