package se.alipsa.games.sc.core

import spock.lang.Specification

class GravityRefillTest extends Specification {

  def 'masked gravity preserves holes and drops pieces only into playable cells'() {
    given:
    boolean[][] mask = [
        [true, true, true] as boolean[],
        [true, false, true] as boolean[],
        [true, true, true] as boolean[],
        [true, false, true] as boolean[],
        [true, true, true] as boolean[]
    ] as boolean[][]
    Board board = new Board(3, 5, mask)
    board.setCell(1, 0, CandyType.RED)
    board.setCell(1, 2, CandyType.BLUE)
    board.setCell(1, 4, CandyType.GREEN)

    GravityRefill refill = new GravityRefill()

    when:
    refill.apply(board, uniformWeights(), new Random(42L))

    then:
    !board.isPlayable(1, 1)
    !board.isPlayable(1, 3)
    board.getPiece(1, 1) == null
    board.getPiece(1, 3) == null
    board.getPiece(1, 4) != null
    board.getPiece(1, 2) != null
    board.getPiece(1, 0) != null
  }

  def 'one-way tile redirects gravity flow in tile direction'() {
    given:
    Board board = new Board(
        3,
        3,
        null,
        [(new Position(1, 1)): FlowDirection.RIGHT],
        [:]
    )
    board.setCell(1, 1, CandyType.RED)
    board.setCell(2, 2, CandyType.BLUE)
    GravityRefill refill = new GravityRefill()

    when:
    refill.applyWithoutRefill(board)

    then:
    board.getCell(1, 1) == null
    board.getCell(2, 1) == CandyType.RED
    board.getCell(2, 2) == CandyType.BLUE
  }

  def 'teleporter moves piece to linked destination during gravity'() {
    given:
    Board board = new Board(
        3,
        4,
        null,
        [:],
        [(new Position(1, 1)): new Position(0, 3)]
    )
    board.setCell(1, 1, CandyType.GREEN)
    GravityRefill refill = new GravityRefill()

    when:
    refill.applyWithoutRefill(board)

    then:
    board.getCell(1, 1) == null
    board.getCell(0, 3) == CandyType.GREEN
  }

  private static Map<CandyType, Integer> uniformWeights() {
    CandyType.values().collectEntries { CandyType type ->
      [(type): 1]
    } as Map<CandyType, Integer>
  }
}
