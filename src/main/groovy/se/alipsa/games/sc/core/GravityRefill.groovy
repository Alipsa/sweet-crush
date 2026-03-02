package se.alipsa.games.sc.core

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class GravityRefill {

  private static final Logger log = LogManager.getLogger(GravityRefill)

  void apply(Board board, Map<CandyType, Integer> spawnWeights, Random random) {
    Map<CandyType, Integer> effectiveWeights = normalizeWeights(spawnWeights)
    applyWithoutRefill(board)
    applyIngredientGravity(board)
    refillEmptyCells(board, effectiveWeights, random)
  }

  void applyWithoutRefill(Board board) {
    if (board == null) {
      return
    }

    boolean topologyActive = board.hasOneWayTiles() || board.hasTeleporters()
    int maxIterations = Math.max(1, board.width * board.height * 8)
    for (int iteration = 0; iteration < maxIterations; iteration++) {
      int moved = settleOnce(board)
      if (topologyActive && moved > 0 && log.isDebugEnabled()) {
        log.debug('Topology gravity iteration {} moved {} pieces', iteration + 1, moved)
      }
      if (moved == 0) {
        return
      }
    }
  }

  void applyIngredientGravity(Board board) {
    if (board == null) {
      return
    }

    boolean topologyActive = board.hasOneWayTiles() || board.hasTeleporters()
    int maxIterations = Math.max(1, board.width * board.height * 8)
    for (int iteration = 0; iteration < maxIterations; iteration++) {
      int moved = settleIngredientsOnce(board)
      if (topologyActive && moved > 0 && log.isDebugEnabled()) {
        log.debug('Ingredient gravity iteration {} moved {} ingredients', iteration + 1, moved)
      }
      if (moved == 0) {
        return
      }
    }
  }

  private int settleIngredientsOnce(Board board) {
    int movedCount = 0
    Set<Position> movedInto = [] as Set<Position>

    for (int y = board.height - 1; y >= 0; y--) {
      for (int x = 0; x < board.width; x++) {
        if (!board.isPlayable(x, y)) {
          continue
        }

        Position current = new Position(x, y)
        if (movedInto.contains(current)) {
          continue
        }

        Ingredient ingredient = board.getIngredient(x, y)
        if (ingredient == null) {
          continue
        }

        Position next = nextIngredientFallPosition(board, current)
        if (next == null || board.hasIngredient(next.x, next.y)) {
          continue
        }

        board.setIngredient(next.x, next.y, ingredient)
        board.setIngredient(x, y, null)
        movedInto << next
        movedCount++
      }
    }

    movedCount
  }

  private static Position nextIngredientFallPosition(Board board, Position current) {
    Position teleported = board.teleporterTargetAt(current.x, current.y)
    if (teleported != null && !board.hasIngredient(teleported.x, teleported.y)) {
      return teleported
    }

    FlowDirection direction = board.flowDirectionAt(current.x, current.y) ?: FlowDirection.DOWN
    firstOpenPlayableAlongForIngredient(board, current, direction)
  }

  private static Position firstOpenPlayableAlongForIngredient(Board board,
                                                               Position origin,
                                                               FlowDirection direction) {
    if (direction == null) {
      return null
    }

    int x = origin.x + direction.dx
    int y = origin.y + direction.dy
    while (board.inBounds(x, y)) {
      if (board.isPlayable(x, y)) {
        return !board.hasIngredient(x, y) ? new Position(x, y) : null
      }
      x += direction.dx
      y += direction.dy
    }
    null
  }

  private void refillEmptyCells(Board board,
                                Map<CandyType, Integer> spawnWeights,
                                Random random) {
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (!board.isPlayable(x, y) || board.getPiece(x, y) != null) {
          continue
        }
        CandyType nextCandy = pickCandy(spawnWeights, random, [] as Set<CandyType>)
        board.setPiece(x, y, nextCandy == null ? null : Piece.normal(nextCandy))
      }
    }
  }

  private int settleOnce(Board board) {
    int movedCount = 0
    Set<Position> movedInto = [] as Set<Position>

    for (int y = board.height - 1; y >= 0; y--) {
      for (int x = 0; x < board.width; x++) {
        if (!board.isPlayable(x, y)) {
          continue
        }

        Position current = new Position(x, y)
        if (movedInto.contains(current)) {
          continue
        }

        Piece piece = board.getPiece(x, y)
        if (piece == null) {
          continue
        }

        Position next = nextFallPosition(board, current)
        if (next == null || board.getPiece(next.x, next.y) != null) {
          continue
        }

        board.setPiece(next.x, next.y, piece)
        board.setPiece(x, y, null)
        movedInto << next
        movedCount++
      }
    }

    movedCount
  }

  private static Position nextFallPosition(Board board, Position current) {
    Position teleported = board.teleporterTargetAt(current.x, current.y)
    if (teleported != null && board.getPiece(teleported.x, teleported.y) == null) {
      return teleported
    }

    FlowDirection direction = board.flowDirectionAt(current.x, current.y) ?: FlowDirection.DOWN
    firstOpenPlayableAlong(board, current, direction)
  }

  private static Position firstOpenPlayableAlong(Board board,
                                                 Position origin,
                                                 FlowDirection direction) {
    if (direction == null) {
      return null
    }

    int x = origin.x + direction.dx
    int y = origin.y + direction.dy
    while (board.inBounds(x, y)) {
      if (board.isPlayable(x, y)) {
        return board.getPiece(x, y) == null ? new Position(x, y) : null
      }
      x += direction.dx
      y += direction.dy
    }
    null
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
