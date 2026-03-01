package se.alipsa.games.sc.ui

import se.alipsa.games.sc.core.GameOutcome

import javax.swing.JOptionPane
import java.awt.Component
import java.awt.GraphicsEnvironment

class GameOverDialog {

  enum Action {
    RETRY,
    SKIP,
    NEXT_TRACK,
    RETURN_TO_SELECTION
  }

  static String winMessage() {
    'Level completed, well done!'
  }

  static String loseMessage() {
    'Moves exhausted. What would you like to do?'
  }

  static List<String> loseOptions() {
    ['Retry', 'Skip']
  }

  static Action determineWinAction(int currentTrackIndex, int totalTracks) {
    if (totalTracks <= 0 || currentTrackIndex >= totalTracks - 1) {
      return Action.RETURN_TO_SELECTION
    }
    return Action.NEXT_TRACK
  }

  Action showForWin(Component parent, int currentTrackIndex, int totalTracks) {
    if (!GraphicsEnvironment.isHeadless()) {
      WinCelebrationDialog.show(parent, winMessage())
    }
    return determineWinAction(currentTrackIndex, totalTracks)
  }

  Action showForLose(Component parent) {
    if (GraphicsEnvironment.isHeadless()) {
      return Action.RETRY
    }

    List<String> options = loseOptions()
    int choice = JOptionPane.showOptionDialog(
        parent,
        loseMessage(),
        'Game Over',
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.QUESTION_MESSAGE,
        null,
        options as Object[],
        options[0]
    )

    return choice == 1 ? Action.SKIP : Action.RETRY
  }
}
