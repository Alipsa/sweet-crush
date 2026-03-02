package se.alipsa.games.sc.model

import groovy.transform.Immutable
import se.alipsa.games.sc.core.Position

@Immutable
class SpawnerConfig {
  Position position
  int everyTurns
  int maxActive
  List<SpawnTableEntry> table
}
