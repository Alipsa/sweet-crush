package se.alipsa.games.sc.io

import groovy.transform.ToString
import se.alipsa.games.sc.model.Campaign

@ToString
class CampaignLoadResult {
  final Campaign campaign
  final List<LoadError> errors

  CampaignLoadResult(Campaign campaign = null, List<LoadError> errors = []) {
    this.campaign = campaign
    this.errors = List.copyOf(errors)
  }
}
