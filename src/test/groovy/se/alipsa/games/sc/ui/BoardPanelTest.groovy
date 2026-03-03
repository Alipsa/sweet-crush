package se.alipsa.games.sc.ui

import se.alipsa.games.sc.core.Board
import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.GameSession
import se.alipsa.games.sc.core.Piece
import se.alipsa.games.sc.core.Position
import se.alipsa.games.sc.model.Track
import spock.lang.Specification

import java.awt.image.BufferedImage

class BoardPanelTest extends Specification {

  def 'computes cell size based on track dimensions and panel size'() {
    given:
    Board board = boardOf(7, 9)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> board
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(7, 9), engine)

    expect:
    panel.computeCellSize(700, 900) == 100
    panel.computeCellSize(701, 900) == 100
  }

  def 'loads candy images for all candy types'() {
    given:
    BoardPanel panel = new BoardPanel()

    expect:
    CandyType.values().every { CandyType type ->
      panel.imageFor(type) != null
    }
  }

  def 'translates mouse coordinates to board cell coordinates'() {
    given:
    Board board = boardOf(7, 9)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> board
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(7, 9), engine)

    when:
    Position cell = panel.toBoardCell(155, 255, 700, 900)

    then:
    cell == new Position(1, 2)
  }

  def 'returns null for mouse coordinates targeting hole cells'() {
    given:
    boolean[][] mask = [
        [true, true, true] as boolean[],
        [true, false, true] as boolean[],
        [true, true, true] as boolean[]
    ] as boolean[][]
    Board board = new Board(3, 3, mask)
    board.setCell(0, 0, CandyType.RED)
    board.setCell(2, 2, CandyType.BLUE)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> board
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(3, 3), engine)

    when:
    Position holeCell = panel.toBoardCell(150, 150, 300, 300)

    then:
    holeCell == null
  }

  def 'rejects move input while engine is resolving'() {
    given:
    Board board = boardOf(3, 3)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> board
      isResolving() >> true
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(3, 3), engine)

    expect:
    !panel.trySwap(new Position(0, 0), new Position(1, 0))
  }

  def 'renders board containing special pieces without throwing'() {
    given:
    Board board = boardOf(4, 4)
    board.setPiece(0, 0, Piece.sweeper(CandyType.RED, true))
    board.setPiece(1, 1, Piece.bomb(CandyType.BLUE))
    board.setPiece(2, 2, Piece.fish(CandyType.GREEN))
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> board
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(4, 4), engine)
    panel.setSize(320, 320)
    BufferedImage canvas = new BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB)

    when:
    panel.paint(canvas.graphics)

    then:
    noExceptionThrown()
  }

  def 'renders sweeper activation beam without throwing'() {
    given:
    Board start = boardOf(4, 4)
    start.setPiece(1, 1, Piece.sweeper(CandyType.RED, true))
    Board end = start.clone()
    end.setCell(1, 1, CandyType.BLUE)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> start
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(4, 4), engine)
    panel.setSize(320, 320)
    panel.onSpecialActivated(se.alipsa.games.sc.core.SpecialPieceType.SWEEPER, new Position(1, 1), true)
    BufferedImage canvas = new BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB)

    when:
    panel.updateBoard(end)
    panel.paint(canvas.graphics)

    then:
    noExceptionThrown()
  }

  def 'includes burst effects when sweeper activation runs'() {
    given:
    Board start = boardOf(4, 4)
    start.setPiece(1, 1, Piece.sweeper(CandyType.RED, true))
    Board end = start.clone()
    end.setCell(1, 1, CandyType.BLUE)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> start
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(4, 4), engine)
    panel.onSpecialActivated(se.alipsa.games.sc.core.SpecialPieceType.SWEEPER, new Position(1, 1), true)

    when:
    panel.updateBoard(end)

    then:
    panel.@transitionState != null
    !panel.@transitionState.@bursts.isEmpty()
  }

  def 'renders small bomb activation explosion without throwing'() {
    given:
    Board start = boardOf(5, 5)
    start.setPiece(2, 2, Piece.smallBomb(CandyType.ORANGE))
    Board end = start.clone()
    end.setCell(2, 2, CandyType.BLUE)
    end.setCell(1, 2, CandyType.GREEN)
    end.setCell(3, 2, CandyType.PURPLE)
    end.setCell(2, 1, CandyType.YELLOW)
    end.setCell(2, 3, CandyType.RED)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> start
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(5, 5), engine)
    panel.setSize(360, 360)
    panel.onSpecialActivated(se.alipsa.games.sc.core.SpecialPieceType.SMALL_BOMB, new Position(2, 2), true)
    BufferedImage canvas = new BufferedImage(360, 360, BufferedImage.TYPE_INT_ARGB)

    when:
    panel.updateBoard(end)
    panel.paint(canvas.graphics)

    then:
    noExceptionThrown()
  }

  def 'renders fish swim activation without throwing'() {
    given:
    Board start = boardOf(5, 5)
    start.setPiece(1, 1, Piece.fish(CandyType.GREEN))
    start.setPiece(3, 3, Piece.normal(CandyType.RED))
    Board end = start.clone()
    end.setCell(1, 1, CandyType.BLUE)
    end.setCell(3, 3, CandyType.YELLOW)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> start
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(5, 5), engine)
    panel.setSize(360, 360)
    panel.onFishLaunched(new Position(1, 1), new Position(3, 3))
    BufferedImage canvas = new BufferedImage(360, 360, BufferedImage.TYPE_INT_ARGB)

    when:
    panel.updateBoard(end)
    panel.paint(canvas.graphics)

    then:
    noExceptionThrown()
  }

  def 'renders big bomb beam activation without throwing'() {
    given:
    Board start = boardOf(5, 5)
    start.setPiece(1, 1, Piece.bomb(CandyType.BLUE))
    Board end = start.clone()
    end.setCell(1, 1, CandyType.GREEN)
    end.setCell(3, 1, CandyType.YELLOW)
    GameSession engine = Stub(GameSession) {
      snapshotBoard() >> start
      isResolving() >> false
    }
    BoardPanel panel = new BoardPanel()
    panel.setGame(track(5, 5), engine)
    panel.setSize(360, 360)
    panel.onBombBeam(new Position(1, 1), new Position(3, 1))
    BufferedImage canvas = new BufferedImage(360, 360, BufferedImage.TYPE_INT_ARGB)

    when:
    panel.updateBoard(end)
    panel.paint(canvas.graphics)

    then:
    noExceptionThrown()
  }

  private static Track track(int width, int height) {
    new Track('track', 'Track', width, height, 10, 1000,
        CandyType.values().collectEntries { CandyType type -> [(type): 1] } as Map<CandyType, Integer>)
  }

  private static Board boardOf(int width, int height) {
    Board board = new Board(width, height)
    CandyType[] values = CandyType.values()
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        board.setCell(x, y, values[(x + y) % values.length])
      }
    }
    board
  }
}
