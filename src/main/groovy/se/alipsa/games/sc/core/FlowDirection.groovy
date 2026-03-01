package se.alipsa.games.sc.core

enum FlowDirection {
  UP(0, -1),
  RIGHT(1, 0),
  DOWN(0, 1),
  LEFT(-1, 0)

  final int dx
  final int dy

  FlowDirection(int dx, int dy) {
    this.dx = dx
    this.dy = dy
  }
}
