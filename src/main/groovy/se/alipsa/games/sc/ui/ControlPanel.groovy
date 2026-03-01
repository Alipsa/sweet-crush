package se.alipsa.games.sc.ui

import se.alipsa.games.sc.core.SpecialPieceType

import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Timer
import java.awt.Component
import java.awt.Font
import java.util.EnumMap

class ControlPanel extends JPanel {

  private static final int GOAL_WRAP_CHARS = 20
  private static final int LABEL_GAP = 10
  private static final int BUTTON_GAP = 16
  private static final java.awt.Color PANEL_BACKGROUND = new java.awt.Color(0x343434)
  private static final java.awt.Color TEXT_COLOR = new java.awt.Color(0xECECEC)
  private static final java.awt.Color BUTTON_BACKGROUND = new java.awt.Color(0x5A5A5A)

  private final JLabel goalLabel = new JLabel()
  private final JLabel objectivesHeaderLabel = new JLabel('Objectives:')
  private final JLabel objectivesLabel = new JLabel('Complete the score goal')
  private final JLabel scoreLabel = new JLabel('Score: 0')
  private final JLabel movesLabel = new JLabel('Moves left: 0')
  private final JLabel specialsHeaderLabel = new JLabel('Specials left:')
  private final JLabel sweeperLabel = new JLabel('Sweeper: 0')
  private final JLabel smallBombLabel = new JLabel('Small Bomb: 0')
  private final JLabel bombLabel = new JLabel('Bomb: 0')
  private final JLabel fishLabel = new JLabel('Fish: 0')
  private final JButton restartButton = new JButton('Restart')
  private final JButton hintButton = new JButton('Hint')
  private final JButton boardDebugButton = new JButton('Log Board States')
  private final Font baseLabelFont = scoreLabel.font.deriveFont(Font.BOLD)
  private final Font emphasizedLabelFont = baseLabelFont.deriveFont((baseLabelFont.size2D + 2.0f) as float)
  private String currentGoalText = 'Choose a track to start'
  private int currentScore = 0
  private Map<SpecialPieceType, Integer> currentSpecials = [:]
  private boolean specialsInitialized = false
  private Timer scoreEmphasisTimer
  private Timer specialsEmphasisTimer

  ControlPanel(Runnable restartAction = null, Runnable boardDebugAction = null, Runnable hintAction = null) {
    super()
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS))
    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
    setOpaque(true)
    background = PANEL_BACKGROUND

    goalLabel.alignmentX = Component.LEFT_ALIGNMENT
    scoreLabel.alignmentX = Component.LEFT_ALIGNMENT
    movesLabel.alignmentX = Component.LEFT_ALIGNMENT
    objectivesHeaderLabel.alignmentX = Component.LEFT_ALIGNMENT
    objectivesLabel.alignmentX = Component.LEFT_ALIGNMENT
    specialsHeaderLabel.alignmentX = Component.LEFT_ALIGNMENT
    sweeperLabel.alignmentX = Component.LEFT_ALIGNMENT
    smallBombLabel.alignmentX = Component.LEFT_ALIGNMENT
    bombLabel.alignmentX = Component.LEFT_ALIGNMENT
    fishLabel.alignmentX = Component.LEFT_ALIGNMENT
    restartButton.alignmentX = Component.LEFT_ALIGNMENT
    hintButton.alignmentX = Component.LEFT_ALIGNMENT
    boardDebugButton.alignmentX = Component.LEFT_ALIGNMENT
    applyLabelFonts(baseLabelFont)
    applyLabelColors(TEXT_COLOR)
    styleButton(restartButton)
    styleButton(hintButton)
    styleButton(boardDebugButton)

    add(goalLabel)
    add(Box.createVerticalStrut(6))
    add(objectivesHeaderLabel)
    add(Box.createVerticalStrut(4))
    add(objectivesLabel)
    add(Box.createVerticalStrut(LABEL_GAP))
    add(scoreLabel)
    add(Box.createVerticalStrut(LABEL_GAP))
    add(movesLabel)
    add(Box.createVerticalStrut(LABEL_GAP))
    add(specialsHeaderLabel)
    add(Box.createVerticalStrut(4))
    add(sweeperLabel)
    add(smallBombLabel)
    add(bombLabel)
    add(fishLabel)
    add(Box.createVerticalStrut(BUTTON_GAP))
    add(restartButton)
    add(Box.createVerticalStrut(8))
    add(hintButton)
    add(Box.createVerticalStrut(8))
    add(boardDebugButton)

    restartButton.addActionListener {
      restartAction?.run()
    }
    boardDebugButton.addActionListener {
      boardDebugAction?.run()
    }
    hintButton.addActionListener {
      hintAction?.run()
    }

    updateGoal(currentGoalText)
  }

  void updateGoal(String goalText) {
    currentGoalText = goalText ?: ''
    String wrapped = wrapAt(currentGoalText, GOAL_WRAP_CHARS)
    String body = escapeHtml(wrapped).replace('\n', '<br/>')
    goalLabel.text = "<html>Goal: ${body}</html>"
  }

  void updateScore(int score) {
    boolean changed = score != currentScore
    boolean increased = score > currentScore
    currentScore = score
    scoreLabel.text = "Score: ${score}"
    if (changed && increased) {
      emphasizeScoreForThreeSeconds()
    } else if (score <= 0) {
      clearScoreEmphasis()
    }
  }

  void updateMovesLeft(int movesLeft) {
    movesLabel.text = "Moves left: ${movesLeft}"
  }

  void updateObjectives(List<String> lines) {
    List<String> cleaned = (lines ?: []).findAll { String line ->
      line != null && !line.trim().isEmpty()
    }
    if (cleaned.isEmpty()) {
      objectivesLabel.text = 'Complete the score goal'
      return
    }
    String body = cleaned.collect { String line ->
      escapeHtml(line)
    }.join('<br/>')
    objectivesLabel.text = "<html>${body}</html>"
  }

  void updateSpecials(Map<SpecialPieceType, Integer> remaining) {
    Map<SpecialPieceType, Integer> values = normalizeSpecials(remaining)
    boolean changed = specialsInitialized && specialsChanged(values)
    specialsInitialized = true
    currentSpecials = values

    sweeperLabel.text = "Sweeper: ${values.getOrDefault(SpecialPieceType.SWEEPER, 0)}"
    smallBombLabel.text = "Small Bomb: ${values.getOrDefault(SpecialPieceType.SMALL_BOMB, 0)}"
    bombLabel.text = "Bomb: ${values.getOrDefault(SpecialPieceType.BOMB, 0)}"
    fishLabel.text = "Fish: ${values.getOrDefault(SpecialPieceType.FISH, 0)}"
    if (changed) {
      emphasizeSpecialsForThreeSeconds()
    }
  }

  String getGoalText() {
    "Goal: ${currentGoalText ?: ''}"
  }

  String getScoreText() {
    scoreLabel.text
  }

  String getObjectivesText() {
    objectivesLabel.text
        ?.replace('<html>', '')
        ?.replace('</html>', '')
        ?.replace('<br/>', '\n')
  }

  String getMovesText() {
    movesLabel.text
  }

  String getSpecialsText() {
    [specialsHeaderLabel.text, sweeperLabel.text, smallBombLabel.text, bombLabel.text, fishLabel.text].join('\n')
  }

  boolean isScoreEmphasized() {
    scoreLabel.font.size > baseLabelFont.size
  }

  boolean isSpecialsEmphasized() {
    sweeperLabel.font.size > baseLabelFont.size
  }

  void clickRestart() {
    restartButton.doClick()
  }

  void clickBoardDebug() {
    boardDebugButton.doClick()
  }

  void clickHint() {
    hintButton.doClick()
  }

  private static String wrapAt(String text, int maxChars) {
    if (!text) {
      return ''
    }

    List<String> lines = text.replace('\r', '').split('\n', -1) as List<String>
    StringBuilder out = new StringBuilder()
    lines.eachWithIndex { String lineText, int idx ->
      out.append(wrapSingleLine(lineText, maxChars))
      if (idx < lines.size() - 1) {
        out.append('\n')
      }
    }
    out.toString()
  }

  private static String wrapSingleLine(String text, int maxChars) {
    String trimmed = text?.trim() ?: ''
    if (!trimmed) {
      return ''
    }

    List<String> tokens = trimmed.split(/\s+/) as List<String>
    StringBuilder out = new StringBuilder()
    StringBuilder line = new StringBuilder()

    tokens.each { String token ->
      String remaining = token
      while (remaining.length() > maxChars) {
        if (line.length() > 0) {
          appendLine(out, line.toString())
          line.setLength(0)
        }
        appendLine(out, remaining.substring(0, maxChars))
        remaining = remaining.substring(maxChars)
      }

      if (line.length() == 0) {
        line.append(remaining)
      } else if (line.length() + 1 + remaining.length() <= maxChars) {
        line.append(' ').append(remaining)
      } else {
        appendLine(out, line.toString())
        line.setLength(0)
        line.append(remaining)
      }
    }

    if (line.length() > 0) {
      out.append(line)
    } else if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
      out.setLength(out.length() - 1)
    }

    out.toString()
  }

  private static void appendLine(StringBuilder out, String line) {
    out.append(line).append('\n')
  }

  private void emphasizeScoreForThreeSeconds() {
    setScoreEmphasis(true)
    if (scoreEmphasisTimer == null) {
      scoreEmphasisTimer = new Timer(3000, {
        clearScoreEmphasis()
      })
      scoreEmphasisTimer.repeats = false
    }
    scoreEmphasisTimer.restart()
  }

  private void clearScoreEmphasis() {
    if (scoreEmphasisTimer != null) {
      scoreEmphasisTimer.stop()
    }
    setScoreEmphasis(false)
  }

  private void emphasizeSpecialsForThreeSeconds() {
    setSpecialsEmphasis(true)
    if (specialsEmphasisTimer == null) {
      specialsEmphasisTimer = new Timer(3000, {
        clearSpecialsEmphasis()
      })
      specialsEmphasisTimer.repeats = false
    }
    specialsEmphasisTimer.restart()
  }

  private void clearSpecialsEmphasis() {
    if (specialsEmphasisTimer != null) {
      specialsEmphasisTimer.stop()
    }
    setSpecialsEmphasis(false)
  }

  private void setScoreEmphasis(boolean emphasized) {
    scoreLabel.font = emphasized ? emphasizedLabelFont : baseLabelFont
  }

  private void setSpecialsEmphasis(boolean emphasized) {
    Font font = emphasized ? emphasizedLabelFont : baseLabelFont
    specialsHeaderLabel.font = font
    sweeperLabel.font = font
    smallBombLabel.font = font
    bombLabel.font = font
    fishLabel.font = font
  }

  private void applyLabelFonts(Font font) {
    goalLabel.font = font
    objectivesHeaderLabel.font = font
    objectivesLabel.font = font
    scoreLabel.font = font
    movesLabel.font = font
    specialsHeaderLabel.font = font
    sweeperLabel.font = font
    smallBombLabel.font = font
    bombLabel.font = font
    fishLabel.font = font
  }

  private void applyLabelColors(java.awt.Color color) {
    goalLabel.foreground = color
    objectivesHeaderLabel.foreground = color
    objectivesLabel.foreground = color
    scoreLabel.foreground = color
    movesLabel.foreground = color
    specialsHeaderLabel.foreground = color
    sweeperLabel.foreground = color
    smallBombLabel.foreground = color
    bombLabel.foreground = color
    fishLabel.foreground = color
  }

  private void styleButton(JButton button) {
    button.background = BUTTON_BACKGROUND
    button.foreground = java.awt.Color.WHITE
    button.focusPainted = false
  }

  private Map<SpecialPieceType, Integer> normalizeSpecials(Map<SpecialPieceType, Integer> values) {
    Map<SpecialPieceType, Integer> normalized = new EnumMap<>(SpecialPieceType)
    SpecialPieceType.values().each { SpecialPieceType type ->
      normalized[type] = values?.getOrDefault(type, 0) ?: 0
    }
    normalized
  }

  private boolean specialsChanged(Map<SpecialPieceType, Integer> next) {
    SpecialPieceType.values().any { SpecialPieceType type ->
      (currentSpecials?.getOrDefault(type, 0) ?: 0) != next.getOrDefault(type, 0)
    }
  }

  private static String escapeHtml(String value) {
    value
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
        .replace("'", '&#39;')
  }

}
