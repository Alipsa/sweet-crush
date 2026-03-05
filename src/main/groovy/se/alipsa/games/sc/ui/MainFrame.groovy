package se.alipsa.games.sc.ui

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import groovy.json.JsonSlurper
import se.alipsa.games.sc.core.Board
import se.alipsa.games.sc.core.CampaignService
import se.alipsa.games.sc.core.GameEngine
import se.alipsa.games.sc.core.GameListener
import se.alipsa.games.sc.core.GameOutcome
import se.alipsa.games.sc.core.IngredientType
import se.alipsa.games.sc.io.CampaignLoadResult
import se.alipsa.games.sc.io.CampaignLoader
import se.alipsa.games.sc.io.LoadResult
import se.alipsa.games.sc.io.ProgressStore
import se.alipsa.games.sc.io.TrackLoader
import se.alipsa.games.sc.model.Campaign
import se.alipsa.games.sc.model.CampaignLevel
import se.alipsa.games.sc.model.CampaignProgress
import se.alipsa.games.sc.model.ObjectiveType
import se.alipsa.games.sc.model.SpawnKind
import se.alipsa.games.sc.model.Track

import javax.swing.JFrame
import javax.swing.JFileChooser
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Image
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.stream.Stream
import javax.imageio.ImageIO
import java.util.prefs.Preferences

class MainFrame extends JFrame {

  private static final Logger log = LogManager.getLogger(MainFrame)
  private static final String PREF_LAST_TRACK_DIR = 'lastTrackDirectory'
  private static final String PREF_COMPLETED_TRACKS = 'completedTracks'
  private static final Color APP_BACKGROUND = new Color(0x2C2C2C)
  private static final Color PANEL_BACKGROUND = new Color(0x343434)
  private static final Color LIST_BACKGROUND = PANEL_BACKGROUND
  private static final Color TEXT_COLOR = new Color(0xECECEC)
  private static final Color LIST_SELECTION = new Color(0x6A6A6A)

  private static final String CARD_TRACK_LIST = 'trackList'
  private static final String CARD_CAMPAIGN_MAP = 'campaignMap'

  private final TrackLoader trackLoader = new TrackLoader()
  private final CampaignLoader campaignLoader = new CampaignLoader()
  private final GameOverDialog gameOverDialog = new GameOverDialog()
  private final Preferences preferences = Preferences.userNodeForPackage(MainFrame)

  private final BoardPanel boardPanel = new BoardPanel()
  private final ControlPanel controlPanel
  private final DefaultListModel<String> trackListModel = new DefaultListModel<>()
  private final JList<String> trackList = new JList<>(trackListModel)
  private final JButton chooseTrackFolderButton = new JButton('Choose Track Folder')
  private final JButton createTrackButton = new JButton('Create Track')
  private final JButton editTrackButton = new JButton('Edit Track')

  private final CardLayout rightPanelCardLayout = new CardLayout()
  private final JPanel rightPanel = new JPanel(rightPanelCardLayout)
  private CampaignMapPanel campaignMapPanel

  private ExecutorService gameWorker
  private GameEngine gameEngine
  private LoadResult currentLoadResult = new LoadResult([], [])
  private int currentTrackIndex = -1
  private boolean updatingTrackListSelection = false
  private Path currentTrackDirectory

  private CampaignService campaignService
  private CampaignProgress campaignProgress
  private boolean campaignMode = false
  private Campaign loadedCampaign
  private JButton modeToggleButton

  MainFrame() {
    super('Sweet Crush')
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
    applyAppIcon()
    setSize(1260, 840)
    setLocationRelativeTo(null)
    setLayout(new BorderLayout())
    getContentPane().setBackground(APP_BACKGROUND)

    controlPanel = new ControlPanel({ restartCurrentTrack() }, { logPreviousAndCurrentBoardState() }, { showHint() })
    configureTrackList()

    boardPanel.setMoveResolvedCallback {
      if (gameEngine != null) {
        controlPanel.updateMovesLeft(gameEngine.movesLeft)
      }
    }

    campaignMapPanel = new CampaignMapPanel({ String trackId -> onCampaignLevelSelected(trackId) })

    rightPanel.add(buildTrackListPanel(), CARD_TRACK_LIST)
    rightPanel.add(campaignMapPanel, CARD_CAMPAIGN_MAP)
    rightPanelCardLayout.show(rightPanel, CARD_TRACK_LIST)

    JPanel rightWrapper = new JPanel(new BorderLayout())
    rightWrapper.background = PANEL_BACKGROUND
    rightWrapper.add(rightPanel, BorderLayout.CENTER)
    rightWrapper.add(buildTrackActionButtons(), BorderLayout.SOUTH)

    add(controlPanel, BorderLayout.WEST)
    add(boardPanel, BorderLayout.CENTER)
    add(rightWrapper, BorderLayout.EAST)

    addWindowListener(new WindowAdapter() {
      @Override
      void windowClosed(WindowEvent e) {
        shutdownWorker()
      }
    })

    Path lastTrackDir = readLastTrackDirectory()
    if (lastTrackDir != null) {
      loadTrackDirectory(lastTrackDir)
    }
  }

  void chooseTrackFolder() {
    JFileChooser chooser = new JFileChooser()
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    Path lastTrackDir = readLastTrackDirectory()
    if (lastTrackDir != null) {
      chooser.currentDirectory = lastTrackDir.toFile()
    }

    int selection = chooser.showOpenDialog(this)
    if (selection != JFileChooser.APPROVE_OPTION) {
      return
    }

    Path directory = chooser.selectedFile.toPath()
    loadTrackDirectory(directory)
  }

  void loadTrackDirectory(Path directory) {
    rememberLastTrackDirectory(directory)
    currentTrackDirectory = directory
    log.info('Loading tracks from {}', directory)
    LoadResult loadResult = trackLoader.loadTracks(directory)
    currentLoadResult = loadResult
    refreshTrackList(loadResult)

    if (loadResult.tracks.isEmpty()) {
      JOptionPane.showMessageDialog(this,
          TrackSelectionDialog.EMPTY_MESSAGE,
          'No Tracks',
          JOptionPane.INFORMATION_MESSAGE)
      currentTrackIndex = -1
      campaignMode = false
      campaignService = null
      campaignProgress = null
      loadedCampaign = null
      rightPanelCardLayout.show(rightPanel, CARD_TRACK_LIST)
      controlPanel.updateGoal('Choose a track to start')
      controlPanel.updateObjectives([])
      controlPanel.updateScore(0)
      controlPanel.updateMovesLeft(0)
      controlPanel.updateSpecials([:])
      boardPanel.updateBoard(null)
      updateModeToggleButton()
      return
    }

    Set<String> trackIds = loadResult.tracks.collect { Track t -> t.id }.toSet()
    CampaignLoadResult campaignResult = campaignLoader.loadCampaign(directory, trackIds)

    if (campaignResult.campaign != null) {
      enterCampaignMode(campaignResult.campaign)
      return
    }

    if (!campaignResult.errors.isEmpty()) {
      log.warn('Campaign loading errors: {}', campaignResult.errors)
      String errorDetails = campaignResult.errors.collect { it.toString() }.join('\n')
      JOptionPane.showMessageDialog(this,
          "Campaign configuration errors were found in campaign.json.\n" +
          "Free-play mode will be used instead.\n\nDetails:\n" + errorDetails,
          'Campaign Configuration Error',
          JOptionPane.ERROR_MESSAGE)
    }

    loadedCampaign = null
    campaignService = null
    campaignProgress = null
    exitCampaignMode()

    int initialIndex = 0
    if (currentTrackIndex >= 0 && currentTrackIndex < loadResult.tracks.size()) {
      initialIndex = currentTrackIndex
    }
    startTrack(loadResult.tracks[initialIndex] as Track, initialIndex)
  }

  private void enterCampaignMode(Campaign campaign) {
    log.info('Entering campaign mode: {}', campaign.name)
    campaignMode = true
    loadedCampaign = campaign
    ProgressStore store = new ProgressStore()
    campaignService = new CampaignService(campaign, store)
    campaignProgress = campaignService.loadProgress()
    currentTrackIndex = -1
    controlPanel.updateGoal('Select a level from the campaign map')
    controlPanel.updateObjectives([])
    controlPanel.updateScore(0)
    controlPanel.updateMovesLeft(0)
    controlPanel.updateSpecials([:])
    boardPanel.updateBoard(null)
    rightPanelCardLayout.show(rightPanel, CARD_CAMPAIGN_MAP)
    refreshCampaignMap(campaign)
    updateModeToggleButton()
  }

  private void exitCampaignMode() {
    campaignMode = false
    rightPanelCardLayout.show(rightPanel, CARD_TRACK_LIST)
    updateModeToggleButton()
  }

  private void toggleMode() {
    if (campaignMode) {
      campaignMode = false
      rightPanelCardLayout.show(rightPanel, CARD_TRACK_LIST)
      if (!currentLoadResult.tracks.isEmpty()) {
        int index = currentTrackIndex >= 0 && currentTrackIndex < currentLoadResult.tracks.size()
            ? currentTrackIndex : 0
        startTrack(currentLoadResult.tracks[index] as Track, index)
      }
    } else if (loadedCampaign != null) {
      campaignMode = true
      rightPanelCardLayout.show(rightPanel, CARD_CAMPAIGN_MAP)
      refreshCampaignMap(loadedCampaign)
      currentTrackIndex = -1
      controlPanel.updateGoal('Select a level from the campaign map')
      controlPanel.updateObjectives([])
      controlPanel.updateScore(0)
      controlPanel.updateMovesLeft(0)
      controlPanel.updateSpecials([:])
      boardPanel.updateBoard(null)
    }
    updateModeToggleButton()
  }

  private void updateModeToggleButton() {
    if (modeToggleButton == null) {
      return
    }
    modeToggleButton.enabled = loadedCampaign != null
    modeToggleButton.text = campaignMode ? 'Free Play' : 'Campaign Map'
  }

  private void refreshCampaignMap(Campaign campaign) {
    Map<String, String> trackNames = [:]
    currentLoadResult.tracks.each { Track t -> trackNames[t.id] = t.name }
    campaignMapPanel.refresh(campaign, campaignProgress,
        { level, progress -> campaignService.isUnlocked(level, progress) }, trackNames)
  }

  private void onCampaignLevelSelected(String trackId) {
    Track track = currentLoadResult.tracks.find { Track t -> t.id == trackId } as Track
    if (track == null) {
      log.warn('Campaign level trackId {} not found in loaded tracks', trackId)
      return
    }
    startTrack(track)
  }

  void startTrack(Track track) {
    int index = currentLoadResult.tracks.indexOf(track)
    startTrack(track, index)
  }

  void startTrack(Track track, int index) {
    this.currentTrackIndex = index >= 0 ? index : 0

    if (gameEngine != null) {
      gameEngine.close()
    }

    gameEngine = new GameEngine(track, new Random(), ensureGameWorker(), new UiGameListener())
    boardPanel.setGame(track, gameEngine)
    controlPanel.updateGoal(goalTextFor(track))
    controlPanel.updateObjectives(objectiveLinesFor(gameEngine.objectiveProgress))
    controlPanel.updateScore(0)
    controlPanel.updateMovesLeft(track.moves)
    controlPanel.updateSpecials(gameEngine.remainingSpecialPieces)
    updateTrackSelection(this.currentTrackIndex)
  }

  void restartCurrentTrack() {
    if (currentTrackIndex < 0 || currentTrackIndex >= currentLoadResult.tracks.size()) {
      return
    }

    Track track = currentLoadResult.tracks[currentTrackIndex] as Track
    startTrack(track, currentTrackIndex)
  }

  private void logPreviousAndCurrentBoardState() {
    if (gameEngine == null) {
      JOptionPane.showMessageDialog(
          this,
          'Start a track before using board debug logging.',
          'No Active Track',
          JOptionPane.INFORMATION_MESSAGE
      )
      return
    }

    gameEngine.logPreviousAndCurrentBoardState()
  }

  private void showHint() {
    if (gameEngine == null) {
      JOptionPane.showMessageDialog(
          this,
          'Start a track before requesting a hint.',
          'No Active Track',
          JOptionPane.INFORMATION_MESSAGE
      )
      return
    }
    if (gameEngine.isResolving()) {
      return
    }

    Optional<GameEngine.HintMove> hint = gameEngine.findHintMove()
    if (hint.present) {
      boardPanel.showHint(hint.get().first, hint.get().second)
    } else {
      JOptionPane.showMessageDialog(
          this,
          'No legal move found on the current board.',
          'No Hint Available',
          JOptionPane.INFORMATION_MESSAGE
      )
    }
  }

  private void skipToNextTrackOrSelection() {
    if (currentTrackIndex + 1 < currentLoadResult.tracks.size()) {
      startTrack(currentLoadResult.tracks[currentTrackIndex + 1] as Track, currentTrackIndex + 1)
    } else {
      JOptionPane.showMessageDialog(
          this,
          'You completed all loaded tracks. Select any track from the list on the right.',
          'All Tracks Completed',
          JOptionPane.INFORMATION_MESSAGE
      )
    }
  }

  private ExecutorService ensureGameWorker() {
    if (gameWorker == null || gameWorker.isShutdown()) {
      gameWorker = Executors.newSingleThreadExecutor()
    }
    gameWorker
  }

  private void shutdownWorker() {
    if (gameWorker != null) {
      gameWorker.shutdownNow()
    }
  }

  private void rememberLastTrackDirectory(Path directory) {
    if (directory == null) {
      return
    }

    try {
      preferences.put(PREF_LAST_TRACK_DIR, directory.toAbsolutePath().normalize().toString())
    } catch (SecurityException e) {
      log.debug('Unable to persist last track directory: {}', e.message)
    }
  }

  private Path readLastTrackDirectory() {
    try {
      String value = preferences.get(PREF_LAST_TRACK_DIR, null)
      if (!value) {
        return null
      }

      Path path = Path.of(value)
      if (Files.isDirectory(path)) {
        return path
      }
    } catch (Exception e) {
      log.debug('Unable to read last track directory preference: {}', e.message)
    }

    return null
  }

  private void markTrackCompleted(String trackId) {
    if (!trackId) {
      return
    }
    try {
      Set<String> completed = loadCompletedTrackIds()
      completed.add(trackId)
      preferences.put(PREF_COMPLETED_TRACKS, completed.join(','))
    } catch (SecurityException e) {
      log.debug('Unable to persist completed tracks: {}', e.message)
    }
  }

  private boolean isTrackCompleted(String trackId) {
    if (!trackId) {
      return false
    }
    return loadCompletedTrackIds().contains(trackId)
  }

  private Set<String> loadCompletedTrackIds() {
    try {
      String value = preferences.get(PREF_COMPLETED_TRACKS, '')
      if (!value) {
        return new LinkedHashSet<>()
      }
      return new LinkedHashSet<>(value.split(',').collect { it.trim() }.findAll { it })
    } catch (Exception e) {
      log.debug('Unable to read completed tracks preference: {}', e.message)
      return new LinkedHashSet<>()
    }
  }

  private void applyAppIcon() {
    InputStream iconStream = MainFrame.class.getResourceAsStream('/images/app-icon.png')
    if (iconStream == null) {
      log.debug('App icon resource not found: /images/app-icon.png')
      return
    }

    try {
      Image icon = ImageIO.read(iconStream)
      if (icon != null) {
        setIconImage(icon)
      }
    } catch (IOException e) {
      log.warn('Failed to load app icon: {}', e.message)
    } finally {
      iconStream.close()
    }
  }

  private void configureTrackList() {
    trackList.selectionMode = ListSelectionModel.SINGLE_SELECTION
    trackList.visibleRowCount = 16
    trackList.background = LIST_BACKGROUND
    trackList.foreground = TEXT_COLOR
    trackList.selectionBackground = LIST_SELECTION
    trackList.selectionForeground = Color.WHITE
    trackList.addListSelectionListener { event ->
      if (event.valueIsAdjusting || updatingTrackListSelection) {
        return
      }

      int selectedIndex = trackList.selectedIndex
      if (selectedIndex < 0 || selectedIndex >= currentLoadResult.tracks.size()) {
        return
      }
      if (selectedIndex == currentTrackIndex) {
        return
      }

      startTrack(currentLoadResult.tracks[selectedIndex] as Track, selectedIndex)
    }
  }

  private JPanel buildTrackListPanel() {
    JPanel panel = new JPanel(new BorderLayout())
    panel.background = PANEL_BACKGROUND
    panel.preferredSize = new Dimension(260, 0)
    panel.minimumSize = new Dimension(220, 0)
    JLabel titleLabel = new JLabel('Tracks')
    titleLabel.foreground = TEXT_COLOR
    panel.add(titleLabel, BorderLayout.NORTH)

    JScrollPane trackScroll = new JScrollPane(trackList)
    trackScroll.viewport.background = LIST_BACKGROUND
    trackScroll.background = PANEL_BACKGROUND
    panel.add(trackScroll, BorderLayout.CENTER)

    panel
  }

  private JPanel buildTrackActionButtons() {
    chooseTrackFolderButton.addActionListener { chooseTrackFolder() }
    createTrackButton.addActionListener { createTrackInCurrentDirectory() }
    editTrackButton.addActionListener { editCurrentTrack() }

    JPanel topRow = new JPanel(new GridLayout(1, 3, 6, 0))
    topRow.background = PANEL_BACKGROUND
    styleTrackActionButton(chooseTrackFolderButton)
    styleTrackActionButton(createTrackButton)
    styleTrackActionButton(editTrackButton)
    topRow.add(chooseTrackFolderButton)
    topRow.add(createTrackButton)
    topRow.add(editTrackButton)

    modeToggleButton = new JButton('Campaign Map')
    modeToggleButton.enabled = false
    styleTrackActionButton(modeToggleButton)
    modeToggleButton.addActionListener { toggleMode() }

    JPanel bottomRow = new JPanel(new GridLayout(1, 1))
    bottomRow.background = PANEL_BACKGROUND
    bottomRow.add(modeToggleButton)

    JPanel actions = new JPanel(new GridLayout(2, 1, 0, 4))
    actions.background = PANEL_BACKGROUND
    actions.add(topRow)
    actions.add(bottomRow)
    actions
  }

  private static void styleTrackActionButton(JButton button) {
    button.opaque = true
    button.margin = new Insets(2, 6, 2, 6)
    button.preferredSize = new Dimension(118, 28)
    button.background = new Color(0x5A5A5A)
    button.foreground = Color.WHITE
    button.focusPainted = false
  }

  private void createTrackInCurrentDirectory() {
    Path directory = currentTrackDirectory ?: readLastTrackDirectory()
    if (directory == null || !Files.isDirectory(directory)) {
      JOptionPane.showMessageDialog(
          this,
          'Choose a track folder first before creating a track.',
          'No Track Folder',
          JOptionPane.INFORMATION_MESSAGE
      )
      return
    }

    Set<String> existingIds = loadExistingTrackIds(directory)
    TrackCreationDialog dialog = new TrackCreationDialog(directory, existingIds)
    Optional<TrackCreationDialog.CreatedTrack> created = dialog.showAndCreate(this)
    if (!created.present) {
      return
    }

    log.info('Created track {} at {}', created.get().id, created.get().file)
    loadTrackDirectory(directory)
    Track createdTrack = currentLoadResult.tracks.find { Track t -> t.id == created.get().id } as Track
    if (createdTrack != null) {
      startTrack(createdTrack)
    }
  }

  private void editCurrentTrack() {
    if (currentTrackIndex < 0 || currentTrackIndex >= currentLoadResult.tracks.size()) {
      JOptionPane.showMessageDialog(
          this,
          'Select a track to edit first.',
          'No Track Selected',
          JOptionPane.INFORMATION_MESSAGE
      )
      return
    }

    Track track = currentLoadResult.tracks[currentTrackIndex] as Track
    Path file = track.sourcePath
    if (file == null || !Files.isRegularFile(file)) {
      JOptionPane.showMessageDialog(
          this,
          'Cannot determine the source file for this track.',
          'Edit Track',
          JOptionPane.WARNING_MESSAGE
      )
      return
    }

    Path directory = file.parent
    Set<String> existingIds = loadExistingTrackIds(directory)
    TrackCreationDialog dialog = new TrackCreationDialog(directory, existingIds)
    Optional<TrackCreationDialog.CreatedTrack> edited = dialog.showAndEdit(this, track, file)
    if (!edited.present) {
      return
    }

    log.info('Edited track {} at {}', edited.get().id, edited.get().file)
    String editedId = edited.get().id
    loadTrackDirectory(directory)
    Track reloadedTrack = currentLoadResult.tracks.find { Track t -> t.id == editedId } as Track
    if (reloadedTrack != null) {
      startTrack(reloadedTrack)
    }
  }

  private static Set<String> loadExistingTrackIds(Path directory) {
    Set<String> ids = new LinkedHashSet<>()
    if (directory == null || !Files.isDirectory(directory)) {
      return ids
    }

    try (Stream<Path> pathStream = Files.list(directory)) {
      pathStream
          .filter { Path path -> Files.isRegularFile(path) && path.fileName.toString().toLowerCase(Locale.ROOT).endsWith('.json') }
          .forEach { Path file ->
            try {
              Object parsed = new JsonSlurper().parse(file.toFile())
              if (parsed instanceof Map && (parsed as Map).id != null) {
                String id = (parsed as Map).id.toString().trim()
                if (id) {
                  ids.add(id)
                }
              }
            } catch (Exception ignored) {
            }
          }
    }
    ids
  }

  private void refreshTrackList(LoadResult loadResult) {
    trackListModel.clear()
    loadResult.tracks.each { Track track ->
      trackListModel.addElement("${track.name} (${track.id})")
    }
    if (trackListModel.isEmpty()) {
      updateTrackSelection(-1)
    }
  }

  private void updateTrackSelection(int index) {
    updatingTrackListSelection = true
    try {
      if (index >= 0 && index < trackListModel.size()) {
        trackList.selectedIndex = index
        trackList.ensureIndexIsVisible(index)
      } else {
        trackList.clearSelection()
      }
    } finally {
      updatingTrackListSelection = false
    }
  }

  private static String goalTextFor(Track track) {
    if (track.hasObjectives()) {
      return "Complete all objectives in ${track.moves} moves"
    }

    String scoringText
    if (track.scoresAllColors()) {
      scoringText = 'all colors score'
    } else if (track.scoreColors.size() == 1) {
      scoringText = "only ${track.scoreColors.first().name()} scores"
    } else {
      scoringText = "only ${track.scoreColors*.name().sort().join(', ')} score"
    }
    return "Reach ${track.targetScore} points in ${track.moves} moves (${scoringText})"
  }

  private static List<String> objectiveLinesFor(List<GameEngine.ObjectiveProgress> progress) {
    if (progress == null || progress.isEmpty()) {
      return []
    }

    return progress.collect { GameEngine.ObjectiveProgress objectiveProgress ->
      String prefix = objectiveProgress.complete ? '[x]' : '[ ]'
      String label
      switch (objectiveProgress.objective.type) {
        case ObjectiveType.SCORE:
          label = 'Score'
          break
        case ObjectiveType.CLEAR_BLOCKER:
          label = objectiveProgress.objective.blockerType == null
              ? 'Clear blockers'
              : "Clear ${objectiveProgress.objective.blockerType.name()} blockers"
          break
        case ObjectiveType.COLLECT_COLOR:
          label = "Collect ${objectiveProgress.objective.color.name()}"
          break
        case ObjectiveType.DROP_INGREDIENT:
          label = 'Drop ingredients'
          break
        default:
          label = objectiveProgress.objective.type.name()
      }
      "${prefix} ${label}: ${objectiveProgress.current}/${objectiveProgress.target}"
    }
  }

  private class UiGameListener implements GameListener {
    @Override
    void onBoardUpdated(Board board) {
      SwingUtilities.invokeLater {
        boardPanel.updateBoard(board)
        controlPanel.updateMovesLeft(gameEngine.movesLeft)
        controlPanel.updateSpecials(gameEngine.remainingSpecialPieces)
        controlPanel.updateObjectives(objectiveLinesFor(gameEngine.objectiveProgress))
      }
    }

    @Override
    void onScoreChanged(int score) {
      SwingUtilities.invokeLater {
        controlPanel.updateScore(score)
        controlPanel.updateMovesLeft(gameEngine.movesLeft)
        controlPanel.updateSpecials(gameEngine.remainingSpecialPieces)
        controlPanel.updateObjectives(objectiveLinesFor(gameEngine.objectiveProgress))
      }
    }

    @Override
    void onSpecialActivated(se.alipsa.games.sc.core.SpecialPieceType specialType,
                            se.alipsa.games.sc.core.Position origin,
                            boolean sweeperHorizontal) {
      SwingUtilities.invokeLater {
        boardPanel.onSpecialActivated(specialType, origin, sweeperHorizontal)
      }
    }

    @Override
    void onFishLaunched(se.alipsa.games.sc.core.Position origin,
                        se.alipsa.games.sc.core.Position target) {
      SwingUtilities.invokeLater {
        boardPanel.onFishLaunched(origin, target)
      }
    }

    @Override
    void onBombBeam(se.alipsa.games.sc.core.Position origin,
                    se.alipsa.games.sc.core.Position target) {
      SwingUtilities.invokeLater {
        boardPanel.onBombBeam(origin, target)
      }
    }

    @Override
    void onIngredientCollected(IngredientType type, se.alipsa.games.sc.core.Position exitCell) {
      SwingUtilities.invokeLater {
        controlPanel.updateObjectives(objectiveLinesFor(gameEngine.objectiveProgress))
      }
    }

    @Override
    void onSpawnerActivated(se.alipsa.games.sc.core.Position position, SpawnKind kind, String type) {
    }

    @Override
    void onGameOver(GameOutcome outcome, int finalScore, int movesLeft) {
      SwingUtilities.invokeLater {
        Track currentTrack = currentLoadResult.tracks[currentTrackIndex] as Track

        if (campaignMode && outcome == GameOutcome.WIN) {
          markTrackCompleted(currentTrack.id)
          campaignService.recordWin(currentTrack.id, finalScore, currentTrack.targetScore, campaignProgress)
          gameOverDialog.showForWin(MainFrame.this, currentTrackIndex, currentLoadResult.tracks.size())
          refreshCampaignMap(campaignService.campaign)

          CampaignLevel nextLevel = campaignService.findNextLevel(currentTrack.id)
          if (nextLevel != null && campaignService.isUnlocked(nextLevel, campaignProgress)) {
            onCampaignLevelSelected(nextLevel.trackId)
          } else {
            controlPanel.updateGoal('Select a level from the campaign map')
            controlPanel.updateObjectives([])
            controlPanel.updateMovesLeft(0)
            controlPanel.updateSpecials([:])
            controlPanel.updateScore(0)
            boardPanel.updateBoard(null)
          }
          return
        }

        if (campaignMode) {
          boolean completed = campaignProgress.isCompleted(currentTrack.id)
          GameOverDialog.Action action = gameOverDialog.showForLose(MainFrame.this, completed)
          if (action == GameOverDialog.Action.RETRY) {
            restartCurrentTrack()
          } else {
            refreshCampaignMap(campaignService.campaign)
            controlPanel.updateGoal('Select a level from the campaign map')
            controlPanel.updateObjectives([])
            controlPanel.updateMovesLeft(0)
            controlPanel.updateSpecials([:])
            controlPanel.updateScore(0)
            boardPanel.updateBoard(null)
          }
          return
        }

        if (outcome == GameOutcome.WIN) {
          markTrackCompleted(currentTrack.id)
          GameOverDialog.Action action = gameOverDialog.showForWin(MainFrame.this,
              currentTrackIndex,
              currentLoadResult.tracks.size())
          if (action == GameOverDialog.Action.NEXT_TRACK) {
            skipToNextTrackOrSelection()
          } else {
            TrackSelectionDialog selectionDialog = new TrackSelectionDialog(currentLoadResult)
            Optional<Track> selectedTrack = selectionDialog.showAndSelect(MainFrame.this)
            if (selectedTrack.present) {
              startTrack(selectedTrack.get())
            }
          }
        } else {
          boolean completed = isTrackCompleted(currentTrack.id)
          GameOverDialog.Action action = gameOverDialog.showForLose(MainFrame.this, completed)
          if (action == GameOverDialog.Action.RETRY) {
            restartCurrentTrack()
          } else {
            skipToNextTrackOrSelection()
          }
        }
      }
    }

    @Override
    void onReshuffleExhausted() {
      SwingUtilities.invokeLater {
        if (campaignMode) {
          String message = 'Unable to continue this level (exhausted all attempts); would you like to restart or return to the campaign map?'
          Object[] options = ['Restart', 'Campaign Map']
          int choice = JOptionPane.showOptionDialog(
              MainFrame.this,
              message,
              'Reshuffle Exhausted',
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.WARNING_MESSAGE,
              null,
              options,
              options[0]
          )
          if (choice == 0) {
            restartCurrentTrack()
          } else {
            refreshCampaignMap(campaignService.campaign)
            controlPanel.updateGoal('Select a level from the campaign map')
            controlPanel.updateObjectives([])
            controlPanel.updateMovesLeft(0)
            controlPanel.updateSpecials([:])
            controlPanel.updateScore(0)
            boardPanel.updateBoard(null)
          }
          return
        }

        String message = 'Unable to continue this track (exhausted all attempts); would you like to restart or continue to the next track?'
        Object[] options = ['Restart', 'Next Track']
        int choice = JOptionPane.showOptionDialog(
            MainFrame.this,
            message,
            'Reshuffle Exhausted',
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        )

        if (choice == 0) {
          restartCurrentTrack()
        } else {
          skipToNextTrackOrSelection()
        }
      }
    }
  }
}
