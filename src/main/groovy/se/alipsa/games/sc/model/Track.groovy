package se.alipsa.games.sc.model

import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.SpecialPieceType

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

  Track(String id,
        String name,
        int width,
        int height,
        int moves,
        int targetScore,
        Map<CandyType, Integer> spawnWeights = null,
        Set<CandyType> scoreColors = null,
        Map<SpecialPieceType, Integer> specialPieces = null) {
    this.id = id
    this.name = name
    this.width = width
    this.height = height
    this.moves = moves
    this.targetScore = targetScore
    this.spawnWeights = Collections.unmodifiableMap(normalizeSpawnWeights(spawnWeights))
    this.scoreColors = Collections.unmodifiableSet(normalizeScoreColors(scoreColors))
    this.specialPieces = Collections.unmodifiableMap(normalizeSpecialPieces(specialPieces))
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

    return new Track(
        data.id?.toString(),
        data.name?.toString(),
        ((Number) data.width).intValue(),
        ((Number) data.height).intValue(),
        ((Number) data.moves).intValue(),
        ((Number) data.targetScore).intValue(),
        weights,
        scoreColors,
        specialPieces
    )
  }

  boolean scoresAllColors() {
    scoreColors.size() == CandyType.values().size()
  }

  int specialPieceBudget(SpecialPieceType type) {
    specialPieces.getOrDefault(type, 0)
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
}
