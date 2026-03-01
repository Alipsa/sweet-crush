package se.alipsa.games.sc.model

import groovy.transform.Immutable
import se.alipsa.games.sc.core.BlockerType
import se.alipsa.games.sc.core.CandyType

@Immutable
class Objective {
  ObjectiveType type
  int target
  CandyType color
  BlockerType blockerType
}
