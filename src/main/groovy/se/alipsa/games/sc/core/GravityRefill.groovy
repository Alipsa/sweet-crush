package se.alipsa.games.sc.core

class GravityRefill {

  void apply(Board board, Map<CandyType, Integer> spawnWeights, Random random) {
    Map<CandyType, Integer> effectiveWeights = normalizeWeights(spawnWeights)

    for (int x = 0; x < board.width; x++) {
      int writeY = board.height - 1
      for (int y = board.height - 1; y >= 0; y--) {
        Piece cell = board.getPiece(x, y)
        if (cell != null) {
          if (writeY != y) {
            board.setPiece(x, writeY, cell)
            board.setPiece(x, y, null)
          }
          writeY--
        }
      }

      while (writeY >= 0) {
        CandyType nextCandy = pickCandy(effectiveWeights, random, [] as Set<CandyType>)
        board.setPiece(x, writeY, nextCandy == null ? null : Piece.normal(nextCandy))
        writeY--
      }
    }
  }

  static CandyType pickCandy(Map<CandyType, Integer> spawnWeights,
                             Random random,
                             Set<CandyType> forbidden) {
    List<Map.Entry<CandyType, Integer>> allowed = spawnWeights.entrySet()
        .findAll { Map.Entry<CandyType, Integer> entry -> entry.value > 0 && !forbidden.contains(entry.key) }
        .toList()

    if (allowed.isEmpty()) {
      return null
    }

    int totalWeight = allowed.sum { it.value } as int
    int draw = random.nextInt(totalWeight)
    int cumulative = 0

    for (Map.Entry<CandyType, Integer> entry : allowed) {
      cumulative += entry.value
      if (draw < cumulative) {
        return entry.key
      }
    }

    return allowed.last().key
  }

  static Map<CandyType, Integer> normalizeWeights(Map<CandyType, Integer> spawnWeights) {
    if (spawnWeights == null || spawnWeights.isEmpty()) {
      return CandyType.values().collectEntries { CandyType type ->
        [(type): 1]
      } as Map<CandyType, Integer>
    }

    Map<CandyType, Integer> normalized = CandyType.values().collectEntries { CandyType type ->
      [(type): spawnWeights.getOrDefault(type, 0)]
    } as Map<CandyType, Integer>

    if ((normalized.values().sum() as int) <= 0) {
      throw new IllegalArgumentException('spawnWeights must contain at least one positive weight')
    }

    normalized
  }
}
