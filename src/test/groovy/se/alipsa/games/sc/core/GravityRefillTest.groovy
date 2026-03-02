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

  def 'ingredient falls down via gravity'() {
    given:
    Board board = new Board(3, 4)
    fillBoard(board)
    board.setIngredient(1, 0, new Ingredient(IngredientType.CHERRY))
    GravityRefill refill = new GravityRefill()

    when:
    refill.applyIngredientGravity(board)

    then:
    board.hasIngredient(1, 3)
    board.getIngredient(1, 3).type == IngredientType.CHERRY
    !board.hasIngredient(1, 0)
  }

  def 'ingredient follows teleporter during gravity'() {
    given:
    Board board = new Board(
        3,
        4,
        null,
        [:],
        [(new Position(1, 1)): new Position(0, 3)]
    )
    fillBoard(board)
    board.setIngredient(1, 0, new Ingredient(IngredientType.NUT))
    GravityRefill refill = new GravityRefill()

    when:
    refill.applyIngredientGravity(board)

    then:
    board.hasIngredient(0, 3)
    board.getIngredient(0, 3).type == IngredientType.NUT
    !board.hasIngredient(1, 0)
  }

  def 'ingredient follows one-way tile direction during gravity'() {
    given:
    Board board = new Board(
        3,
        3,
        null,
        [(new Position(1, 1)): FlowDirection.RIGHT],
        [:]
    )
    fillBoard(board)
    board.setIngredient(1, 0, new Ingredient(IngredientType.CHERRY))
    GravityRefill refill = new GravityRefill()

    when:
    refill.applyIngredientGravity(board)

    then: 'ingredient falls to (1,1), redirects RIGHT to (2,1), then falls DOWN to (2,2)'
    board.hasIngredient(2, 2)
    !board.hasIngredient(1, 0)
    !board.hasIngredient(1, 1)
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

  private static void fillBoard(Board board) {
    CandyType[] colors = CandyType.values()
    int idx = 0
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (board.isPlayable(x, y)) {
          board.setCell(x, y, colors[idx % colors.length])
          idx++
        }
      }
    }
  }

  private static Map<CandyType, Integer> uniformWeights() {
    CandyType.values().collectEntries { CandyType type ->
      [(type): 1]
    } as Map<CandyType, Integer>
  }
}
