package se.alipsa.games.sc.io

import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.LevelProgress
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ProgressStoreTest extends Specification {

  @TempDir
  Path tempDir

  def 'save and load round-trip preserves progress'() {
    given:
    Path progressFile = tempDir.resolve('progress.json')
    ProgressStore store = new ProgressStore(progressFile)
    CampaignProgress progress = new CampaignProgress('test-campaign')
    progress.recordWin('track-1', 5000, 3)
    progress.recordWin('track-2', 2000, 1)

    when:
    store.save(progress)
    CampaignProgress loaded = store.load('test-campaign')

    then:
    loaded.campaignId == 'test-campaign'
    loaded.levelProgress.size() == 2
    loaded.levelProgress['track-1'].completed
    loaded.levelProgress['track-1'].stars == 3
    loaded.levelProgress['track-1'].bestScore == 5000
    loaded.levelProgress['track-2'].completed
    loaded.levelProgress['track-2'].stars == 1
    loaded.levelProgress['track-2'].bestScore == 2000
  }

  def 'load returns empty progress when file is missing'() {
    given:
    Path progressFile = tempDir.resolve('nonexistent.json')
    ProgressStore store = new ProgressStore(progressFile)

    when:
    CampaignProgress loaded = store.load('test-campaign')

    then:
    loaded.campaignId == 'test-campaign'
    loaded.levelProgress.isEmpty()
  }

  def 'load returns empty progress for corrupt file'() {
    given:
    Path progressFile = tempDir.resolve('corrupt.json')
    Files.writeString(progressFile, '{broken-json!!!')
    ProgressStore store = new ProgressStore(progressFile)

    when:
    CampaignProgress loaded = store.load('test-campaign')

    then:
    loaded.campaignId == 'test-campaign'
    loaded.levelProgress.isEmpty()
  }

  def 'load returns empty progress when campaignId does not match'() {
    given:
    Path progressFile = tempDir.resolve('progress.json')
    ProgressStore store = new ProgressStore(progressFile)
    CampaignProgress progress = new CampaignProgress('campaign-a')
    progress.recordWin('track-1', 1000, 1)
    store.save(progress)

    when:
    CampaignProgress loaded = store.load('campaign-b')

    then:
    loaded.campaignId == 'campaign-b'
    loaded.levelProgress.isEmpty()
  }

  def 'save creates parent directories if missing'() {
    given:
    Path progressFile = tempDir.resolve('sub/dir/progress.json')
    ProgressStore store = new ProgressStore(progressFile)
    CampaignProgress progress = new CampaignProgress('test-campaign')
    progress.recordWin('track-1', 3000, 2)

    when:
    store.save(progress)

    then:
    Files.isRegularFile(progressFile)

    when:
    CampaignProgress loaded = store.load('test-campaign')

    then:
    loaded.levelProgress['track-1'].stars == 2
  }

  def 'totalStars aggregates all level stars'() {
    given:
    CampaignProgress progress = new CampaignProgress('test-campaign')
    progress.recordWin('track-1', 5000, 3)
    progress.recordWin('track-2', 2000, 2)
    progress.recordWin('track-3', 1000, 1)

    expect:
    progress.totalStars() == 6
  }

  def 'recordWin keeps best score and best stars'() {
    given:
    CampaignProgress progress = new CampaignProgress('test-campaign')
    progress.recordWin('track-1', 3000, 2)

    when:
    progress.recordWin('track-1', 2500, 1)

    then:
    progress.levelProgress['track-1'].bestScore == 3000
    progress.levelProgress['track-1'].stars == 2

    when:
    progress.recordWin('track-1', 6000, 3)

    then:
    progress.levelProgress['track-1'].bestScore == 6000
    progress.levelProgress['track-1'].stars == 3
  }

  def 'load handles non-object JSON gracefully'() {
    given:
    Path progressFile = tempDir.resolve('array.json')
    Files.writeString(progressFile, '["not", "an", "object"]')
    ProgressStore store = new ProgressStore(progressFile)

    when:
    CampaignProgress loaded = store.load('test-campaign')

    then:
    loaded.campaignId == 'test-campaign'
    loaded.levelProgress.isEmpty()
  }
}
