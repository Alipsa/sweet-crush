package se.alipsa.games.sc.model

import groovy.transform.Immutable

@Immutable
class UnlockCondition {
  UnlockType type
  int starThreshold
}
