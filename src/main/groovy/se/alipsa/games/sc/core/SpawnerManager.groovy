package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.SpawnKind
import se.alipsa.games.sc.model.SpawnTableEntry
import se.alipsa.games.sc.model.SpawnerConfig

class SpawnerManager {

  private final List<SpawnerConfig> spawners
  private final Random random
  private final Map<Position, Integer> turnCounters = [:]
  private final Map<Position, List<SpawnRecord>> activeSpawns = [:]

  SpawnerManager(List<SpawnerConfig> spawners, Random random) {
    this.spawners = spawners ?: []
    this.random = random ?: new Random()
    this.spawners.each { SpawnerConfig config ->
      turnCounters[config.position] = 0
      activeSpawns[config.position] = []
    }
  }

  List<SpawnEvent> processAfterMove(Board board) {
    List<SpawnEvent> events = []
    spawners.each { SpawnerConfig config ->
      Position pos = config.position
      int counter = (turnCounters[pos] ?: 0) + 1
      turnCounters[pos] = counter

      if (config.everyTurns <= 0 || counter % config.everyTurns != 0) {
        return
      }

      List<SpawnRecord> records = activeSpawns.getOrDefault(pos, [])
      records = records.findAll { SpawnRecord record -> isStillActive(board, record) }
      activeSpawns[pos] = records

      if (records.size() >= config.maxActive) {
        return
      }

      if (!board.inBounds(pos.x, pos.y) || !board.isPlayable(pos.x, pos.y)) {
        return
      }

      SpawnTableEntry selected = selectWeighted(config.table)
      if (selected == null) {
        return
      }

      SpawnEvent event = applySpawn(board, pos, selected)
      if (event != null) {
        records << new SpawnRecord(pos, selected.kind)
        events << event
      }
    }
    events
  }

  private static boolean isStillActive(Board board, SpawnRecord record) {
    if (!board.inBounds(record.position.x, record.position.y)) {
      return false
    }
    if (record.kind == SpawnKind.BLOCKER) {
      return board.getBlocker(record.position.x, record.position.y) != null
    } else if (record.kind == SpawnKind.SPECIAL) {
      Piece piece = board.getPiece(record.position.x, record.position.y)
      return piece != null && piece.isSpecial()
    }
    false
  }

  private SpawnTableEntry selectWeighted(List<SpawnTableEntry> table) {
    if (table == null || table.isEmpty()) {
      return null
    }

    int totalWeight = table.sum { it.weight } as int
    if (totalWeight <= 0) {
      return null
    }

    int draw = random.nextInt(totalWeight)
    int cumulative = 0
    for (SpawnTableEntry entry : table) {
      cumulative += entry.weight
      if (draw < cumulative) {
        return entry
      }
    }
    table.last()
  }

  private static SpawnEvent applySpawn(Board board, Position pos, SpawnTableEntry entry) {
    if (entry.kind == SpawnKind.BLOCKER) {
      if (board.getBlocker(pos.x, pos.y) != null) {
        return null
      }
      BlockerType blockerType = BlockerType.valueOf(entry.type)
      board.setBlocker(pos.x, pos.y, new Blocker(blockerType, entry.layers))
      return new SpawnEvent(pos, entry.kind, entry.type)
    } else if (entry.kind == SpawnKind.SPECIAL) {
      Piece existing = board.getPiece(pos.x, pos.y)
      if (existing == null || existing.isSpecial()) {
        return null
      }
      SpecialPieceType specialType = SpecialPieceType.valueOf(entry.type)
      Piece specialPiece
      switch (specialType) {
        case SpecialPieceType.SWEEPER:
          specialPiece = Piece.sweeper(existing.color, true)
          break
        case SpecialPieceType.SMALL_BOMB:
          specialPiece = Piece.smallBomb(existing.color)
          break
        case SpecialPieceType.BOMB:
          specialPiece = Piece.bomb(existing.color)
          break
        case SpecialPieceType.FISH:
          specialPiece = Piece.fish(existing.color)
          break
        default:
          return null
      }
      board.setPiece(pos.x, pos.y, specialPiece)
      return new SpawnEvent(pos, entry.kind, entry.type)
    }
    null
  }

  private static final class SpawnRecord {
    final Position position
    final SpawnKind kind

    SpawnRecord(Position position, SpawnKind kind) {
      this.position = position
      this.kind = kind
    }
  }

  static final class SpawnEvent {
    final Position position
    final SpawnKind kind
    final String type

    SpawnEvent(Position position, SpawnKind kind, String type) {
      this.position = position
      this.kind = kind
      this.type = type
    }
  }
}
