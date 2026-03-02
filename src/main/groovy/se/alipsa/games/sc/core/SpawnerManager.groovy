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
    Set<Piece> activeSpecialPieces = collectActiveSpecialPieces(board)
    spawners.each { SpawnerConfig config ->
      Position pos = config.position
      int counter = (turnCounters[pos] ?: 0) + 1
      turnCounters[pos] = counter

      if (config.everyTurns <= 0 || counter % config.everyTurns != 0) {
        return
      }

      List<SpawnRecord> records = activeSpawns.getOrDefault(pos, [])
      records = records.findAll { SpawnRecord record -> isStillActive(board, record, activeSpecialPieces) }
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

      SpawnResult result = applySpawn(board, pos, selected)
      if (result != null) {
        records << result.record
        events << result.event
      }
    }
    events
  }

  private static boolean isStillActive(Board board, SpawnRecord record, Set<Piece> activeSpecialPieces) {
    if (!board.inBounds(record.position.x, record.position.y)) {
      return false
    }
    if (record.kind == SpawnKind.BLOCKER) {
      return board.getBlocker(record.position.x, record.position.y) != null
    } else if (record.kind == SpawnKind.SPECIAL) {
      return record.spawnedPiece != null && activeSpecialPieces.contains(record.spawnedPiece)
    }
    false
  }

  private static Set<Piece> collectActiveSpecialPieces(Board board) {
    Set<Piece> active = Collections.newSetFromMap(new IdentityHashMap<Piece, Boolean>())
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (!board.isPlayable(x, y)) {
          continue
        }
        Piece piece = board.getPiece(x, y)
        if (piece != null && piece.isSpecial()) {
          active.add(piece)
        }
      }
    }
    active
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

  private static SpawnResult applySpawn(Board board, Position pos, SpawnTableEntry entry) {
    if (entry.kind == SpawnKind.BLOCKER) {
      if (board.getBlocker(pos.x, pos.y) != null) {
        return null
      }
      BlockerType blockerType = BlockerType.valueOf(entry.type)
      board.setBlocker(pos.x, pos.y, new Blocker(blockerType, entry.layers))
      return new SpawnResult(
          new SpawnEvent(pos, entry.kind, entry.type),
          SpawnRecord.blocker(pos)
      )
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
      return new SpawnResult(
          new SpawnEvent(pos, entry.kind, entry.type),
          SpawnRecord.special(pos, specialPiece)
      )
    }
    null
  }

  private static final class SpawnRecord {
    final Position position
    final SpawnKind kind
    final Piece spawnedPiece

    private SpawnRecord(Position position, SpawnKind kind, Piece spawnedPiece = null) {
      this.position = position
      this.kind = kind
      this.spawnedPiece = spawnedPiece
    }

    static SpawnRecord blocker(Position position) {
      new SpawnRecord(position, SpawnKind.BLOCKER)
    }

    static SpawnRecord special(Position position, Piece spawnedPiece) {
      new SpawnRecord(position, SpawnKind.SPECIAL, spawnedPiece)
    }
  }

  private static final class SpawnResult {
    final SpawnEvent event
    final SpawnRecord record

    SpawnResult(SpawnEvent event, SpawnRecord record) {
      this.event = event
      this.record = record
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
