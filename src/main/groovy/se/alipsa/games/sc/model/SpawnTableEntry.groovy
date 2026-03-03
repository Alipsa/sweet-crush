package se.alipsa.games.sc.model

import groovy.transform.Immutable

@Immutable
class SpawnTableEntry {
  SpawnKind kind
  String type
  int layers
  int weight
}
