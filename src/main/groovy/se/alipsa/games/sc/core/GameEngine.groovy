package se.alipsa.games.sc.core

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import se.alipsa.games.sc.core.BlockerType
import se.alipsa.games.sc.model.Objective
import se.alipsa.games.sc.model.ObjectiveType
import se.alipsa.games.sc.model.Track

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class GameEngine implements AutoCloseable, GameSession {

  private static final Logger log = LogManager.getLogger(GameEngine)
  private static final GameListener NO_OP_LISTENER = new NoOpGameListener()

  final Track track
  private final MatchFinder matchFinder
  private final BoardResolver boardResolver
  private final ExecutorService gameWorker
  private final boolean ownsWorker

  private final AtomicBoolean resolvingFlag = new AtomicBoolean(false)

  private volatile GameListener listener
  private volatile Board board
  private volatile Board previousBoardSnapshot
  private volatile Board currentBoardSnapshot
  private volatile int score = 0
  private volatile int movesLeft
  private volatile boolean gameOver = false
  private final Map<SpecialPieceType, Integer> remainingSpecialPieces
  private final List<ObjectiveProgressState> objectiveStates
  private final Object objectiveLock = new Object()

  GameEngine(Track track,
             Random random = new Random(),
             ExecutorService gameWorker = null,
             GameListener listener = null) {
    this(
        track,
        new BoardResolver(random, new MatchFinder(), new GravityRefill()),
        new MatchFinder(),
        gameWorker,
        listener
    )
  }

  GameEngine(Track track,
             BoardResolver boardResolver,
             MatchFinder matchFinder,
             ExecutorService gameWorker,
             GameListener listener = null) {
    this.track = track
    this.boardResolver = boardResolver
    this.matchFinder = matchFinder
    this.gameWorker = gameWorker ?: Executors.newSingleThreadExecutor()
    this.ownsWorker = gameWorker == null
    this.listener = listener ?: NO_OP_LISTENER
    this.movesLeft = track.moves
    this.remainingSpecialPieces = new EnumMap<>(track.specialPieces ?: [:])
    this.objectiveStates = (track.objectives ?: []).collect { Objective objective ->
      new ObjectiveProgressState(objective)
    }
    this.board = boardResolver.createInitialBoard(track, this.listener)
    this.currentBoardSnapshot = this.board?.clone()
  }

  Future<Boolean> submitSwap(int x1, int y1, int x2, int y2) {
    if (gameOver) {
      return CompletableFuture.completedFuture(false)
    }
    if (!resolvingFlag.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(false)
    }

    return gameWorker.submit({
      try {
        return processSwap(x1, y1, x2, y2)
      } finally {
        resolvingFlag.set(false)
      }
    } as Callable<Boolean>)
  }

  Board snapshotBoard() {
    board.clone()
  }

  int getScore() {
    score
  }

  int getMovesLeft() {
    movesLeft
  }

  Map<SpecialPieceType, Integer> getRemainingSpecialPieces() {
    return Collections.unmodifiableMap(new EnumMap<>(remainingSpecialPieces))
  }

  List<ObjectiveProgress> getObjectiveProgress() {
    synchronized (objectiveLock) {
      return objectiveStates.collect { ObjectiveProgressState state -> state.snapshot() }
    }
  }

  boolean isGameOver() {
    gameOver
  }

  boolean isResolving() {
    resolvingFlag.get()
  }

  void setListener(GameListener listener) {
    this.listener = listener ?: NO_OP_LISTENER
  }

  void logPreviousAndCurrentBoardState() {
    Board previous = previousBoardSnapshot?.clone()
    Board current = (currentBoardSnapshot ?: board)?.clone()
    log.info(
        'Board debug [manual snapshot], totalScore={}, movesLeft={}\nPrevious:\n{}\nCurrent:\n{}',
        score,
        movesLeft,
        formatBoard(previous),
        formatBoard(current)
    )
  }

  Optional<HintMove> findHintMove() {
    if (board == null || gameOver || resolvingFlag.get()) {
      return Optional.empty()
    }

    Board snapshot = board.clone()
    for (int y = 0; y < snapshot.height; y++) {
      for (int x = 0; x < snapshot.width; x++) {
        if (!snapshot.isPlayable(x, y) || snapshot.getPiece(x, y) == null) {
          continue
        }
        if (x + 1 < snapshot.width && isLegalHintSwap(snapshot, x, y, x + 1, y)) {
          return Optional.of(new HintMove(new Position(x, y), new Position(x + 1, y)))
        }
        if (y + 1 < snapshot.height && isLegalHintSwap(snapshot, x, y, x, y + 1)) {
          return Optional.of(new HintMove(new Position(x, y), new Position(x, y + 1)))
        }
      }
    }

    return Optional.empty()
  }

  void shutdown() {
    if (ownsWorker) {
      gameWorker.shutdown()
    }
  }

  @Override
  void close() {
    shutdown()
  }

  private boolean processSwap(int x1, int y1, int x2, int y2) {
    Board beforeBoard = board.clone()
    if (gameOver) {
      return false
    }

    if (!board.inBounds(x1, y1) || !board.inBounds(x2, y2) || !isOrthogonallyAdjacent(x1, y1, x2, y2)) {
      return false
    }
    if (!board.isPlayable(x1, y1) || !board.isPlayable(x2, y2)) {
      return false
    }

    Piece firstBefore = board.getPiece(x1, y1)
    Piece secondBefore = board.getPiece(x2, y2)
    if (firstBefore == null || secondBefore == null) {
      return false
    }
    boolean swapContainsSwapTriggeredSpecial = isSwapTriggeredSpecial(firstBefore) || isSwapTriggeredSpecial(secondBefore)
    boolean swapIsSpecialCombo = isSpecialSwapCombo(firstBefore, secondBefore)

    Board preview = board.clone()
    preview.swap(x1, y1, x2, y2)
    if (!swapContainsSwapTriggeredSpecial && !swapIsSpecialCombo && matchFinder.findMatchGroups(preview).isEmpty()) {
      return false
    }

    board.swap(x1, y1, x2, y2)
    movesLeft--

    Set<Position> forcedActivations = [] as Set<Position>
    BoardResolver.SpecialSwapCombo specialSwapCombo = null
    if (swapIsSpecialCombo) {
      specialSwapCombo = new BoardResolver.SpecialSwapCombo(
          new Position(x1, y1), board.getPiece(x1, y1),
          new Position(x2, y2), board.getPiece(x2, y2)
      )
    } else {
      forcedActivations = [new Position(x2, y2)] as Set<Position>
      forcedActivations << new Position(x1, y1)
      if (isSwapTriggeredSpecial(firstBefore)) {
        forcedActivations << new Position(x2, y2)
      }
      if (isSwapTriggeredSpecial(secondBefore)) {
        forcedActivations << new Position(x1, y1)
      }
    }

    BoardResolver.CascadeResult cascadeResult
    if (hasAnySpecialBudgetRemaining() || swapContainsSwapTriggeredSpecial || swapIsSpecialCombo) {
      cascadeResult = boardResolver.resolve(board, track.spawnWeights, remainingSpecialPieces, forcedActivations, specialSwapCombo, listener)
    } else {
      cascadeResult = boardResolver.resolve(board, track.spawnWeights, listener)
    }
    int gainedScore = scoreCascade(cascadeResult)
    score += gainedScore
    updateObjectiveProgress(cascadeResult)

    listener.onBoardUpdated(board.clone())
    listener.onScoreChanged(score)

    previousBoardSnapshot = beforeBoard
    currentBoardSnapshot = board.clone()

    evaluateTerminalState()
    return true
  }

  private static String formatBoard(Board board) {
    if (board == null) {
      return '<null>'
    }

    StringBuilder out = new StringBuilder()
    out.append('y\\x ').append(' ')
    for (int x = 0; x < board.width; x++) {
      out.append(x.toString().padRight(4))
    }
    out.append('\n')
    for (int y = 0; y < board.height; y++) {
      out.append(y.toString().padRight(4))
      for (int x = 0; x < board.width; x++) {
        if (!board.isPlayable(x, y)) {
          out.append('##'.padRight(6))
        } else {
          out.append(tokenFor(board.getPiece(x, y), board.getBlocker(x, y)).padRight(6))
        }
      }
      if (y < board.height - 1) {
        out.append('\n')
      }
    }
    out.toString()
  }

  private static String tokenFor(Piece piece, Blocker blocker = null) {
    String base
    if (piece == null) {
      base = '__'
    } else {
      String color = piece.color?.name()?.substring(0, 1) ?: '?'
      if (!piece.isSpecial()) {
        base = color
      } else {
        switch (piece.specialType) {
          case SpecialPieceType.SWEEPER:
            base = color + (piece.sweeperHorizontal ? 'SH' : 'SV')
            break
          case SpecialPieceType.SMALL_BOMB:
            base = color + 'SB'
            break
          case SpecialPieceType.BOMB:
            base = color + 'BM'
            break
          case SpecialPieceType.FISH:
            base = color + 'FS'
            break
          default:
            base = color + '??'
        }
      }
    }

    if (blocker == null) {
      return base
    }
    String blockerTag = blocker.type.name().substring(0, 1) + blocker.layers
    "${base}|${blockerTag}"
  }

  private void evaluateTerminalState() {
    if (gameOver) {
      return
    }

    if (hasMetWinCondition()) {
      gameOver = true
      listener.onGameOver(GameOutcome.WIN, score, movesLeft)
      return
    }

    if (movesLeft <= 0) {
      gameOver = true
      listener.onGameOver(GameOutcome.LOSE, score, movesLeft)
    }
  }

  private static boolean isOrthogonallyAdjacent(int x1, int y1, int x2, int y2) {
    Math.abs(x1 - x2) + Math.abs(y1 - y2) == 1
  }

  private boolean isLegalHintSwap(Board candidateBoard, int x1, int y1, int x2, int y2) {
    if (!candidateBoard.isPlayable(x1, y1) || !candidateBoard.isPlayable(x2, y2)) {
      return false
    }
    Piece first = candidateBoard.getPiece(x1, y1)
    Piece second = candidateBoard.getPiece(x2, y2)
    if (first == null || second == null) {
      return false
    }
    boolean swapContainsSwapTriggeredSpecial = isSwapTriggeredSpecial(first) || isSwapTriggeredSpecial(second)
    boolean swapIsSpecialCombo = isSpecialSwapCombo(first, second)
    if (swapContainsSwapTriggeredSpecial || swapIsSpecialCombo) {
      return true
    }

    candidateBoard.swap(x1, y1, x2, y2)
    boolean hasMatch = !matchFinder.findMatchGroups(candidateBoard).isEmpty()
    candidateBoard.swap(x1, y1, x2, y2)
    hasMatch
  }

  private static boolean isSwapTriggeredSpecial(Piece piece) {
    piece?.isSpecial() && piece.specialType != SpecialPieceType.SWEEPER
  }

  private static boolean isSpecialSwapCombo(Piece first, Piece second) {
    first?.isSpecial() && second?.isSpecial()
  }

  private boolean hasAnySpecialBudgetRemaining() {
    remainingSpecialPieces.values().any { int value -> value > 0 }
  }

  private int scoreCascade(BoardResolver.CascadeResult cascadeResult) {
    int gainedScore = 0
    for (int i = 0; i < cascadeResult.groupSizes.size(); i++) {
      int groupSize = cascadeResult.groupSizes[i]
      Map<CandyType, Integer> groupCounts = i < cascadeResult.groupCandyCounts.size()
          ? cascadeResult.groupCandyCounts[i]
          : [:]
      gainedScore += scoreGroup(groupSize, groupCounts)
    }
    return gainedScore
  }

  private int scoreGroup(int groupSize, Map<CandyType, Integer> groupCounts) {
    if (track.scoresAllColors() || groupCounts == null || groupCounts.isEmpty()) {
      return groupSize * 10
    }

    int eligibleCandyCount = track.scoreColors.sum { CandyType type ->
      groupCounts.getOrDefault(type, 0)
    } as int
    return eligibleCandyCount * 10
  }

  private boolean hasMetWinCondition() {
    if (objectiveStates.isEmpty()) {
      return score >= track.targetScore
    }
    synchronized (objectiveLock) {
      objectiveStates.every { ObjectiveProgressState state -> state.complete }
    }
  }

  private void updateObjectiveProgress(BoardResolver.CascadeResult cascadeResult) {
    if (objectiveStates.isEmpty()) {
      return
    }

    Map<CandyType, Integer> clearedCandies = new EnumMap<>(CandyType)
    cascadeResult.groupCandyCounts.each { Map<CandyType, Integer> group ->
      group.each { CandyType candyType, Integer count ->
        clearedCandies[candyType] = clearedCandies.getOrDefault(candyType, 0) + (count ?: 0)
      }
    }
    Map<BlockerType, Integer> clearedBlockers = cascadeResult.clearedBlockers ?: [:]

    synchronized (objectiveLock) {
      objectiveStates.each { ObjectiveProgressState state ->
        switch (state.objective.type) {
          case ObjectiveType.SCORE:
            state.updateProgress(score)
            break
          case ObjectiveType.COLLECT_COLOR:
            int collected = state.objective.color == null ? 0 : clearedCandies.getOrDefault(state.objective.color, 0)
            state.increment(collected)
            break
          case ObjectiveType.CLEAR_BLOCKER:
            int cleared = state.objective.blockerType == null
                ? (clearedBlockers.values().sum(0) as int)
                : clearedBlockers.getOrDefault(state.objective.blockerType, 0)
            state.increment(cleared)
            break
        }
      }
    }
  }

  private static final class NoOpGameListener implements GameListener {
    @Override
    void onBoardUpdated(Board board) {
    }

    @Override
    void onScoreChanged(int score) {
    }

    @Override
    void onGameOver(GameOutcome outcome, int finalScore, int movesLeft) {
    }

    @Override
    void onReshuffleExhausted() {
    }
  }

  static final class HintMove {
    final Position first
    final Position second

    HintMove(Position first, Position second) {
      this.first = first
      this.second = second
    }
  }

  static final class ObjectiveProgress {
    final Objective objective
    final int current
    final int target
    final boolean complete

    ObjectiveProgress(Objective objective, int current, int target, boolean complete) {
      this.objective = objective
      this.current = current
      this.target = target
      this.complete = complete
    }
  }

  private static final class ObjectiveProgressState {
    final Objective objective
    int current

    ObjectiveProgressState(Objective objective) {
      this.objective = objective
      this.current = 0
    }

    void increment(int delta) {
      if (delta > 0) {
        updateProgress(current + delta)
      }
    }

    void updateProgress(int nextValue) {
      current = Math.max(0, Math.min(nextValue, objective.target))
    }

    boolean isComplete() {
      current >= objective.target
    }

    ObjectiveProgress snapshot() {
      new ObjectiveProgress(objective, current, objective.target, isComplete())
    }
  }
}
