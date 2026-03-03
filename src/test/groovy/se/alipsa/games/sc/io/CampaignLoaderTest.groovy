package se.alipsa.games.sc.io

import groovy.json.JsonOutput
import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.UnlockType
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CampaignLoaderTest extends Specification {

  @TempDir
  Path tempDir

  private static final Set<String> KNOWN_TRACK_IDS = ['classic-01', 'test-01', 'test-red-only'] as Set

  def 'loads a valid campaign file'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    writeCampaign(tempDir.resolve('campaign.json'), validCampaign())
    writeTrack(tempDir, 'classic-01')
    writeTrack(tempDir, 'test-01')
    writeTrack(tempDir, 'test-red-only')

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.errors.isEmpty()
    result.campaign != null
    result.campaign.campaignId == 'sweet-crush-main'
    result.campaign.name == 'Sweet Crush Campaign'
    result.campaign.chapters.size() == 1
    result.campaign.chapters[0].levels.size() == 2
    result.campaign.chapters[0].levels[0].trackId == 'classic-01'
    result.campaign.chapters[0].levels[0].unlockCondition.type == UnlockType.NONE
    result.campaign.chapters[0].levels[1].unlockCondition.type == UnlockType.PREVIOUS_LEVEL_WIN
  }

  def 'returns null campaign with no errors when file is missing (free-play mode)'() {
    given:
    CampaignLoader loader = new CampaignLoader()

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.isEmpty()
  }

  def 'returns error for malformed JSON'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Files.writeString(tempDir.resolve('campaign.json'), '{broken-json')

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.MALFORMED_JSON }
  }

  def 'returns error for invalid JSON structure (non-object)'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Files.writeString(tempDir.resolve('campaign.json'), '["not", "an", "object"]')

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.MALFORMED_JSON }
  }

  def 'returns validation errors for missing required fields'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    writeCampaign(tempDir.resolve('campaign.json'), [:])

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.INVALID_CAMPAIGN && it.message.contains('campaignId') }
    result.errors.any { it.code == LoadErrorCode.INVALID_CAMPAIGN && it.message.contains('name') }
    result.errors.any { it.code == LoadErrorCode.INVALID_CAMPAIGN && it.message.contains('chapters') }
  }

  def 'returns error when referenced trackId is not found'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Map campaign = validCampaign()
    campaign.chapters[0].levels[0].trackId = 'nonexistent-track'
    writeCampaign(tempDir.resolve('campaign.json'), campaign)

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.CAMPAIGN_TRACK_NOT_FOUND }
  }

  def 'returns error for empty chapters array'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Map campaign = validCampaign()
    campaign.chapters = []
    writeCampaign(tempDir.resolve('campaign.json'), campaign)

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.INVALID_CAMPAIGN && it.message.contains('at least one chapter') }
  }

  def 'returns error for chapter with empty levels'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Map campaign = validCampaign()
    campaign.chapters[0].levels = []
    writeCampaign(tempDir.resolve('campaign.json'), campaign)

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.INVALID_CAMPAIGN && it.message.contains('at least one level') }
  }

  def 'returns error for unknown unlockCondition type'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Map campaign = validCampaign()
    campaign.chapters[0].levels[0].unlockCondition = [type: 'UNKNOWN_TYPE']
    writeCampaign(tempDir.resolve('campaign.json'), campaign)

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.any { it.code == LoadErrorCode.INVALID_CAMPAIGN && it.message.contains('UNKNOWN_TYPE') }
  }

  def 'returns null campaign with no errors for null directory'() {
    given:
    CampaignLoader loader = new CampaignLoader()

    when:
    CampaignLoadResult result = loader.loadCampaign(null, KNOWN_TRACK_IDS)

    then:
    result.campaign == null
    result.errors.isEmpty()
  }

  def 'parses STAR_THRESHOLD unlock condition with starThreshold'() {
    given:
    CampaignLoader loader = new CampaignLoader()
    Map campaign = validCampaign()
    campaign.chapters[0].levels[1].unlockCondition = [type: 'STAR_THRESHOLD', starThreshold: 5]
    writeCampaign(tempDir.resolve('campaign.json'), campaign)

    when:
    CampaignLoadResult result = loader.loadCampaign(tempDir, KNOWN_TRACK_IDS)

    then:
    result.errors.isEmpty()
    result.campaign.chapters[0].levels[1].unlockCondition.type == UnlockType.STAR_THRESHOLD
    result.campaign.chapters[0].levels[1].unlockCondition.starThreshold == 5
  }

  private static Map validCampaign() {
    [
        campaignId: 'sweet-crush-main',
        name      : 'Sweet Crush Campaign',
        chapters  : [
            [
                id    : 'chapter-1',
                name  : 'Getting Started',
                theme : 'basics',
                levels: [
                    [trackId: 'classic-01', unlockCondition: [type: 'NONE']],
                    [trackId: 'test-01', unlockCondition: [type: 'PREVIOUS_LEVEL_WIN']]
                ]
            ]
        ]
    ]
  }

  private static void writeCampaign(Path path, Map data) {
    Files.writeString(path, JsonOutput.prettyPrint(JsonOutput.toJson(data)))
  }

  private static void writeTrack(Path dir, String id) {
    Map<String, Object> track = [
        id         : id,
        name       : "Track ${id}",
        width      : 7,
        height     : 9,
        moves      : 25,
        targetScore: 3000,
        spawnWeights: [RED: 3, BLUE: 3, GREEN: 3, YELLOW: 2, PURPLE: 2, ORANGE: 1]
    ]
    Files.writeString(dir.resolve("${id}.json"), JsonOutput.prettyPrint(JsonOutput.toJson(track)))
  }
}
