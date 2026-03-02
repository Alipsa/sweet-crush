package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.SpawnKind
import se.alipsa.games.sc.model.SpawnTableEntry
import se.alipsa.games.sc.model.SpawnerConfig
import spock.lang.Specification

class SpawnerManagerTest extends Specification {

  def 'spawns blocker at configured cadence'() {
    given:
    SpawnerConfig config = new SpawnerConfig(
        new Position(1, 1),
        2,
        3,
        [new SpawnTableEntry(SpawnKind.BLOCKER, 'JELLY', 1, 1)]
    )
    SpawnerManager manager = new SpawnerManager([config], new Random(42L))
    Board board = new Board(3, 3)
    fillBoard(board)

    when:
    List<SpawnerManager.SpawnEvent> events1 = manager.processAfterMove(board)

    then:
    events1.isEmpty()
    board.getBlocker(1, 1) == null

    when:
    List<SpawnerManager.SpawnEvent> events2 = manager.processAfterMove(board)

    then:
    events2.size() == 1
    events2[0].kind == SpawnKind.BLOCKER
    events2[0].type == 'JELLY'
    board.getBlocker(1, 1) != null
    board.getBlocker(1, 1).type == BlockerType.JELLY
  }

  def 'respects maxActive guard and does not spawn beyond limit'() {
    given:
    SpawnerConfig config = new SpawnerConfig(
        new Position(1, 1),
        1,
        1,
        [new SpawnTableEntry(SpawnKind.BLOCKER, 'CRATE', 1, 1)]
    )
    SpawnerManager manager = new SpawnerManager([config], new Random(42L))
    Board board = new Board(3, 3)
    fillBoard(board)

    when: 'first turn spawns a blocker'
    List<SpawnerManager.SpawnEvent> events1 = manager.processAfterMove(board)

    then:
    events1.size() == 1
    board.getBlocker(1, 1) != null

    when: 'second turn skips because maxActive reached and blocker still present'
    List<SpawnerManager.SpawnEvent> events2 = manager.processAfterMove(board)

    then:
    events2.isEmpty()
    board.getBlocker(1, 1) != null
  }

  def 'resumes spawning after spawned entity is removed from board'() {
    given:
    SpawnerConfig config = new SpawnerConfig(
        new Position(1, 1),
        1,
        1,
        [new SpawnTableEntry(SpawnKind.BLOCKER, 'CRATE', 1, 1)]
    )
    SpawnerManager manager = new SpawnerManager([config], new Random(42L))
    Board board = new Board(3, 3)
    fillBoard(board)

    when: 'first turn spawns a blocker'
    manager.processAfterMove(board)

    then:
    board.getBlocker(1, 1) != null

    when: 'blocker is cleared from the board, then next turn triggers'
    board.setBlocker(1, 1, null)
    List<SpawnerManager.SpawnEvent> events = manager.processAfterMove(board)

    then: 'spawner detects removal and spawns again'
    events.size() == 1
    board.getBlocker(1, 1) != null
    board.getBlocker(1, 1).type == BlockerType.CRATE
  }

  def 'spawns special piece by upgrading existing candy'() {
    given:
    SpawnerConfig config = new SpawnerConfig(
        new Position(1, 1),
        1,
        5,
        [new SpawnTableEntry(SpawnKind.SPECIAL, 'FISH', 1, 1)]
    )
    SpawnerManager manager = new SpawnerManager([config], new Random(42L))
    Board board = new Board(3, 3)
    fillBoard(board)

    when:
    List<SpawnerManager.SpawnEvent> events = manager.processAfterMove(board)

    then:
    events.size() == 1
    events[0].kind == SpawnKind.SPECIAL
    board.getPiece(1, 1).isSpecial()
    board.getPiece(1, 1).specialType == SpecialPieceType.FISH
  }

  def 'does not spawn blocker when cell already has blocker'() {
    given:
    SpawnerConfig config = new SpawnerConfig(
        new Position(1, 1),
        1,
        5,
        [new SpawnTableEntry(SpawnKind.BLOCKER, 'JELLY', 1, 1)]
    )
    SpawnerManager manager = new SpawnerManager([config], new Random(42L))
    Board board = new Board(3, 3)
    fillBoard(board)
    board.setBlocker(1, 1, new Blocker(BlockerType.ICE, 2))

    when:
    List<SpawnerManager.SpawnEvent> events = manager.processAfterMove(board)

    then:
    events.isEmpty()
    board.getBlocker(1, 1).type == BlockerType.ICE
  }

  private static void fillBoard(Board board) {
    CandyType[] colors = CandyType.values()
    int idx = 0
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        board.setCell(x, y, colors[idx % colors.length])
        idx++
      }
    }
  }
}
