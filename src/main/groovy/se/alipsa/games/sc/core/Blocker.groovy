package se.alipsa.games.sc.core

import java.util.Objects

final class Blocker {

  final BlockerType type
  final int layers

  Blocker(BlockerType type, int layers) {
    this.type = Objects.requireNonNull(type, 'type')
    this.layers = Math.max(1, layers)
  }

  Blocker hit() {
    if (layers <= 1) {
      return null
    }
    return new Blocker(type, layers - 1)
  }
}
