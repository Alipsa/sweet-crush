package se.alipsa.games.sc.io

import groovy.json.JsonSlurper
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.CampaignLevel
import se.alipsa.games.sc.model.Chapter
import se.alipsa.games.sc.model.UnlockCondition
import se.alipsa.games.sc.model.UnlockType

import java.nio.file.Files
import java.nio.file.Path

class CampaignLoader {

  private static final Logger log = LogManager.getLogger(CampaignLoader)

  private final CampaignValidator validator

  CampaignLoader(CampaignValidator validator = new CampaignValidator()) {
    this.validator = validator
  }

  CampaignLoadResult loadCampaign(Path directory, Set<String> knownTrackIds) {
    if (directory == null || !Files.isDirectory(directory)) {
      return new CampaignLoadResult(null, [])
    }

    Path campaignFile = directory.resolve('campaign.json')
    if (!Files.isRegularFile(campaignFile)) {
      log.debug('No campaign.json found in {} — free-play mode', directory)
      return new CampaignLoadResult(null, [])
    }

    Object parsed
    try {
      parsed = new JsonSlurper().parse(campaignFile.toFile())
    } catch (Exception e) {
      return new CampaignLoadResult(null,
          [new LoadError(campaignFile.toString(), LoadErrorCode.MALFORMED_JSON,
              "Malformed campaign JSON: ${e.message}")])
    }

    if (!(parsed instanceof Map)) {
      return new CampaignLoadResult(null,
          [new LoadError(campaignFile.toString(), LoadErrorCode.MALFORMED_JSON,
              'Campaign JSON must be a single JSON object')])
    }

    Map<String, ?> rawCampaign = parsed as Map<String, ?>
    String fileName = campaignFile.toString()

    List<LoadError> errors = validator.validate(fileName, rawCampaign, knownTrackIds)
    if (!errors.isEmpty()) {
      return new CampaignLoadResult(null, errors)
    }

    Campaign campaign = buildCampaign(rawCampaign)
    return new CampaignLoadResult(campaign, [])
  }

  private static Campaign buildCampaign(Map<String, ?> raw) {
    List<Chapter> chapters = (raw.chapters as Collection<?>).collect { Object chapterItem ->
      Map<?, ?> chapterMap = chapterItem as Map<?, ?>
      List<CampaignLevel> levels = (chapterMap.levels as Collection<?>).collect { Object levelItem ->
        Map<?, ?> levelMap = levelItem as Map<?, ?>
        UnlockCondition condition = parseUnlockCondition(levelMap.unlockCondition)
        new CampaignLevel(levelMap.trackId.toString().trim(), condition)
      }
      new Chapter(
          chapterMap.id.toString().trim(),
          chapterMap.name.toString().trim(),
          chapterMap.theme?.toString()?.trim() ?: '',
          levels
      )
    }

    new Campaign(
        raw.campaignId.toString().trim(),
        raw.name.toString().trim(),
        chapters
    )
  }

  private static UnlockCondition parseUnlockCondition(Object raw) {
    if (raw == null || !(raw instanceof Map)) {
      return new UnlockCondition(UnlockType.NONE, 0)
    }
    Map<?, ?> conditionMap = raw as Map<?, ?>
    UnlockType type
    try {
      type = UnlockType.valueOf(conditionMap.type?.toString() ?: 'NONE')
    } catch (IllegalArgumentException ignored) {
      type = UnlockType.NONE
    }
    int threshold = 0
    if (type == UnlockType.STAR_THRESHOLD && conditionMap.starThreshold instanceof Number) {
      threshold = ((Number) conditionMap.starThreshold).intValue()
    }
    new UnlockCondition(type, threshold)
  }
}
