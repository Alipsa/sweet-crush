package se.alipsa.games.sc.model

import groovy.transform.Immutable

@Immutable
class Chapter {
  String id
  String name
  String theme
  List<CampaignLevel> levels
}
