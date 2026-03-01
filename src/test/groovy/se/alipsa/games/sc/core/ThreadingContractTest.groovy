package se.alipsa.games.sc.core

import se.alipsa.games.sc.model.Track
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.Collections

class ThreadingContractTest extends Specification {

  private ExecutorService worker

  def cleanup() {
    if (worker != null) {
      worker.shutdownNow()
      worker.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  def 'engine resolves on game worker, callbacks run on worker, and input is rejected while resolving'() {
    given:
    worker = Executors.newSingleThreadExecutor(new NamedThreadFactory('game-worker-test'))
    RecordingListener listener = new RecordingListener()
    SlowBoardResolver resolver = new SlowBoardResolver(legalSwapBoard())
    GameEngine engine = new GameEngine(track(5, 9999), resolver, new MatchFinder(), worker, listener)

    when:
    def firstSwap = engine.submitSwap(0, 1, 1, 1)
    assert resolver.entered.await(2, TimeUnit.SECONDS)
    boolean secondSwap = engine.submitSwap(0, 1, 1, 1).get(1, TimeUnit.SECONDS)
    boolean firstSwapResult = firstSwap.get(3, TimeUnit.SECONDS)

    then:
    firstSwapResult
    !secondSwap
    listener.callbackThreads.size() >= 2
    listener.callbackThreads.every { it.contains('game-worker-test') }
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

  private static Track track(int moves, int targetScore) {
    new Track('t', 't', 3, 3, moves, targetScore, uniformWeights())
  }

  private static Map<CandyType, Integer> uniformWeights() {
    CandyType.values().collectEntries { CandyType type ->
      [(type): 1]
    } as Map<CandyType, Integer>
  }

  private static final class NamedThreadFactory implements ThreadFactory {
    private final String threadName

    NamedThreadFactory(String threadName) {
      this.threadName = threadName
    }

    @Override
    Thread newThread(Runnable runnable) {
      new Thread(runnable, threadName)
    }
  }

  private static final class SlowBoardResolver extends BoardResolver {
    private final Board initialBoard
    final CountDownLatch entered = new CountDownLatch(1)

    SlowBoardResolver(Board initialBoard) {
      super(new Random(7L), new MatchFinder(), new GravityRefill())
      this.initialBoard = initialBoard
    }

    @Override
    Board createInitialBoard(Track track, GameListener listener = null) {
      initialBoard.clone()
    }

    @Override
    CascadeResult resolve(Board board, Map<CandyType, Integer> spawnWeights, GameListener listener = null) {
      entered.countDown()
      try {
        Thread.sleep(250)
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt()
      }
      new CascadeResult([3])
    }
  }

  private static final class RecordingListener implements GameListener {
    final List<String> callbackThreads = Collections.synchronizedList([])

    @Override
    void onBoardUpdated(Board board) {
      callbackThreads << Thread.currentThread().name
    }

    @Override
    void onScoreChanged(int score) {
      callbackThreads << Thread.currentThread().name
    }

    @Override
    void onGameOver(GameOutcome outcome, int finalScore, int movesLeft) {
      callbackThreads << Thread.currentThread().name
    }

    @Override
    void onReshuffleExhausted() {
      callbackThreads << Thread.currentThread().name
    }
  }
}
