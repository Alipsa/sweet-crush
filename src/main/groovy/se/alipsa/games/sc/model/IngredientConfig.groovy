package se.alipsa.games.sc.model

import groovy.transform.Immutable

@Immutable
class IngredientConfig {
  boolean enabled
  List<IngredientQueueEntry> queue
  int spawnEveryTurns
}
