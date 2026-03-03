package se.alipsa.games.sc.io

import se.alipsa.games.sc.model.UnlockType

class CampaignValidator {

  List<LoadError> validate(String fileName, Map<String, ?> rawCampaign, Set<String> knownTrackIds) {
    List<LoadError> errors = []

    validateCampaignId(fileName, rawCampaign, errors)
    validateName(fileName, rawCampaign, errors)
    validateChapters(fileName, rawCampaign, knownTrackIds, errors)

    return errors
  }

  private static void validateCampaignId(String fileName, Map<String, ?> rawCampaign, List<LoadError> errors) {
    if (!rawCampaign.containsKey('campaignId') || !rawCampaign.campaignId?.toString()?.trim()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN, 'Missing or blank required field: campaignId')
    }
  }

  private static void validateName(String fileName, Map<String, ?> rawCampaign, List<LoadError> errors) {
    if (!rawCampaign.containsKey('name') || !rawCampaign.name?.toString()?.trim()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN, 'Missing or blank required field: name')
    }
  }

  private static void validateChapters(String fileName, Map<String, ?> rawCampaign,
                                        Set<String> knownTrackIds, List<LoadError> errors) {
    if (!rawCampaign.containsKey('chapters') || rawCampaign.chapters == null) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN, 'Missing required field: chapters')
      return
    }
    if (!(rawCampaign.chapters instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN, 'chapters must be a JSON array')
      return
    }

    Collection<?> chapters = rawCampaign.chapters as Collection<?>
    if (chapters.isEmpty()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN, 'chapters must contain at least one chapter')
      return
    }

    chapters.eachWithIndex { Object item, int chapterIndex ->
      if (!(item instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
            "Chapter at index ${chapterIndex} must be a JSON object")
        return
      }
      Map<?, ?> chapter = item as Map<?, ?>

      if (!chapter.containsKey('id') || !chapter.id?.toString()?.trim()) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
            "Chapter at index ${chapterIndex}: missing or blank id")
      }
      if (!chapter.containsKey('name') || !chapter.name?.toString()?.trim()) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
            "Chapter at index ${chapterIndex}: missing or blank name")
      }

      validateLevels(fileName, chapter, chapterIndex, knownTrackIds, errors)
    }
  }

  private static void validateLevels(String fileName, Map<?, ?> chapter, int chapterIndex,
                                      Set<String> knownTrackIds, List<LoadError> errors) {
    if (!chapter.containsKey('levels') || chapter.levels == null) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
          "Chapter at index ${chapterIndex}: missing required field: levels")
      return
    }
    if (!(chapter.levels instanceof Collection)) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
          "Chapter at index ${chapterIndex}: levels must be a JSON array")
      return
    }

    Collection<?> levels = chapter.levels as Collection<?>
    if (levels.isEmpty()) {
      errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
          "Chapter at index ${chapterIndex}: levels must contain at least one level")
      return
    }

    levels.eachWithIndex { Object levelItem, int levelIndex ->
      if (!(levelItem instanceof Map)) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
            "Chapter ${chapterIndex}, level ${levelIndex}: must be a JSON object")
        return
      }
      Map<?, ?> level = levelItem as Map<?, ?>

      String trackId = level.trackId?.toString()?.trim()
      if (!trackId) {
        errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
            "Chapter ${chapterIndex}, level ${levelIndex}: missing or blank trackId")
      } else if (!knownTrackIds.contains(trackId)) {
        errors << new LoadError(fileName, LoadErrorCode.CAMPAIGN_TRACK_NOT_FOUND,
            "Chapter ${chapterIndex}, level ${levelIndex}: trackId '${trackId}' not found in loaded tracks")
      }

      if (level.containsKey('unlockCondition') && level.unlockCondition != null) {
        if (!(level.unlockCondition instanceof Map)) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
              "Chapter ${chapterIndex}, level ${levelIndex}: unlockCondition must be a JSON object")
          return
        }
        Map<?, ?> condition = level.unlockCondition as Map<?, ?>
        if (!condition.containsKey('type') || condition.type == null) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
              "Chapter ${chapterIndex}, level ${levelIndex}: unlockCondition.type is required")
          return
        }
        try {
          UnlockType unlockType = UnlockType.valueOf(condition.type.toString())
          if (unlockType == UnlockType.STAR_THRESHOLD) {
            Object threshold = condition.starThreshold
            if (threshold == null || !(threshold instanceof Number) || ((Number) threshold).intValue() <= 0) {
              errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
                  "Chapter ${chapterIndex}, level ${levelIndex}: STAR_THRESHOLD requires starThreshold > 0")
            }
          }
        } catch (IllegalArgumentException ignored) {
          errors << new LoadError(fileName, LoadErrorCode.INVALID_CAMPAIGN,
              "Chapter ${chapterIndex}, level ${levelIndex}: unknown unlockCondition.type '${condition.type}'")
        }
      }
    }
  }
}
