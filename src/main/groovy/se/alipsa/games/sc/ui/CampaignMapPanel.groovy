package se.alipsa.games.sc.ui

import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.CampaignLevel
import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.Chapter
import se.alipsa.games.sc.model.UnlockType

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.util.function.Consumer

class CampaignMapPanel extends JPanel {

  private static final Color PANEL_BACKGROUND = new Color(0x343434)
  private static final Color TEXT_COLOR = new Color(0xECECEC)
  private static final Color BUTTON_BACKGROUND = new Color(0x5A5A5A)
  private static final Color LOCKED_COLOR = new Color(0x808080)
  private static final Color STAR_FILLED = new Color(0xFFD700)
  private static final Color CHAPTER_HEADER_BG = new Color(0x404040)

  private final Consumer<String> onLevelSelected
  private final JPanel contentPanel
  private final Map<String, Boolean> chapterExpanded = [:]

  CampaignMapPanel(Consumer<String> onLevelSelected) {
    super(new BorderLayout())
    this.onLevelSelected = onLevelSelected
    background = PANEL_BACKGROUND
    preferredSize = new Dimension(260, 0)
    minimumSize = new Dimension(220, 0)

    JLabel titleLabel = new JLabel('Campaign Map')
    titleLabel.foreground = TEXT_COLOR
    titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
    titleLabel.horizontalAlignment = SwingConstants.CENTER
    titleLabel.border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
    add(titleLabel, BorderLayout.NORTH)

    contentPanel = new JPanel()
    contentPanel.layout = new BoxLayout(contentPanel, BoxLayout.Y_AXIS)
    contentPanel.background = PANEL_BACKGROUND

    JScrollPane scrollPane = new JScrollPane(contentPanel)
    scrollPane.viewport.background = PANEL_BACKGROUND
    scrollPane.background = PANEL_BACKGROUND
    scrollPane.border = BorderFactory.createEmptyBorder()
    scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    add(scrollPane, BorderLayout.CENTER)
  }

  void refresh(Campaign campaign, CampaignProgress progress,
               java.util.function.BiFunction<CampaignLevel, CampaignProgress, Boolean> unlockChecker,
               Map<String, String> trackNames = [:]) {
    contentPanel.removeAll()

    if (campaign == null) {
      contentPanel.revalidate()
      contentPanel.repaint()
      return
    }

    JLabel starsLabel = new JLabel("Total Stars: ${starString(progress.totalStars())}")
    starsLabel.foreground = STAR_FILLED
    starsLabel.font = starsLabel.font.deriveFont(Font.BOLD, 14f)
    starsLabel.alignmentX = Component.LEFT_ALIGNMENT
    starsLabel.border = BorderFactory.createEmptyBorder(4, 8, 8, 4)
    contentPanel.add(starsLabel)

    campaign.chapters.each { Chapter chapter ->
      chapterExpanded.putIfAbsent(chapter.id, true)
      contentPanel.add(createChapterSection(chapter, progress, unlockChecker, trackNames))
    }

    contentPanel.add(Box.createVerticalGlue())
    contentPanel.revalidate()
    contentPanel.repaint()
  }

  private JPanel createChapterSection(Chapter chapter, CampaignProgress progress,
                                       java.util.function.BiFunction<CampaignLevel, CampaignProgress, Boolean> unlockChecker,
                                       Map<String, String> trackNames) {
    JPanel sectionPanel = new JPanel()
    sectionPanel.layout = new BoxLayout(sectionPanel, BoxLayout.Y_AXIS)
    sectionPanel.background = PANEL_BACKGROUND
    sectionPanel.alignmentX = Component.LEFT_ALIGNMENT
    sectionPanel.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)

    JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2))
    headerPanel.background = CHAPTER_HEADER_BG
    headerPanel.maximumSize = new Dimension(Integer.MAX_VALUE, 32)
    headerPanel.alignmentX = Component.LEFT_ALIGNMENT
    headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

    boolean expanded = chapterExpanded[chapter.id]
    String arrow = expanded ? '\u25BC' : '\u25B6'
    JLabel headerLabel = new JLabel("${arrow} ${chapter.name}")
    headerLabel.foreground = TEXT_COLOR
    headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
    headerPanel.add(headerLabel)

    int chapterStars = chapter.levels.isEmpty() ? 0 :
        (chapter.levels.sum { CampaignLevel lvl -> progress.getStars(lvl.trackId) } as int)
    int maxStars = chapter.levels.size() * 3
    JLabel chapterStarsLabel = new JLabel("${chapterStars}/${maxStars}")
    chapterStarsLabel.foreground = STAR_FILLED
    chapterStarsLabel.font = chapterStarsLabel.font.deriveFont(Font.PLAIN, 11f)
    headerPanel.add(chapterStarsLabel)

    sectionPanel.add(headerPanel)

    JPanel levelsPanel = new JPanel()
    levelsPanel.layout = new BoxLayout(levelsPanel, BoxLayout.Y_AXIS)
    levelsPanel.background = PANEL_BACKGROUND
    levelsPanel.alignmentX = Component.LEFT_ALIGNMENT
    levelsPanel.visible = expanded

    chapter.levels.eachWithIndex { CampaignLevel level, int index ->
      boolean unlocked = unlockChecker.apply(level, progress)
      levelsPanel.add(createLevelButton(level, index + 1, progress, unlocked, trackNames))
    }

    sectionPanel.add(levelsPanel)

    headerPanel.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override
      void mouseClicked(java.awt.event.MouseEvent e) {
        boolean nowExpanded = !chapterExpanded[chapter.id]
        chapterExpanded[chapter.id] = nowExpanded
        levelsPanel.visible = nowExpanded
        headerLabel.text = "${nowExpanded ? '\u25BC' : '\u25B6'} ${chapter.name}"
        sectionPanel.revalidate()
        sectionPanel.repaint()
      }
    })

    return sectionPanel
  }

  private JPanel createLevelButton(CampaignLevel level, int levelNumber,
                                    CampaignProgress progress, boolean unlocked,
                                    Map<String, String> trackNames = [:]) {
    JPanel row = new JPanel(new BorderLayout())
    row.background = PANEL_BACKGROUND
    row.maximumSize = new Dimension(Integer.MAX_VALUE, 36)
    row.alignmentX = Component.LEFT_ALIGNMENT
    row.border = BorderFactory.createEmptyBorder(1, 12, 1, 4)

    if (unlocked) {
      JButton button = new JButton("Level ${levelNumber}")
      button.opaque = true
      button.margin = new Insets(2, 8, 2, 8)
      button.background = BUTTON_BACKGROUND
      button.foreground = Color.WHITE
      button.focusPainted = false
      button.preferredSize = new Dimension(120, 28)
      button.addActionListener { onLevelSelected.accept(level.trackId) }
      String trackName = trackNames?.getOrDefault(level.trackId, level.trackId) ?: level.trackId
      button.toolTipText = "Play ${trackName}"
      row.add(button, BorderLayout.WEST)

      int stars = progress.getStars(level.trackId)
      JLabel starsLabel = new JLabel(renderStars(stars))
      starsLabel.foreground = TEXT_COLOR
      starsLabel.font = starsLabel.font.deriveFont(Font.PLAIN, 14f)
      row.add(starsLabel, BorderLayout.EAST)
    } else {
      JLabel lockedLabel = new JLabel("\uD83D\uDD12 Level ${levelNumber}")
      lockedLabel.foreground = LOCKED_COLOR
      lockedLabel.font = lockedLabel.font.deriveFont(Font.PLAIN, 13f)
      lockedLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 0)
      lockedLabel.toolTipText = unlockRequirementText(level)
      row.add(lockedLabel, BorderLayout.WEST)
    }

    return row
  }

  private static String renderStars(int count) {
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i < 3; i++) {
      sb.append(i < count ? '\u2605' : '\u2606')
    }
    sb.toString()
  }

  private static String starString(int totalStars) {
    "\u2605 ${totalStars}"
  }

  private static String unlockRequirementText(CampaignLevel level) {
    if (level.unlockCondition == null) {
      return 'Locked'
    }
    switch (level.unlockCondition.type) {
      case UnlockType.PREVIOUS_LEVEL_WIN:
        return 'Win the previous level to unlock'
      case UnlockType.STAR_THRESHOLD:
        return "Earn ${level.unlockCondition.value} total stars to unlock"
      default:
        return 'Locked'
    }
  }
}
