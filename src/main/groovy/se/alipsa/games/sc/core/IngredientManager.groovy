package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.IngredientConfig
import se.alipsa.games.sc.model.IngredientQueueEntry

class IngredientManager {

  private final List<IngredientType> flatQueue
  private final List<Position> spawnCells
  private final List<Position> exitCells
  private final int spawnEveryTurns
  private int turnCounter = 0
  private int queueIndex = 0
  private int ingredientsCollected = 0

  IngredientManager(IngredientConfig config, List<Position> spawnCells, List<Position> exitCells) {
    this.spawnCells = spawnCells ?: []
    this.exitCells = exitCells ?: []
    this.spawnEveryTurns = config?.spawnEveryTurns ?: 1
    this.flatQueue = flattenQueue(config?.queue ?: [])
  }

  int getIngredientsCollected() {
    ingredientsCollected
  }

  boolean isQueueExhausted() {
    queueIndex >= flatQueue.size()
  }

  List<CollectedIngredient> processAfterMove(Board board) {
    List<CollectedIngredient> collected = collectAtExitCells(board)
    turnCounter++
    if (spawnEveryTurns > 0 && turnCounter % spawnEveryTurns == 0) {
      spawnNextIngredient(board)
    }
    collected
  }

  List<CollectedIngredient> collectAtExitCells(Board board) {
    List<CollectedIngredient> collected = []
    exitCells.each { Position exitCell ->
      if (!board.inBounds(exitCell.x, exitCell.y)) {
        return
      }
      Ingredient ingredient = board.removeIngredient(exitCell.x, exitCell.y)
      if (ingredient != null) {
        ingredientsCollected++
        collected << new CollectedIngredient(ingredient.type, exitCell)
      }
    }
    collected
  }

  boolean spawnNextIngredient(Board board) {
    if (queueIndex >= flatQueue.size()) {
      return false
    }

    Position spawnCell = spawnCells.find { Position pos ->
      board.inBounds(pos.x, pos.y) && board.isPlayable(pos.x, pos.y) && !board.hasIngredient(pos.x, pos.y)
    }
    if (spawnCell == null) {
      return false
    }

    IngredientType type = flatQueue[queueIndex]
    board.setIngredient(spawnCell.x, spawnCell.y, new Ingredient(type))
    queueIndex++
    true
  }

  private static List<IngredientType> flattenQueue(List<IngredientQueueEntry> queue) {
    List<IngredientType> flat = []
    queue.each { IngredientQueueEntry entry ->
      for (int i = 0; i < entry.count; i++) {
        flat << entry.type
      }
    }
    flat
  }

  static final class CollectedIngredient {
    final IngredientType type
    final Position exitCell

    CollectedIngredient(IngredientType type, Position exitCell) {
      this.type = type
      this.exitCell = exitCell
    }
  }
}
