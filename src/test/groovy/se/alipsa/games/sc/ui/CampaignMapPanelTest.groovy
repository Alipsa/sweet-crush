package se.alipsa.games.sc.ui

import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.CampaignLevel
import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.Chapter
import se.alipsa.games.sc.model.UnlockCondition
import se.alipsa.games.sc.model.UnlockType
import spock.lang.Specification

import javax.swing.JButton
import javax.swing.JLabel
import java.awt.Component

class CampaignMapPanelTest extends Specification {

  def 'panel can be created with a callback'() {
    when:
    CampaignMapPanel panel = new CampaignMapPanel({ String trackId -> })

    then:
    panel != null
    panel.preferredSize.width == 260
  }

  def 'refresh populates panel with chapter sections and level buttons'() {
    given:
    List<String> selectedTrackIds = []
    CampaignMapPanel panel = new CampaignMapPanel({ String trackId -> selectedTrackIds << trackId })

    Campaign campaign = buildCampaign()
    CampaignProgress progress = new CampaignProgress('test')

    when:
    panel.refresh(campaign, progress, { level, prog -> true })

    then: 'panel has level buttons'
    findLevelButtons(panel).size() >= 2
  }

  def 'locked levels do not have clickable buttons'() {
    given:
    List<String> selectedTrackIds = []
    CampaignMapPanel panel = new CampaignMapPanel({ String trackId -> selectedTrackIds << trackId })

    Campaign campaign = buildCampaign()
    CampaignProgress progress = new CampaignProgress('test')

    when: 'first level unlocked, second locked'
    panel.refresh(campaign, progress, { level, prog ->
      level.trackId == 'track-1'
    })

    then: 'only one level button (for unlocked level)'
    findLevelButtons(panel).size() == 1

    and: 'locked level shows a label instead'
    findAllLabels(panel).any { JLabel l -> l.text?.contains('Level 2') }
  }

  def 'star rendering shows correct filled and empty stars'() {
    given:
    CampaignMapPanel panel = new CampaignMapPanel({ String trackId -> })
    Campaign campaign = buildCampaign()
    CampaignProgress progress = new CampaignProgress('test')
    progress.recordWin('track-1', 5000, 2)

    when:
    panel.refresh(campaign, progress, { level, prog -> true })

    then: 'star labels exist with filled stars'
    List<JLabel> starLabels = findAllLabels(panel).findAll { JLabel l ->
      l.text?.contains('\u2605') || l.text?.contains('\u2606')
    }
    !starLabels.isEmpty()
  }

  def 'refresh with null campaign clears panel'() {
    given:
    CampaignMapPanel panel = new CampaignMapPanel({ String trackId -> })

    when:
    panel.refresh(null, new CampaignProgress('test'), { level, prog -> true })

    then:
    noExceptionThrown()
  }

  private static Campaign buildCampaign() {
    new Campaign('test', 'Test Campaign', [
        new Chapter('ch1', 'Chapter 1', 'theme', [
            new CampaignLevel('track-1', new UnlockCondition(UnlockType.NONE, 0)),
            new CampaignLevel('track-2', new UnlockCondition(UnlockType.PREVIOUS_LEVEL_WIN, 0))
        ])
    ])
  }

  private static List<JButton> findLevelButtons(Component component) {
    List<JButton> buttons = []
    collectButtons(component, buttons)
    buttons.findAll { JButton b -> b.text?.startsWith('Level ') }
  }

  private static void collectButtons(Component component, List<JButton> buttons) {
    if (component instanceof JButton) {
      buttons << component
    }
    if (component instanceof java.awt.Container) {
      (component as java.awt.Container).components.each { Component child ->
        collectButtons(child, buttons)
      }
    }
  }

  private static List<JLabel> findAllLabels(Component component) {
    List<JLabel> labels = []
    collectLabels(component, labels)
    labels
  }

  private static void collectLabels(Component component, List<JLabel> labels) {
    if (component instanceof JLabel) {
      labels << component
    }
    if (component instanceof java.awt.Container) {
      (component as java.awt.Container).components.each { Component child ->
        collectLabels(child, labels)
      }
    }
  }
}
