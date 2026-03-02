package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.IngredientConfig
import se.alipsa.games.sc.model.IngredientQueueEntry
import spock.lang.Specification

class IngredientManagerTest extends Specification {

  def 'spawns ingredient at spawn cell on cadence'() {
    given:
    IngredientConfig config = new IngredientConfig(true, [
        new IngredientQueueEntry(IngredientType.CHERRY, 3)
    ], 1)
    List<Position> spawnCells = [new Position(2, 0)]
    List<Position> exitCells = [new Position(2, 4)]
    IngredientManager manager = new IngredientManager(config, spawnCells, exitCells)
    Board board = new Board(5, 5)
    fillBoard(board)

    when:
    manager.processAfterMove(board)

    then:
    board.hasIngredient(2, 0)
    board.getIngredient(2, 0).type == IngredientType.CHERRY
    board.getPiece(2, 0) == null
  }

  def 'collects ingredient at exit cell'() {
    given:
    IngredientConfig config = new IngredientConfig(true, [
        new IngredientQueueEntry(IngredientType.NUT, 2)
    ], 1)
    List<Position> spawnCells = [new Position(0, 0)]
    List<Position> exitCells = [new Position(2, 4)]
    IngredientManager manager = new IngredientManager(config, spawnCells, exitCells)
    Board board = new Board(5, 5)
    fillBoard(board)
    board.setIngredient(2, 4, new Ingredient(IngredientType.NUT))

    when:
    List<IngredientManager.CollectedIngredient> collected = manager.processAfterMove(board)

    then:
    collected.size() == 1
    collected[0].type == IngredientType.NUT
    collected[0].exitCell == new Position(2, 4)
    !board.hasIngredient(2, 4)
    manager.ingredientsCollected == 1
  }

  def 'respects spawn cadence'() {
    given:
    IngredientConfig config = new IngredientConfig(true, [
        new IngredientQueueEntry(IngredientType.CHERRY, 5)
    ], 3)
    List<Position> spawnCells = [new Position(0, 0)]
    List<Position> exitCells = []
    IngredientManager manager = new IngredientManager(config, spawnCells, exitCells)
    Board board = new Board(3, 3)
    fillBoard(board)

    when:
    manager.processAfterMove(board)

    then:
    !board.hasIngredient(0, 0)

    when:
    manager.processAfterMove(board)

    then:
    !board.hasIngredient(0, 0)

    when:
    manager.processAfterMove(board)

    then:
    board.hasIngredient(0, 0)
  }

  def 'does not spawn when queue is exhausted'() {
    given:
    IngredientConfig config = new IngredientConfig(true, [
        new IngredientQueueEntry(IngredientType.CHERRY, 1)
    ], 1)
    List<Position> spawnCells = [new Position(0, 0)]
    List<Position> exitCells = []
    IngredientManager manager = new IngredientManager(config, spawnCells, exitCells)
    Board board = new Board(3, 3)
    fillBoard(board)

    when:
    manager.processAfterMove(board)

    then:
    board.hasIngredient(0, 0)

    when:
    board.removeIngredient(0, 0)
    manager.processAfterMove(board)

    then:
    !board.hasIngredient(0, 0)
    manager.queueExhausted
  }

  def 'skips spawn when spawn cell is occupied by ingredient'() {
    given:
    IngredientConfig config = new IngredientConfig(true, [
        new IngredientQueueEntry(IngredientType.CHERRY, 3)
    ], 1)
    List<Position> spawnCells = [new Position(0, 0)]
    List<Position> exitCells = []
    IngredientManager manager = new IngredientManager(config, spawnCells, exitCells)
    Board board = new Board(3, 3)
    fillBoard(board)
    board.setIngredient(0, 0, new Ingredient(IngredientType.NUT))

    when:
    manager.processAfterMove(board)

    then:
    board.getIngredient(0, 0).type == IngredientType.NUT
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
