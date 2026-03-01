package se.alipsa.games.sc.ui

import spock.lang.Specification

class GameOverDialogTest extends Specification {

  def 'win state shows congratulations message'() {
    expect:
    GameOverDialog.winMessage() == 'Level completed, well done!'
  }

  def 'lose state offers retry and skip options'() {
    expect:
    GameOverDialog.loseOptions() == ['Retry', 'Skip']
  }

  def 'after last track win returns to track selection'() {
    expect:
    GameOverDialog.determineWinAction(2, 3) == GameOverDialog.Action.RETURN_TO_SELECTION
  }
}
