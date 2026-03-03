package se.alipsa.games.sc.model

import groovy.transform.Immutable

@Immutable
class CampaignLevel {
  String trackId
  UnlockCondition unlockCondition
}
