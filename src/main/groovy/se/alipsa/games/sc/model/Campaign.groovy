package se.alipsa.games.sc.model

import groovy.transform.Immutable

@Immutable
class Campaign {
  String campaignId
  String name
  List<Chapter> chapters
}
