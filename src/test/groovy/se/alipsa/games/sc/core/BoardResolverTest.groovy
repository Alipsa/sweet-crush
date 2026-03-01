package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.Track
import spock.lang.Specification

class BoardResolverTest extends Specification {

  def 'initial fill has no pre-match and has at least one legal swap'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(12345L))
    MatchFinder finder = new MatchFinder()
    Track track = track('init', 7, 9, 25, 3000, uniformWeights())

    when:
    Board board = resolver.createInitialBoard(track)

    then:
    finder.findMatchGroups(board).isEmpty()
    resolver.hasLegalSwap(board)
  }

  def 'initial fill respects hole mask and keeps hole cells empty'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(12346L))
    MatchFinder finder = new MatchFinder()
    List<String> mask = [
        '...#...',
        '..###..',
        '.......',
        '...#...',
        '...#...',
        '..###..',
        '.......',
        '.......',
        '...#...'
    ]
    Track track = track('masked-init', 7, 9, 25, 3000, uniformWeights(), mask)

    when:
    Board board = resolver.createInitialBoard(track)

    then:
    finder.findMatchGroups(board).isEmpty()
    resolver.hasLegalSwap(board)
    !board.isPlayable(3, 0)
    board.getPiece(3, 0) == null
    !board.isPlayable(3, 1)
    board.getPiece(3, 1) == null
  }

  def 'resolve performs clear, gravity and refill and stabilizes board'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(99L))
    MatchFinder finder = new MatchFinder()
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW]
    ])

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights())

    then:
    result.groupSizes.contains(3)
    board.nonNullCandies().size() == 9
    finder.findMatchGroups(board).isEmpty()
  }

  def 'reshuffles dead board into playable board'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(321L))
    MatchFinder finder = new MatchFinder()
    Board deadBoard = findDeadBoard(resolver, finder)

    expect:
    finder.findMatchGroups(deadBoard).isEmpty()
    !resolver.hasLegalSwap(deadBoard)

    when:
    boolean playable = resolver.ensurePlayable(deadBoard, uniformWeights())

    then:
    playable
    finder.findMatchGroups(deadBoard).isEmpty()
    resolver.hasLegalSwap(deadBoard)
  }

  def 'emits reshuffle exhausted when all reshuffle attempts fail'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(777L))
    MatchFinder finder = new MatchFinder()
    Board deadBoard = findDeadBoard(resolver, finder)
    RecordingListener listener = new RecordingListener()
    Map<CandyType, Integer> impossibleWeights = [
        (CandyType.RED)   : 1,
        (CandyType.BLUE)  : 0,
        (CandyType.GREEN) : 0,
        (CandyType.YELLOW): 0,
        (CandyType.PURPLE): 0,
        (CandyType.ORANGE): 0
    ]

    when:
    boolean playable = resolver.ensurePlayable(deadBoard, impossibleWeights, listener)

    then:
    !playable
    listener.reshuffleExhaustedCount == 1
  }

  def 'creates sweeper from 4-in-a-row when budget is available'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(11L))
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.ORANGE, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE, CandyType.GREEN]
    ])
    Map<SpecialPieceType, Integer> budgets = [(SpecialPieceType.SWEEPER): 1]

    when:
    resolver.resolve(board, uniformWeights(), budgets, [] as Set<Position>, null)

    then:
    budgets[SpecialPieceType.SWEEPER] == 0
    countSpecials(board, SpecialPieceType.SWEEPER) >= 1
  }

  def 'creates sweeper at preferred swap-destination anchor when provided'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(18L))
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.ORANGE, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE, CandyType.GREEN]
    ])
    Map<CandyType, Integer> weightsWithoutRed = [
        (CandyType.RED)   : 0,
        (CandyType.BLUE)  : 1,
        (CandyType.GREEN) : 1,
        (CandyType.YELLOW): 1,
        (CandyType.PURPLE): 1,
        (CandyType.ORANGE): 1
    ]
    Map<SpecialPieceType, Integer> budgets = [(SpecialPieceType.SWEEPER): 1]
    Position preferred = new Position(3, 0)

    when:
    resolver.resolve(board, weightsWithoutRed, budgets, [preferred] as Set<Position>, null)

    then:
    board.getPiece(preferred.x, preferred.y)?.specialType == SpecialPieceType.SWEEPER
  }

  def 'activates sweeper when it is part of a line match and clears full sweep path'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(15L))
    Board board = boardOf([
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE],
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.GREEN, CandyType.BLUE, CandyType.ORANGE, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.BLUE, CandyType.ORANGE, CandyType.GREEN, CandyType.RED]
    ])
    board.setPiece(1, 1, Piece.sweeper(CandyType.RED, true))

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights(), [:], [] as Set<Position>, null)

    then:
    !result.groupSizes.isEmpty()
    result.groupSizes.first() >= board.width
  }

  def 'does not activate sweeper from forced activation seed without a line match'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(16L))
    Board board = boardOf([
        [CandyType.RED, CandyType.GREEN, CandyType.BLUE],
        [CandyType.BLUE, CandyType.RED, CandyType.YELLOW],
        [CandyType.RED, CandyType.PURPLE, CandyType.ORANGE]
    ])
    board.setPiece(1, 1, Piece.sweeper(CandyType.RED, false))
    Board before = board.clone()

    expect:
    resolver.hasLegalSwap(board)

    when:
    BoardResolver.CascadeResult result = resolver.resolve(
        board,
        uniformWeights(),
        [:],
        [new Position(1, 1)] as Set<Position>,
        null
    )

    then:
    result.groupSizes.isEmpty()
    boardsEqual(before, board)
  }

  def 'creates bomb from 5-in-a-row when budget is available'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(12L))
    Board board = boardOf([
        [CandyType.BLUE, CandyType.BLUE, CandyType.BLUE, CandyType.BLUE, CandyType.BLUE],
        [CandyType.RED, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.RED]
    ])
    Map<SpecialPieceType, Integer> budgets = [(SpecialPieceType.BOMB): 1]

    when:
    resolver.resolve(board, uniformWeights(), budgets, [] as Set<Position>, null)

    then:
    budgets[SpecialPieceType.BOMB] == 0
    countSpecials(board, SpecialPieceType.BOMB) >= 1
  }

  def 'creates fish from 2x2 square when budget is available'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(13L))
    Board board = boardOf([
        [CandyType.YELLOW, CandyType.YELLOW, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.YELLOW, CandyType.GREEN],
        [CandyType.RED, CandyType.PURPLE, CandyType.ORANGE]
    ])
    Map<SpecialPieceType, Integer> budgets = [(SpecialPieceType.FISH): 1]

    when:
    resolver.resolve(board, uniformWeights(), budgets, [] as Set<Position>, null)

    then:
    budgets[SpecialPieceType.FISH] == 0
    countSpecials(board, SpecialPieceType.FISH) >= 1
  }

  def 'creates small bomb from T-shape when budget is available'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(14L))
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE],
        [CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE]
    ])
    Map<SpecialPieceType, Integer> budgets = [(SpecialPieceType.SMALL_BOMB): 1]

    when:
    resolver.resolve(board, uniformWeights(), budgets, [] as Set<Position>, null)

    then:
    budgets[SpecialPieceType.SMALL_BOMB] == 0
    countSpecials(board, SpecialPieceType.SMALL_BOMB) >= 1
  }

  def 'creates small bomb when fish and small bomb patterns overlap in larger intersecting group'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(141L))
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.GREEN, CandyType.ORANGE, CandyType.PURPLE]
    ])
    Map<SpecialPieceType, Integer> budgets = [
        (SpecialPieceType.SMALL_BOMB): 1,
        (SpecialPieceType.FISH)      : 1
    ]

    when:
    resolver.resolve(board, uniformWeights(), budgets, [] as Set<Position>, null)

    then:
    budgets[SpecialPieceType.SMALL_BOMB] == 0
  }

  def 'creates only one special from an overlapping match group in a single cascade step'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(143L))
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE, CandyType.YELLOW, CandyType.GREEN],
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE, CandyType.YELLOW, CandyType.GREEN],
        [CandyType.ORANGE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.ORANGE, CandyType.PURPLE, CandyType.BLUE, CandyType.RED]
    ])
    Map<SpecialPieceType, Integer> budgets = [
        (SpecialPieceType.SMALL_BOMB): 1,
        (SpecialPieceType.FISH)      : 1,
        (SpecialPieceType.SWEEPER)   : 1
    ]
    int budgetTotalBefore = budgets.values().sum(0) as int

    when:
    resolver.resolve(board, uniformWeights(), budgets, [] as Set<Position>, null)
    int budgetTotalAfter = budgets.values().sum(0) as int

    then:
    budgetTotalBefore - budgetTotalAfter == 1
  }

  def 'fish targets highest-priority special piece first (bomb > small bomb > sweeper > fish)'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(142L))
    RecordingListener listener = new RecordingListener()
    Board board = boardOf([
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.RED, CandyType.ORANGE, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE, CandyType.RED],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE, CandyType.RED, CandyType.GREEN]
    ])
    Position fishOrigin = new Position(2, 2)
    Position bombTarget = new Position(4, 4)
    board.setPiece(fishOrigin.x, fishOrigin.y, Piece.fish(CandyType.RED))
    board.setPiece(4, 0, Piece.sweeper(CandyType.YELLOW, true))
    board.setPiece(0, 4, Piece.smallBomb(CandyType.BLUE))
    board.setPiece(0, 0, Piece.fish(CandyType.GREEN))
    board.setPiece(bombTarget.x, bombTarget.y, Piece.bomb(CandyType.PURPLE))

    when:
    resolver.resolve(board, uniformWeights(), [:], [fishOrigin] as Set<Position>, listener)

    then:
    !listener.fishLaunches.isEmpty()
    listener.fishLaunches[0].target == bombTarget
  }

  def 'swap-triggered big bomb clears swapped color globally and emits beam callbacks'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(305L))
    RecordingListener listener = new RecordingListener()
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.BLUE, CandyType.RED],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.YELLOW, CandyType.BLUE, CandyType.RED, CandyType.GREEN]
    ])
    Position bombPos = new Position(0, 0)
    board.setPiece(bombPos.x, bombPos.y, Piece.bomb(CandyType.BLUE))
    Set<Position> forced = [new Position(0, 0), new Position(1, 0)] as Set<Position>
    Set<Position> expectedRedTargets = [new Position(1, 0), new Position(3, 1), new Position(2, 3)] as Set<Position>

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights(), [:], forced, listener)

    then:
    !result.groupSizes.isEmpty()
    result.groupSizes.first() >= 4
    listener.bombBeams.findAll { it.origin == bombPos }*.target.toSet().containsAll(expectedRedTargets)
  }

  def 'sweeper plus sweeper combo triggers both sweep directions'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(301L))
    RecordingListener listener = new RecordingListener()
    Board board = boardOf([
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.RED],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.RED, CandyType.BLUE],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.RED, CandyType.BLUE, CandyType.GREEN]
    ])
    Position first = new Position(1, 2)
    Position second = new Position(2, 2)
    board.setPiece(first.x, first.y, Piece.sweeper(CandyType.YELLOW, true))
    board.setPiece(second.x, second.y, Piece.sweeper(CandyType.PURPLE, false))
    BoardResolver.SpecialSwapCombo combo = new BoardResolver.SpecialSwapCombo(
        first, board.getPiece(first.x, first.y),
        second, board.getPiece(second.x, second.y)
    )

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights(), [:], [] as Set<Position>, combo, listener)

    then:
    listener.specialActivations.count { it.type == SpecialPieceType.SWEEPER } >= 4
    result.groupSizes.first() >= 10
  }

  def 'sweeper plus fish combo targets least promising cell before sweeping'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(302L))
    RecordingListener listener = new RecordingListener()
    Board board = new Board(3, 3)
    Position sweeperPos = new Position(0, 0)
    Position fishPos = new Position(1, 0)
    Position leastPromising = new Position(2, 1)
    Position highValue = new Position(2, 2)
    board.setPiece(sweeperPos.x, sweeperPos.y, Piece.sweeper(CandyType.RED, true))
    board.setPiece(fishPos.x, fishPos.y, Piece.fish(CandyType.BLUE))
    board.setPiece(leastPromising.x, leastPromising.y, Piece.normal(CandyType.GREEN))
    board.setPiece(highValue.x, highValue.y, Piece.bomb(CandyType.YELLOW))
    BoardResolver.SpecialSwapCombo combo = new BoardResolver.SpecialSwapCombo(
        sweeperPos, board.getPiece(sweeperPos.x, sweeperPos.y),
        fishPos, board.getPiece(fishPos.x, fishPos.y)
    )

    when:
    resolver.resolve(board, uniformWeights(), [:], [] as Set<Position>, combo, listener)

    then:
    !listener.fishLaunches.isEmpty()
    listener.fishLaunches[0].target == leastPromising
    listener.specialActivations.any { it.type == SpecialPieceType.SWEEPER }
  }

  def 'small bomb plus bomb combo converts matching color cells into small bomb activations'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(303L))
    RecordingListener listener = new RecordingListener()
    Board board = new Board(4, 4)
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        board.setCell(x, y, CandyType.BLUE)
      }
    }
    Position smallBombPos = new Position(0, 0)
    Position bombPos = new Position(1, 0)
    board.setPiece(smallBombPos.x, smallBombPos.y, Piece.smallBomb(CandyType.RED))
    board.setPiece(bombPos.x, bombPos.y, Piece.bomb(CandyType.YELLOW))
    board.setCell(2, 2, CandyType.RED)
    board.setCell(3, 3, CandyType.RED)
    BoardResolver.SpecialSwapCombo combo = new BoardResolver.SpecialSwapCombo(
        smallBombPos, board.getPiece(smallBombPos.x, smallBombPos.y),
        bombPos, board.getPiece(bombPos.x, bombPos.y)
    )

    when:
    resolver.resolve(board, uniformWeights(), [:], [] as Set<Position>, combo, listener)

    then:
    listener.specialActivations.count { it.type == SpecialPieceType.SMALL_BOMB } == 2
  }

  def 'bomb plus bomb combo clears entire board'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(304L))
    RecordingListener listener = new RecordingListener()
    Board board = boardOf([
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.BLUE, CandyType.GREEN, CandyType.RED]
    ])
    Position first = new Position(0, 0)
    Position second = new Position(1, 0)
    board.setPiece(first.x, first.y, Piece.bomb(CandyType.RED))
    board.setPiece(second.x, second.y, Piece.bomb(CandyType.BLUE))
    BoardResolver.SpecialSwapCombo combo = new BoardResolver.SpecialSwapCombo(
        first, board.getPiece(first.x, first.y),
        second, board.getPiece(second.x, second.y)
    )

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights(), [:], [] as Set<Position>, combo, listener)

    then:
    result.groupSizes.first() == 9
    listener.specialActivations.count { it.type == SpecialPieceType.BOMB } == 2
  }

  def 'clear effects damage blocker layers and keep blocker until last layer is removed'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(901L))
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.ORANGE, CandyType.PURPLE, CandyType.BLUE]
    ])
    board.setBlocker(1, 0, new Blocker(BlockerType.JELLY, 2))

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights())

    then:
    board.getBlocker(1, 0) != null
    board.getBlocker(1, 0).layers == 1
    !result.clearedBlockers.containsKey(BlockerType.JELLY)
  }

  def 'reports cleared blocker count when final blocker layer is removed'() {
    given:
    BoardResolver resolver = new BoardResolver(new Random(902L))
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.ORANGE, CandyType.PURPLE, CandyType.BLUE]
    ])
    board.setBlocker(1, 0, new Blocker(BlockerType.CRATE, 1))

    when:
    BoardResolver.CascadeResult result = resolver.resolve(board, uniformWeights())

    then:
    board.getBlocker(1, 0) == null
    result.clearedBlockers.getOrDefault(BlockerType.CRATE, 0) >= 1
  }

  private static Track track(String id,
                             int width,
                             int height,
                             int moves,
                             int targetScore,
                             Map<CandyType, Integer> weights,
                             List<String> boardMask = null) {
    new Track(id, id, width, height, moves, targetScore, weights, null, null, null, null, boardMask)
  }

  private static Map<CandyType, Integer> uniformWeights() {
    CandyType.values().collectEntries { CandyType type ->
      [(type): 1]
    } as Map<CandyType, Integer>
  }

  private static Board findDeadBoard(BoardResolver resolver, MatchFinder finder) {
    Board latin = boardOf([
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.RED],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.RED, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.RED, CandyType.BLUE, CandyType.GREEN],
        [CandyType.PURPLE, CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW]
    ])

    if (finder.findMatchGroups(latin).isEmpty() && !resolver.hasLegalSwap(latin)) {
      return latin
    }

    Random random = new Random(2026L)
    CandyType[] candyTypes = CandyType.values()
    for (int attempt = 0; attempt < 200_000; attempt++) {
      Board candidate = new Board(5, 5)
      for (int y = 0; y < candidate.height; y++) {
        for (int x = 0; x < candidate.width; x++) {
          candidate.setCell(x, y, candyTypes[random.nextInt(candyTypes.length)])
        }
      }

      if (finder.findMatchGroups(candidate).isEmpty() && !resolver.hasLegalSwap(candidate)) {
        return candidate
      }
    }

    throw new IllegalStateException('Failed to generate dead board for test')
  }

  private static Board boardOf(List<List<CandyType>> rows) {
    int height = rows.size()
    int width = rows[0].size()
    Board board = new Board(width, height)

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        board.setCell(x, y, rows[y][x])
      }
    }

    board
  }

  private static int countSpecials(Board board, SpecialPieceType type) {
    int count = 0
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (board.getPiece(x, y)?.specialType == type) {
          count++
        }
      }
    }
    count
  }

  private static boolean boardsEqual(Board left, Board right) {
    if (left.width != right.width || left.height != right.height) {
      return false
    }
    for (int y = 0; y < left.height; y++) {
      for (int x = 0; x < left.width; x++) {
        Piece lp = left.getPiece(x, y)
        Piece rp = right.getPiece(x, y)
        if (lp?.color != rp?.color || lp?.specialType != rp?.specialType || lp?.sweeperHorizontal != rp?.sweeperHorizontal) {
          return false
        }
      }
    }
    true
  }

  private static final class RecordingListener implements GameListener {
    int reshuffleExhaustedCount = 0
    List<Map<String, Position>> fishLaunches = []
    List<Map<String, Position>> bombBeams = []
    List<Map<String, Object>> specialActivations = []

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
      reshuffleExhaustedCount++
    }

    @Override
    void onFishLaunched(Position origin, Position target) {
      fishLaunches << [origin: origin, target: target]
    }

    @Override
    void onBombBeam(Position origin, Position target) {
      bombBeams << [origin: origin, target: target]
    }

    @Override
    void onSpecialActivated(SpecialPieceType specialType, Position origin, boolean sweeperHorizontal) {
      specialActivations << [type: specialType, origin: origin, sweeperHorizontal: sweeperHorizontal]
    }
  }
}
