package se.alipsa.games.sc.model

class CampaignProgress {
  String campaignId
  Map<String, LevelProgress> levelProgress

  CampaignProgress(String campaignId, Map<String, LevelProgress> levelProgress = [:]) {
    this.campaignId = campaignId
    this.levelProgress = levelProgress
  }

  int getStars(String trackId) {
    levelProgress[trackId]?.stars ?: 0
  }

  boolean isCompleted(String trackId) {
    levelProgress[trackId]?.completed ?: false
  }

  int totalStars() {
    if (levelProgress.isEmpty()) {
      return 0
    }
    levelProgress.values().sum { LevelProgress lp -> lp.stars } as int
  }

  void recordWin(String trackId, int score, int stars) {
    LevelProgress existing = levelProgress[trackId]
    if (existing == null) {
      levelProgress[trackId] = new LevelProgress(trackId, true, stars, score)
    } else {
      existing.completed = true
      if (stars > existing.stars) {
        existing.stars = stars
      }
      if (score > existing.bestScore) {
        existing.bestScore = score
      }
    }
  }
}
