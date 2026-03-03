package se.alipsa.games.sc.core

import se.alipsa.games.sc.io.ProgressStore
import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.CampaignLevel
import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.Chapter
import se.alipsa.games.sc.model.UnlockCondition
import se.alipsa.games.sc.model.UnlockType
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Path

class CampaignServiceTest extends Specification {

  @TempDir
  Path tempDir

  @Unroll
  def 'calculateStars returns #expectedStars stars for score #score with target #target'() {
    expect:
    CampaignService.calculateStars(score, target) == expectedStars

    where:
    score | target | expectedStars
    0     | 1000   | 0
    999   | 1000   | 0
    1000  | 1000   | 1
    1499  | 1000   | 1
    1500  | 1000   | 2
    1999  | 1000   | 2
    2000  | 1000   | 3
    5000  | 1000   | 3
  }

  def 'calculateStars returns 0 for zero or negative targetScore'() {
    expect:
    CampaignService.calculateStars(1000, 0) == 0
    CampaignService.calculateStars(1000, -1) == 0
  }

  def 'first level of first chapter is always unlocked'() {
    given:
    Campaign campaign = buildCampaign()
    CampaignService service = createService(campaign)
    CampaignProgress progress = new CampaignProgress('test-campaign')

    expect:
    service.isUnlocked(campaign.chapters[0].levels[0], progress)
  }

  def 'NONE unlock type is always unlocked'() {
    given:
    CampaignLevel level = new CampaignLevel('track-2', new UnlockCondition(UnlockType.NONE, 0))
    Campaign campaign = new Campaign('test', 'Test', [
        new Chapter('ch1', 'Ch1', 'theme', [
            new CampaignLevel('track-1', new UnlockCondition(UnlockType.NONE, 0)),
            level
        ])
    ])
    CampaignService service = createService(campaign)
    CampaignProgress progress = new CampaignProgress('test')

    expect:
    service.isUnlocked(level, progress)
  }

  def 'PREVIOUS_LEVEL_WIN requires previous level completed'() {
    given:
    Campaign campaign = buildCampaign()
    CampaignService service = createService(campaign)
    CampaignProgress progress = new CampaignProgress('test-campaign')

    CampaignLevel secondLevel = campaign.chapters[0].levels[1]

    expect: 'locked when previous not completed'
    !service.isUnlocked(secondLevel, progress)

    when: 'complete previous level'
    progress.recordWin('track-1', 3000, 2)

    then: 'now unlocked'
    service.isUnlocked(secondLevel, progress)
  }

  def 'STAR_THRESHOLD requires enough total stars'() {
    given:
    CampaignLevel thresholdLevel = new CampaignLevel('track-3', new UnlockCondition(UnlockType.STAR_THRESHOLD, 5))
    Campaign campaign = new Campaign('test', 'Test', [
        new Chapter('ch1', 'Ch1', 'theme', [
            new CampaignLevel('track-1', new UnlockCondition(UnlockType.NONE, 0)),
            new CampaignLevel('track-2', new UnlockCondition(UnlockType.NONE, 0)),
            thresholdLevel
        ])
    ])
    CampaignService service = createService(campaign)
    CampaignProgress progress = new CampaignProgress('test')

    expect: 'locked with 0 stars'
    !service.isUnlocked(thresholdLevel, progress)

    when: 'earn some stars but not enough'
    progress.recordWin('track-1', 3000, 2)

    then: 'still locked'
    !service.isUnlocked(thresholdLevel, progress)

    when: 'earn enough total stars'
    progress.recordWin('track-2', 6000, 3)

    then: 'now unlocked'
    service.isUnlocked(thresholdLevel, progress)
  }

  def 'PREVIOUS_LEVEL_WIN across chapters uses last level of previous chapter'() {
    given:
    Campaign campaign = new Campaign('test', 'Test', [
        new Chapter('ch1', 'Ch1', 'theme', [
            new CampaignLevel('track-1', new UnlockCondition(UnlockType.NONE, 0))
        ]),
        new Chapter('ch2', 'Ch2', 'theme', [
            new CampaignLevel('track-2', new UnlockCondition(UnlockType.PREVIOUS_LEVEL_WIN, 0))
        ])
    ])
    CampaignService service = createService(campaign)
    CampaignProgress progress = new CampaignProgress('test')

    CampaignLevel ch2Level = campaign.chapters[1].levels[0]

    expect: 'locked when ch1 last level not completed'
    !service.isUnlocked(ch2Level, progress)

    when: 'complete ch1 last level'
    progress.recordWin('track-1', 3000, 1)

    then: 'ch2 first level now unlocked'
    service.isUnlocked(ch2Level, progress)
  }

  def 'recordWin calculates stars and saves progress'() {
    given:
    Path progressFile = tempDir.resolve('progress.json')
    ProgressStore store = new ProgressStore(progressFile)
    Campaign campaign = buildCampaign()
    CampaignService service = new CampaignService(campaign, store)
    CampaignProgress progress = new CampaignProgress('test-campaign')

    when:
    service.recordWin('track-1', 3000, 2000, progress)

    then: 'score 3000 with target 2000 gives 2 stars (1.5x threshold)'
    progress.isCompleted('track-1')
    progress.getStars('track-1') == 2

    when: 'reload from disk'
    CampaignProgress reloaded = store.load('test-campaign')

    then:
    reloaded.isCompleted('track-1')
    reloaded.getStars('track-1') == 2
  }

  private Campaign buildCampaign() {
    new Campaign('test-campaign', 'Test Campaign', [
        new Chapter('ch1', 'Chapter 1', 'theme', [
            new CampaignLevel('track-1', new UnlockCondition(UnlockType.NONE, 0)),
            new CampaignLevel('track-2', new UnlockCondition(UnlockType.PREVIOUS_LEVEL_WIN, 0))
        ])
    ])
  }

  private CampaignService createService(Campaign campaign) {
    Path progressFile = tempDir.resolve('progress.json')
    ProgressStore store = new ProgressStore(progressFile)
    new CampaignService(campaign, store)
  }
}
