package se.alipsa.games.sc.model

import groovy.transform.Immutable
import se.alipsa.games.sc.core.IngredientType

@Immutable
class IngredientQueueEntry {
  IngredientType type
  int count
}
