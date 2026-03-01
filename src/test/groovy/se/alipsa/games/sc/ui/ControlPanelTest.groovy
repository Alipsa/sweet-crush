package se.alipsa.games.sc.ui

import se.alipsa.games.sc.core.SpecialPieceType
import spock.lang.Specification

class ControlPanelTest extends Specification {

  def 'updates goal score and moves labels when values change'() {
    given:
    ControlPanel panel = new ControlPanel()

    when:
    panel.updateGoal('Reach 3000 points')
    panel.updateScore(420)
    panel.updateMovesLeft(12)

    then:
    panel.goalText == 'Goal: Reach 3000 points'
    panel.scoreText == 'Score: 420'
    panel.movesText == 'Moves left: 12'
  }

  def 'restart button invokes restart callback'() {
    given:
    int restartCalls = 0
    ControlPanel panel = new ControlPanel({ restartCalls++ })

    when:
    panel.clickRestart()

    then:
    restartCalls == 1
  }

  def 'board debug button invokes callback'() {
    given:
    int debugCalls = 0
    ControlPanel panel = new ControlPanel(null, { debugCalls++ })

    when:
    panel.clickBoardDebug()

    then:
    debugCalls == 1
  }

  def 'hint button invokes callback'() {
    given:
    int hintCalls = 0
    ControlPanel panel = new ControlPanel(null, null, { hintCalls++ })

    when:
    panel.clickHint()

    then:
    hintCalls == 1
  }

  def 'emphasizes score size when score increases and clears on reset'() {
    given:
    ControlPanel panel = new ControlPanel()

    when:
    panel.updateScore(10)

    then:
    panel.scoreEmphasized

    when:
    panel.updateScore(0)

    then:
    !panel.scoreEmphasized
  }

  def 'preserves explicit goal line breaks'() {
    given:
    ControlPanel panel = new ControlPanel()

    when:
    panel.updateGoal('Reach 800 points\nSpecials: Sweeper 2, Bomb 1')

    then:
    panel.goalText.contains('\nSpecials:')
  }

  def 'updates specials remaining label'() {
    given:
    ControlPanel panel = new ControlPanel()

    when:
    panel.updateSpecials([(SpecialPieceType.SWEEPER): 2, (SpecialPieceType.BOMB): 1])

    then:
    panel.specialsText.contains('Sweeper: 2')
    panel.specialsText.contains('Bomb: 1')
  }

  def 'updates objective lines text'() {
    given:
    ControlPanel panel = new ControlPanel()

    when:
    panel.updateObjectives(['[ ] Score: 0/1000', '[ ] Clear blockers: 0/3'])

    then:
    panel.objectivesText.contains('Score: 0/1000')
    panel.objectivesText.contains('Clear blockers: 0/3')
  }

  def 'emphasizes specials size when remaining counts change after initialization'() {
    given:
    ControlPanel panel = new ControlPanel()

    when:
    panel.updateSpecials([(SpecialPieceType.SWEEPER): 2])

    then:
    !panel.specialsEmphasized

    when:
    panel.updateSpecials([(SpecialPieceType.SWEEPER): 1])

    then:
    panel.specialsEmphasized
  }
}
