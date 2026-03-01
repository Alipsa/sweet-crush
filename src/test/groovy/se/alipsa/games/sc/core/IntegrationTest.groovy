package se.alipsa.games.sc.core

import groovy.json.JsonOutput
import se.alipsa.games.sc.io.TrackLoader
import se.alipsa.games.sc.io.LoadResult
import se.alipsa.games.sc.model.Track
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IntegrationTest extends Specification {

  @TempDir
  Path tempDir

  private ExecutorService worker

  def cleanup() {
    if (worker != null) {
      worker.shutdownNow()
      worker.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  def 'loads track JSON and plays deterministic scripted moves through engine pipeline'() {
    given:
    Path trackFile = tempDir.resolve('track-01.json')
    Files.writeString(trackFile, JsonOutput.prettyPrint(JsonOutput.toJson([
        id         : 'scripted-01',
        name       : 'Scripted',
        width      : 7,
        height     : 7,
        moves      : 20,
        targetScore: 20000,
        spawnWeights: [
            RED   : 3,
            BLUE  : 3,
            GREEN : 3,
            YELLOW: 2,
            PURPLE: 2,
            ORANGE: 1
        ]
    ])))

    TrackLoader loader = new TrackLoader()
    LoadResult loaded = loader.loadTracks(tempDir)
    Track track = loaded.tracks[0] as Track

    worker = Executors.newSingleThreadExecutor()
    GameEngine engine = new GameEngine(track, new Random(2026L), worker)
    MatchFinder matchFinder = new MatchFinder()

    when:
    5.times {
      int[] swap = findFirstLegalSwap(engine.snapshotBoard(), matchFinder)
      assert swap != null
      assert engine.submitSwap(swap[0], swap[1], swap[2], swap[3]).get(2, TimeUnit.SECONDS)
    }

    then:
    loaded.errors.isEmpty()
    engine.movesLeft == 15
    engine.score > 0
    !engine.gameOver
  }

  def 'loads specialPieces config and executes special-triggered cascade through engine'() {
    given:
    Path trackFile = tempDir.resolve('track-specials.json')
    Files.writeString(trackFile, JsonOutput.prettyPrint(JsonOutput.toJson([
        id          : 'special-chain-01',
        name        : 'Special Chain',
        width       : 5,
        height      : 5,
        moves       : 10,
        targetScore : 5000,
        specialPieces: [
            SWEEPER  : 2,
            SMALL_BOMB: 2,
            BOMB     : 2,
            FISH     : 1
        ],
        spawnWeights: [
            RED   : 3,
            BLUE  : 3,
            GREEN : 3,
            YELLOW: 2,
            PURPLE: 2,
            ORANGE: 1
        ]
    ])))

    TrackLoader loader = new TrackLoader()
    LoadResult loaded = loader.loadTracks(tempDir)
    Track track = loaded.tracks.find { it.id == 'special-chain-01' } as Track

    Board seeded = specialChainBoard()
    BoardResolver resolver = new FixedInitialBoardResolver(seeded)

    worker = Executors.newSingleThreadExecutor()
    GameEngine engine = new GameEngine(track, resolver, new MatchFinder(), worker)

    when:
    boolean success = engine.submitSwap(1, 2, 2, 2).get(2, TimeUnit.SECONDS)

    then:
    loaded.errors.isEmpty()
    success
    engine.movesLeft == 9
    engine.score >= 80
    !engine.gameOver
  }

  private static int[] findFirstLegalSwap(Board board, MatchFinder matchFinder) {
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (x + 1 < board.width && createsMatch(board, x, y, x + 1, y, matchFinder)) {
          return [x, y, x + 1, y] as int[]
        }
        if (y + 1 < board.height && createsMatch(board, x, y, x, y + 1, matchFinder)) {
          return [x, y, x, y + 1] as int[]
        }
      }
    }
    null
  }

  private static boolean createsMatch(Board board,
                                      int x1,
                                      int y1,
                                      int x2,
                                      int y2,
                                      MatchFinder matchFinder) {
    Board preview = board.clone()
    preview.swap(x1, y1, x2, y2)
    !matchFinder.findMatchGroups(preview).isEmpty()
  }

  private static Board specialChainBoard() {
    Board board = new Board(5, 5)
    List<List<CandyType>> rows = [
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE],
        [CandyType.GREEN, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.RED],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.RED, CandyType.BLUE],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.RED, CandyType.BLUE, CandyType.GREEN]
    ]

    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        board.setCell(x, y, rows[y][x])
      }
    }

    board.setPiece(1, 2, Piece.sweeper(CandyType.YELLOW, true))
    board.setPiece(2, 2, Piece.bomb(CandyType.PURPLE))
    return board
  }

  private static final class FixedInitialBoardResolver extends BoardResolver {
    private final Board fixed

    FixedInitialBoardResolver(Board fixed) {
      super(new Random(17L), new MatchFinder(), new GravityRefill())
      this.fixed = fixed
    }

    @Override
    Board createInitialBoard(Track track, GameListener listener = null) {
      fixed.clone()
    }
  }
}
