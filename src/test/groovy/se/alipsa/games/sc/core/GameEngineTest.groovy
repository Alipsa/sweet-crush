package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.Track
import spock.lang.Specification

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GameEngineTest extends Specification {

  private ExecutorService worker

  def cleanup() {
    if (worker != null) {
      worker.shutdownNow()
      worker.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  def 'accepts legal orthogonal swap, decrements moves, and updates score'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    RecordingListener listener = new RecordingListener()
    StubBoardResolver resolver = new StubBoardResolver(legalSwapBoard(), [3])
    GameEngine engine = new GameEngine(track(5, 500), resolver, new MatchFinder(), worker, listener)

    when:
    boolean success = engine.submitSwap(0, 1, 1, 1).get(2, TimeUnit.SECONDS)

    then:
    success
    engine.movesLeft == 4
    engine.score == 30
    listener.boardUpdatedCount == 1
    listener.scoreChangedCount == 1
  }

  def 'rejects illegal swaps (non-adjacent and adjacent without resulting match)'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(legalSwapBoard(), [3])
    GameEngine engine = new GameEngine(track(5, 500), resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    boolean nonAdjacent = engine.submitSwap(0, 0, 2, 2).get(2, TimeUnit.SECONDS)
    boolean noMatch = engine.submitSwap(1, 0, 2, 0).get(2, TimeUnit.SECONDS)

    then:
    !nonAdjacent
    !noMatch
    engine.movesLeft == 5
    engine.score == 0
  }

  def 'findHintMove returns a legal adjacent swap'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(legalSwapBoard(), [3])
    GameEngine engine = new GameEngine(track(5, 500), resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    Optional<GameEngine.HintMove> hint = engine.findHintMove()

    then:
    hint.present
    Math.abs(hint.get().first.x - hint.get().second.x) + Math.abs(hint.get().first.y - hint.get().second.y) == 1
  }

  def 'applies win-before-lose precedence when score target reached on final move'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    RecordingListener listener = new RecordingListener()
    StubBoardResolver resolver = new StubBoardResolver(legalSwapBoard(), [3])
    GameEngine engine = new GameEngine(track(1, 30), resolver, new MatchFinder(), worker, listener)

    when:
    boolean success = engine.submitSwap(0, 1, 1, 1).get(2, TimeUnit.SECONDS)

    then:
    success
    engine.gameOver
    engine.movesLeft == 0
    listener.outcomes == [GameOutcome.WIN]
  }

  def 'emits lose event when moves reach zero without hitting target score'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    RecordingListener listener = new RecordingListener()
    StubBoardResolver resolver = new StubBoardResolver(legalSwapBoard(), [3])
    GameEngine engine = new GameEngine(track(1, 1000), resolver, new MatchFinder(), worker, listener)

    when:
    boolean success = engine.submitSwap(0, 1, 1, 1).get(2, TimeUnit.SECONDS)

    then:
    success
    engine.gameOver
    engine.movesLeft == 0
    listener.outcomes == [GameOutcome.LOSE]
  }

  def 'scores using grouped clear sizes across cascade steps'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(legalSwapBoard(), [3, 5, 4])
    GameEngine engine = new GameEngine(track(5, 9999), resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    boolean success = engine.submitSwap(0, 1, 1, 1).get(2, TimeUnit.SECONDS)

    then:
    success
    engine.score == (3 + 5 + 4) * 10
    engine.movesLeft == 4
  }

  def 'scores only configured colors when scoreColors is restricted'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(
        legalSwapBoard(),
        [5],
        [[
            (CandyType.RED)  : 2,
            (CandyType.BLUE) : 3
        ]]
    )
    Track redOnlyTrack = track(5, 9999, [(CandyType.RED)] as Set<CandyType>)
    GameEngine engine = new GameEngine(redOnlyTrack, resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    boolean success = engine.submitSwap(0, 1, 1, 1).get(2, TimeUnit.SECONDS)

    then:
    success
    engine.score == 20
  }

  def 'accepts swap with non-sweeper special piece even when no normal match is formed'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(specialSwapBoard(), [3])
    GameEngine engine = new GameEngine(track(5, 9999), resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    boolean success = engine.submitSwap(0, 0, 1, 0).get(2, TimeUnit.SECONDS)

    then:
    success
    resolver.lastForcedActivations.contains(new Position(1, 0))
  }

  def 'passes special combo metadata when swapping two special pieces'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(twoSpecialComboBoard(), [3])
    GameEngine engine = new GameEngine(track(5, 9999), resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    boolean success = engine.submitSwap(0, 0, 1, 0).get(2, TimeUnit.SECONDS)

    then:
    success
    resolver.lastForcedActivations.isEmpty()
    resolver.lastSpecialSwapCombo != null
    resolver.lastSpecialSwapCombo.firstPiece?.isSpecial()
    resolver.lastSpecialSwapCombo.secondPiece?.isSpecial()
  }

  def 'rejects swap with sweeper when swap does not create a match'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(sweeperNoMatchBoard(), [3])
    GameEngine engine = new GameEngine(track(5, 9999), resolver, new MatchFinder(), worker, new RecordingListener())

    when:
    boolean success = engine.submitSwap(0, 0, 1, 0).get(2, TimeUnit.SECONDS)

    then:
    !success
    engine.movesLeft == 5
    resolver.lastForcedActivations.isEmpty()
  }

  def 'allows swapping sweeper into a real line match and passes destination anchor hint'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    StubBoardResolver resolver = new StubBoardResolver(sweeperMatchBoard(), [6])
    GameEngine engine = new GameEngine(
        track(5, 9999, null, [(SpecialPieceType.SWEEPER): 1]),
        resolver,
        new MatchFinder(),
        worker,
        new RecordingListener()
    )

    when:
    boolean success = engine.submitSwap(1, 1, 1, 0).get(2, TimeUnit.SECONDS)

    then:
    success
    resolver.lastForcedActivations == [new Position(1, 0), new Position(1, 1)] as Set<Position>
  }

  def 'activates swapped small bomb and clears cross area'() {
    given:
    worker = Executors.newSingleThreadExecutor()
    BoardResolver resolver = new FixedInitialBoardResolver(smallBombSwapBoard(), 23L)
    GameEngine engine = new GameEngine(track(5, 5, 5, 9999), resolver, new MatchFinder(), worker, new RecordingListener())
    Board before = engine.snapshotBoard()

    when:
    boolean success = engine.submitSwap(2, 2, 3, 2).get(2, TimeUnit.SECONDS)
    Board after = engine.snapshotBoard()

    then:
    success
    engine.score >= 50
    countChangedCells(before, after) >= 5
  }

  private static Board legalSwapBoard() {
    Board board = new Board(3, 3)

    board.setCell(0, 0, CandyType.RED)
    board.setCell(1, 0, CandyType.GREEN)
    board.setCell(2, 0, CandyType.BLUE)

    board.setCell(0, 1, CandyType.BLUE)
    board.setCell(1, 1, CandyType.RED)
    board.setCell(2, 1, CandyType.YELLOW)

    board.setCell(0, 2, CandyType.RED)
    board.setCell(1, 2, CandyType.PURPLE)
    board.setCell(2, 2, CandyType.ORANGE)

    board
  }

  private static Board specialSwapBoard() {
    Board board = new Board(3, 3)
    board.setPiece(0, 0, Piece.smallBomb(CandyType.RED))
    board.setCell(1, 0, CandyType.BLUE)
    board.setCell(2, 0, CandyType.GREEN)
    board.setCell(0, 1, CandyType.YELLOW)
    board.setCell(1, 1, CandyType.PURPLE)
    board.setCell(2, 1, CandyType.ORANGE)
    board.setCell(0, 2, CandyType.BLUE)
    board.setCell(1, 2, CandyType.GREEN)
    board.setCell(2, 2, CandyType.YELLOW)
    board
  }

  private static Board sweeperNoMatchBoard() {
    Board board = new Board(3, 3)
    board.setPiece(0, 0, Piece.sweeper(CandyType.RED, true))
    board.setCell(1, 0, CandyType.BLUE)
    board.setCell(2, 0, CandyType.GREEN)
    board.setCell(0, 1, CandyType.YELLOW)
    board.setCell(1, 1, CandyType.PURPLE)
    board.setCell(2, 1, CandyType.ORANGE)
    board.setCell(0, 2, CandyType.BLUE)
    board.setCell(1, 2, CandyType.GREEN)
    board.setCell(2, 2, CandyType.YELLOW)
    board
  }

  private static Board twoSpecialComboBoard() {
    Board board = new Board(3, 3)
    board.setPiece(0, 0, Piece.smallBomb(CandyType.RED))
    board.setPiece(1, 0, Piece.fish(CandyType.BLUE))
    board.setCell(2, 0, CandyType.GREEN)
    board.setCell(0, 1, CandyType.YELLOW)
    board.setCell(1, 1, CandyType.PURPLE)
    board.setCell(2, 1, CandyType.ORANGE)
    board.setCell(0, 2, CandyType.BLUE)
    board.setCell(1, 2, CandyType.GREEN)
    board.setCell(2, 2, CandyType.YELLOW)
    board
  }

  private static Board sweeperMatchBoard() {
    Board board = new Board(3, 3)
    board.setCell(0, 0, CandyType.RED)
    board.setCell(1, 0, CandyType.BLUE)
    board.setCell(2, 0, CandyType.RED)
    board.setCell(0, 1, CandyType.GREEN)
    board.setPiece(1, 1, Piece.sweeper(CandyType.RED, true))
    board.setCell(2, 1, CandyType.YELLOW)
    board.setCell(0, 2, CandyType.PURPLE)
    board.setCell(1, 2, CandyType.ORANGE)
    board.setCell(2, 2, CandyType.BLUE)
    board
  }

  private static Board smallBombSwapBoard() {
    Board board = new Board(5, 5)
    List<List<CandyType>> rows = [
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.RED, CandyType.BLUE, CandyType.RED],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.RED, CandyType.BLUE],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW]
    ]

    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        board.setCell(x, y, rows[y][x])
      }
    }
    board.setPiece(2, 2, Piece.smallBomb(CandyType.RED))
    board
  }

  private static Track track(int moves, int targetScore, Set<CandyType> scoreColors = null) {
    track(3, 3, moves, targetScore, scoreColors, null)
  }

  private static Track track(int moves,
                             int targetScore,
                             Set<CandyType> scoreColors,
                             Map<SpecialPieceType, Integer> specialPieces) {
    track(3, 3, moves, targetScore, scoreColors, specialPieces)
  }

  private static Track track(int width,
                             int height,
                             int moves,
                             int targetScore,
                             Set<CandyType> scoreColors = null,
                             Map<SpecialPieceType, Integer> specialPieces = null) {
    new Track('t', 't', width, height, moves, targetScore, uniformWeights(), scoreColors, specialPieces)
  }

  private static int countChangedCells(Board before, Board after) {
    int changed = 0
    for (int y = 0; y < before.height; y++) {
      for (int x = 0; x < before.width; x++) {
        Piece left = before.getPiece(x, y)
        Piece right = after.getPiece(x, y)
        if ((left?.color != right?.color) || (left?.specialType != right?.specialType)) {
          changed++
        }
      }
    }
    changed
  }

  private static Map<CandyType, Integer> uniformWeights() {
    CandyType.values().collectEntries { CandyType type ->
      [(type): 1]
    } as Map<CandyType, Integer>
  }

  private static final class StubBoardResolver extends BoardResolver {
    private final Board initialBoard
    private final List<Integer> groupSizes
    private final List<Map<CandyType, Integer>> groupCandyCounts
    Set<Position> lastForcedActivations = [] as Set<Position>
    BoardResolver.SpecialSwapCombo lastSpecialSwapCombo

    StubBoardResolver(Board initialBoard,
                     List<Integer> groupSizes,
                     List<Map<CandyType, Integer>> groupCandyCounts = []) {
      super(new Random(1L), new MatchFinder(), new GravityRefill())
      this.initialBoard = initialBoard
      this.groupSizes = groupSizes
      this.groupCandyCounts = groupCandyCounts
    }

    @Override
    Board createInitialBoard(Track track, GameListener listener = null) {
      initialBoard.clone()
    }

    @Override
    CascadeResult resolve(Board board, Map<CandyType, Integer> spawnWeights, GameListener listener = null) {
      new CascadeResult(groupSizes, groupCandyCounts)
    }

    @Override
    CascadeResult resolve(Board board,
                          Map<CandyType, Integer> spawnWeights,
                          Map<SpecialPieceType, Integer> remainingSpecialPieces,
                          Set<Position> forcedActivations,
                          BoardResolver.SpecialSwapCombo specialSwapCombo,
                          GameListener listener = null) {
      lastForcedActivations = new LinkedHashSet<>(forcedActivations ?: [])
      lastSpecialSwapCombo = specialSwapCombo
      new CascadeResult(groupSizes, groupCandyCounts)
    }
  }

  private static final class FixedInitialBoardResolver extends BoardResolver {
    private final Board initialBoard

    FixedInitialBoardResolver(Board initialBoard, long seed) {
      super(new Random(seed), new MatchFinder(), new GravityRefill())
      this.initialBoard = initialBoard
    }

    @Override
    Board createInitialBoard(Track track, GameListener listener = null) {
      initialBoard.clone()
    }
  }

  private static final class RecordingListener implements GameListener {
    int boardUpdatedCount = 0
    int scoreChangedCount = 0
    List<GameOutcome> outcomes = []

    @Override
    void onBoardUpdated(Board board) {
      boardUpdatedCount++
    }

    @Override
    void onScoreChanged(int score) {
      scoreChangedCount++
    }

    @Override
    void onGameOver(GameOutcome outcome, int finalScore, int movesLeft) {
      outcomes << outcome
    }

    @Override
    void onReshuffleExhausted() {
    }
  }
}
