package se.alipsa.games.sc.core

import java.util.concurrent.Future

interface GameSession {

  Board snapshotBoard()

  boolean isResolving()

  Future<Boolean> submitSwap(int x1, int y1, int x2, int y2)
}
