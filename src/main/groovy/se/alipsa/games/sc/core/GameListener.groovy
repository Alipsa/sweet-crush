package se.alipsa.games.sc.core

interface GameListener {

  void onBoardUpdated(Board board)

  void onScoreChanged(int score)

  void onGameOver(GameOutcome outcome, int finalScore, int movesLeft)

  void onReshuffleExhausted()

  default void onSpecialActivated(SpecialPieceType specialType, Position origin, boolean sweeperHorizontal) {
  }

  default void onFishLaunched(Position origin, Position target) {
  }

  default void onBombBeam(Position origin, Position target) {
  }

  default void onIngredientCollected(IngredientType type, Position exitCell) {
  }

  default void onSpawnerActivated(Position position, se.alipsa.games.sc.model.SpawnKind kind, String type) {
  }
}
