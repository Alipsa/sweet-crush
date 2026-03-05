package se.alipsa.games.sc.core

import se.alipsa.games.sc.io.ProgressStore
import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.CampaignLevel
import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.Chapter
import se.alipsa.games.sc.model.UnlockType

class CampaignService {

  private final Campaign campaign
  private final ProgressStore store

  CampaignService(Campaign campaign, ProgressStore store) {
    this.campaign = campaign
    this.store = store
  }

  Campaign getCampaign() {
    campaign
  }

  CampaignProgress loadProgress() {
    store.load(campaign.campaignId)
  }

  void recordWin(String trackId, int score, int targetScore, CampaignProgress progress) {
    int stars = calculateStars(score, targetScore)
    progress.recordWin(trackId, score, stars)
    store.save(progress)
  }

  boolean isUnlocked(CampaignLevel level, CampaignProgress progress) {
    if (isFirstLevelOfFirstChapter(level)) {
      return true
    }

    switch (level.unlockCondition.type) {
      case UnlockType.NONE:
        return true
      case UnlockType.PREVIOUS_LEVEL_WIN:
        CampaignLevel previous = findPreviousLevel(level)
        return previous == null || progress.isCompleted(previous.trackId)
      case UnlockType.STAR_THRESHOLD:
        return progress.totalStars() >= level.unlockCondition.starThreshold
      default:
        return false
    }
  }

  static int calculateStars(int score, int targetScore) {
    if (targetScore <= 0 || score < targetScore) {
      return 0
    }
    if (score >= (int) (targetScore * 2.0)) {
      return 3
    }
    if (score >= (int) (targetScore * 1.5)) {
      return 2
    }
    return 1
  }

  private boolean isFirstLevelOfFirstChapter(CampaignLevel level) {
    if (campaign.chapters.isEmpty()) {
      return false
    }
    Chapter firstChapter = campaign.chapters[0]
    if (firstChapter.levels.isEmpty()) {
      return false
    }
    return firstChapter.levels[0].trackId == level.trackId
  }

  CampaignLevel findNextLevel(String trackId) {
    boolean found = false
    for (Chapter chapter : campaign.chapters) {
      for (CampaignLevel level : chapter.levels) {
        if (found) {
          return level
        }
        if (level.trackId == trackId) {
          found = true
        }
      }
    }
    return null
  }

  private CampaignLevel findPreviousLevel(CampaignLevel level) {
    for (Chapter chapter : campaign.chapters) {
      for (int i = 0; i < chapter.levels.size(); i++) {
        if (chapter.levels[i].trackId == level.trackId) {
          if (i > 0) {
            return chapter.levels[i - 1]
          }
          int chapterIndex = campaign.chapters.indexOf(chapter)
          if (chapterIndex > 0) {
            Chapter prevChapter = campaign.chapters[chapterIndex - 1]
            if (!prevChapter.levels.isEmpty()) {
              return prevChapter.levels.last()
            }
          }
          return null
        }
      }
    }
    return null
  }
}
