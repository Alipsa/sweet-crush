package se.alipsa.games.sc.io

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.LevelProgress

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ProgressStore {

  private static final Logger log = LogManager.getLogger(ProgressStore)

  private final Path progressFile

  ProgressStore(Path progressFile = defaultProgressFile()) {
    this.progressFile = progressFile
  }

  static Path defaultProgressFile() {
    Path home = Path.of(System.getProperty('user.home'))
    home.resolve('.sweet-crush').resolve('progress.json')
  }

  CampaignProgress load(String campaignId) {
    if (!Files.isRegularFile(progressFile)) {
      log.debug('Progress file not found at {} — returning empty progress', progressFile)
      return new CampaignProgress(campaignId)
    }

    try {
      Object parsed = new JsonSlurper().parse(progressFile.toFile())
      if (!(parsed instanceof Map)) {
        log.warn('Progress file is not a JSON object — returning empty progress')
        return new CampaignProgress(campaignId)
      }

      Map<String, ?> data = parsed as Map<String, ?>
      String storedCampaignId = data.campaignId?.toString()?.trim() ?: ''
      if (!storedCampaignId) {
        log.info('Progress file has no campaignId — returning empty progress')
        return new CampaignProgress(campaignId)
      }

      if (storedCampaignId != campaignId) {
        log.info('Progress file is for campaign "{}" but requested "{}". Returning empty progress.',
            storedCampaignId, campaignId)
        return new CampaignProgress(campaignId)
      }

      Map<String, LevelProgress> levelProgress = [:]
      if (data.levels instanceof Map) {
        (data.levels as Map<String, ?>).each { String trackId, Object levelData ->
          if (levelData instanceof Map) {
            Map<?, ?> ld = levelData as Map<?, ?>
            levelProgress[trackId] = new LevelProgress(
                trackId,
                Boolean.parseBoolean(ld.completed?.toString() ?: 'false'),
                parseIntSafe(ld.stars, 0),
                parseIntSafe(ld.bestScore, 0)
            )
          }
        }
      }

      return new CampaignProgress(campaignId, levelProgress)
    } catch (Exception e) {
      log.warn('Failed to read progress file: {} — returning empty progress', e.message)
      return new CampaignProgress(campaignId)
    }
  }

  void save(CampaignProgress progress) {
    try {
      Path parentDir = progressFile.parent
      if (!Files.isDirectory(parentDir)) {
        Files.createDirectories(parentDir)
      }

      Map<String, Object> data = [
          campaignId: progress.campaignId,
          levels    : progress.levelProgress.collectEntries { String trackId, LevelProgress lp ->
            [(trackId): [
                completed: lp.completed,
                stars    : lp.stars,
                bestScore: lp.bestScore
            ]]
          }
      ]

      String json = JsonOutput.prettyPrint(JsonOutput.toJson(data))

      Path tmpFile = parentDir.resolve("progress.tmp.${System.currentTimeMillis()}.json")
      Files.writeString(tmpFile, json)
      try {
        Files.move(tmpFile, progressFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        Files.move(tmpFile, progressFile, StandardCopyOption.REPLACE_EXISTING)
      }

      log.debug('Progress saved to {}', progressFile)
    } catch (Exception e) {
      log.warn('Failed to save progress: {}', e.message)
    }
  }

  private static int parseIntSafe(Object value, int defaultValue) {
    if (value == null) {
      return defaultValue
    }
    if (value instanceof Number) {
      return ((Number) value).intValue()
    }
    try {
      return Integer.parseInt(value.toString())
    } catch (NumberFormatException ignored) {
      return defaultValue
    }
  }
}
