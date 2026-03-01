package se.alipsa.games.sc.ui

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import se.alipsa.games.sc.core.Board
import se.alipsa.games.sc.core.Blocker
import se.alipsa.games.sc.core.BlockerType
import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.FlowDirection
import se.alipsa.games.sc.core.GameSession
import se.alipsa.games.sc.core.Piece
import se.alipsa.games.sc.core.Position
import se.alipsa.games.sc.core.SpecialPieceType
import se.alipsa.games.sc.model.Track

import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Composite
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.QuadCurve2D
import java.awt.image.BufferedImage
import java.net.URL
import java.util.concurrent.Future

class BoardPanel extends JPanel {

  private static final Map<CandyType, Color> CANDY_COLORS = [
      (CandyType.RED)   : new Color(0xD64034), // Fire
      (CandyType.BLUE)  : new Color(0x1F3558), // Water
      (CandyType.GREEN) : new Color(0x2F8F46), // Wood
      (CandyType.YELLOW): new Color(0xE6C65A), // Earth
      (CandyType.PURPLE): new Color(0xD9DEE3), // Metal
      (CandyType.ORANGE): new Color(0xA8793E)  // Earth variant
  ].asImmutable()
  private static final Map<BlockerType, Color> BLOCKER_COLORS = [
      (BlockerType.JELLY)   : new Color(0x8EB9FF),
      (BlockerType.CRATE)   : new Color(0x8A5E3B),
      (BlockerType.LICORICE): new Color(0x2D2D2D),
      (BlockerType.ICE)     : new Color(0x9EDBFF),
      (BlockerType.HONEY)   : new Color(0xC48C2C)
  ].asImmutable()

  private static final Map<CandyType, BufferedImage> CANDY_IMAGES = loadCandyImages()
  private static final int SVG_ICON_BASE_SIZE = 96
  private static final long TRANSITION_DURATION_NANOS = 920_000_000L
  private static final double EXPLOSION_PHASE_RATIO = 0.38d
  private static final double BURST_TAIL_RATIO = 0.22d
  private static final double SWEEP_PHASE_RATIO = 0.56d
  private static final double FISH_SWIM_PHASE_RATIO = 0.90d
  private static final double SWEEP_AFTER_FISH_START_RATIO = 0.82d
  private static final double SWEEP_AFTER_FISH_END_RATIO = 0.88d
  private static final int[][] SMALL_BOMB_BLAST_DELTAS = [
      [0, 0],
      [-1, 0],
      [1, 0],
      [0, -1],
      [0, 1]
  ] as int[][]

  private volatile GameSession engine
  private volatile Board board
  private volatile Track track
  private volatile boolean inputEnabled = true
  private volatile boolean inputLocked = false
  private volatile boolean visualAnimationRunning = false

  private volatile Position selectedCell
  private volatile Position pressedCell
  private volatile boolean dragging = false
  private volatile CandyType draggedCandy
  private volatile int dragPixelX = 0
  private volatile int dragPixelY = 0
  private volatile Position hintFirst
  private volatile Position hintSecond

  private Timer transitionTimer
  private TransitionState transitionState
  private final List<SweepActivation> pendingSweepActivations = []
  private final List<SmallBombActivation> pendingSmallBombActivations = []
  private final List<FishSwimActivation> pendingFishSwims = []
  private final List<BombBeamActivation> pendingBombBeams = []

  private Runnable moveResolvedCallback

  BoardPanel() {
    setOpaque(true)
    setBackground(new Color(0x2C2C2C))
    preferredSize = new Dimension(560, 720)

    addMouseListener(new MouseAdapter() {
      @Override
      void mousePressed(MouseEvent event) {
        if (!canAcceptInput()) {
          return
        }

        Position cell = resolveBoardCell(event.x, event.y)
        if (cell == null || board == null) {
          clearDragState()
          return
        }

        pressedCell = cell
        draggedCandy = board.getCell(cell.x, cell.y)
        if (draggedCandy != null) {
          dragging = true
          dragPixelX = event.x
          dragPixelY = event.y
          repaint()
        }
      }

      @Override
      void mouseReleased(MouseEvent event) {
        Position releasedCell = resolveBoardCell(event.x, event.y)
        if (pressedCell != null && releasedCell != null &&
            pressedCell != releasedCell &&
            isOrthogonallyAdjacent(pressedCell, releasedCell)) {
          selectedCell = pressedCell
          trySwap(pressedCell, releasedCell)
          clearDragState()
          return
        }

        clearDragState()
        handleCellSelection(releasedCell)
      }
    })

    addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      void mouseDragged(MouseEvent event) {
        if (!dragging) {
          return
        }

        dragPixelX = event.x
        dragPixelY = event.y
        repaint()
      }
    })
  }

  void setGame(Track track, GameSession engine) {
    this.track = track
    this.engine = engine
    this.board = engine?.snapshotBoard()
    this.selectedCell = null
    this.inputEnabled = true
    this.inputLocked = false
    this.pendingSweepActivations.clear()
    this.pendingSmallBombActivations.clear()
    this.pendingFishSwims.clear()
    this.pendingBombBeams.clear()
    clearHintState(false)
    clearDragState()
    stopTransitionAnimation()
    repaint()
  }

  void setMoveResolvedCallback(Runnable moveResolvedCallback) {
    this.moveResolvedCallback = moveResolvedCallback
  }

  void onSpecialActivated(SpecialPieceType specialType, Position origin, boolean sweeperHorizontal) {
    if (origin == null) {
      return
    }
    if (specialType == SpecialPieceType.SWEEPER) {
      pendingSweepActivations << new SweepActivation(origin.x, origin.y, sweeperHorizontal)
    } else if (specialType == SpecialPieceType.SMALL_BOMB) {
      pendingSmallBombActivations << new SmallBombActivation(origin.x, origin.y)
    }
  }

  void onFishLaunched(Position origin, Position target) {
    if (origin == null || target == null) {
      return
    }
    pendingFishSwims << new FishSwimActivation(origin.x, origin.y, target.x, target.y)
  }

  void onBombBeam(Position origin, Position target) {
    if (origin == null || target == null) {
      return
    }
    pendingBombBeams << new BombBeamActivation(origin.x, origin.y, target.x, target.y)
  }

  void showHint(Position first, Position second) {
    if (first == null || second == null || board == null) {
      return
    }
    if (!board.inBounds(first.x, first.y) || !board.inBounds(second.x, second.y)) {
      return
    }
    hintFirst = first
    hintSecond = second
    selectedCell = null
    repaint()
  }

  void updateBoard(Board nextBoard) {
    Board incoming = nextBoard?.clone()
    if (incoming == null) {
      board = null
      pendingSweepActivations.clear()
      pendingSmallBombActivations.clear()
      pendingFishSwims.clear()
      pendingBombBeams.clear()
      clearHintState(false)
      stopTransitionAnimation()
      repaint()
      return
    }

    Board previous = board?.clone()
    board = incoming

    if (previous != null && previous.width == incoming.width && previous.height == incoming.height && hasChanges(previous, incoming)) {
      startTransitionAnimation(previous, incoming)
    } else {
      pendingSweepActivations.clear()
      pendingSmallBombActivations.clear()
      pendingFishSwims.clear()
      pendingBombBeams.clear()
      clearHintState(false)
      stopTransitionAnimation()
      repaint()
    }
  }

  int computeCellSize(int panelWidth, int panelHeight) {
    if (board == null) {
      return 1
    }
    int cellWidth = Math.max(1, panelWidth.intdiv(board.width))
    int cellHeight = Math.max(1, panelHeight.intdiv(board.height))
    return Math.max(1, Math.min(cellWidth, cellHeight))
  }

  Color colorFor(CandyType candyType) {
    CANDY_COLORS.getOrDefault(candyType, Color.GRAY)
  }

  BufferedImage imageFor(CandyType candyType) {
    CANDY_IMAGES[candyType]
  }

  Position toBoardCell(int pixelX, int pixelY) {
    toBoardCell(pixelX, pixelY, getWidth(), getHeight())
  }

  Position toBoardCell(int pixelX, int pixelY, int panelWidth, int panelHeight) {
    if (board == null) {
      return null
    }

    int cellSize = computeCellSize(panelWidth, panelHeight)
    int offsetX = boardOffsetX(panelWidth, panelHeight)
    int offsetY = boardOffsetY(panelWidth, panelHeight)

    int localX = pixelX - offsetX
    int localY = pixelY - offsetY
    if (localX < 0 || localY < 0) {
      return null
    }

    int x = localX.intdiv(cellSize)
    int y = localY.intdiv(cellSize)

    if (!board.inBounds(x, y)) {
      return null
    }
    if (!board.isPlayable(x, y)) {
      return null
    }
    new Position(x, y)
  }

  boolean canAcceptInput() {
    inputEnabled && !inputLocked && !visualAnimationRunning && engine != null && !engine.isResolving()
  }

  boolean trySwap(Position first, Position second) {
    if (!canAcceptInput()) {
      return false
    }

    if (first == null || second == null) {
      return false
    }

    clearHintState(false)
    inputLocked = true
    Future<Boolean> swapResult = engine.submitSwap(first.x, first.y, second.x, second.y)

    Thread unlockThread = new Thread({
      try {
        swapResult.get()
      } catch (Exception ignored) {
      } finally {
        SwingUtilities.invokeLater {
          inputLocked = false
          selectedCell = null
          repaint()
          moveResolvedCallback?.run()
        }
      }
    }, 'sweet-crush-swap-await')
    unlockThread.daemon = true
    unlockThread.start()

    return true
  }

  boolean isInputLocked() {
    inputLocked
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics)
    if (board == null) {
      return
    }

    Graphics2D g2 = (Graphics2D) graphics.create()
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    int cellSize = computeCellSize(getWidth(), getHeight())
    int offsetX = boardOffsetX(getWidth(), getHeight())
    int offsetY = boardOffsetY(getWidth(), getHeight())

    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        int left = offsetX + (x * cellSize)
        int top = offsetY + (y * cellSize)
        if (!board.isPlayable(x, y)) {
          g2.setColor(new Color(0x1E1E1E))
          g2.fillRect(left, top, cellSize, cellSize)
          g2.setColor(new Color(0x565656))
          g2.drawRect(left, top, cellSize, cellSize)
          continue
        }

        Piece piece = board.getPiece(x, y)
        Blocker blocker = board.getBlocker(x, y)

        g2.setColor(new Color(0xD1D5DB))
        g2.drawRect(left, top, cellSize, cellSize)

        boolean draggedSource = isDraggedSourceCell(x, y)
        boolean hiddenByAnimation = isHiddenAnimationTarget(x, y)

        if (piece != null && !draggedSource && !hiddenByAnimation) {
          drawCandyAtCell(g2, piece, left, top, cellSize, 1.0f)
        }
        if (blocker != null) {
          drawBlockerOverlay(g2, blocker, left, top, cellSize)
        }
        drawGeometryOverlay(g2, x, y, left, top, cellSize)

        if (draggedSource) {
          int inset = Math.max(2, cellSize / 10)
          int drawSize = cellSize - (2 * inset)
          g2.setColor(new Color(17, 24, 39, 45))
          g2.fillOval(left + inset, top + inset, drawSize, drawSize)
        }

        if (selectedCell != null && selectedCell.x == x && selectedCell.y == y) {
          g2.setColor(new Color(0x111827))
          g2.drawRect(left + 1, top + 1, cellSize - 2, cellSize - 2)
        }
        if (isHintCell(x, y)) {
          java.awt.Stroke oldStroke = g2.stroke
          g2.stroke = new java.awt.BasicStroke(Math.max(2f, (float) (cellSize * 0.08d)))
          g2.setColor(new Color(255, 226, 138, 230))
          g2.drawRect(left + 2, top + 2, cellSize - 4, cellSize - 4)
          g2.stroke = oldStroke
        }
      }
    }

    drawTransitionEffects(g2, cellSize, offsetX, offsetY)
    drawDragGhost(g2, cellSize)

    g2.dispose()
  }

  private void drawGeometryOverlay(Graphics2D g2, int x, int y, int left, int top, int cellSize) {
    FlowDirection direction = board.flowDirectionAt(x, y)
    Position teleporterTarget = board.teleporterTargetAt(x, y)
    if (direction == null && teleporterTarget == null) {
      return
    }

    int cx = left + cellSize.intdiv(2)
    int cy = top + cellSize.intdiv(2)
    java.awt.Stroke oldStroke = g2.stroke

    if (teleporterTarget != null) {
      int radius = Math.max(5, cellSize.intdiv(6))
      g2.setColor(new Color(129, 230, 217, 210))
      g2.setStroke(new java.awt.BasicStroke(Math.max(1.4f, (float) (cellSize * 0.045d))))
      g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2)
      g2.setColor(new Color(45, 212, 191, 190))
      g2.fillOval(cx - Math.max(2, radius.intdiv(3)), cy - Math.max(2, radius.intdiv(3)), Math.max(4, radius.intdiv(2) * 2), Math.max(4, radius.intdiv(2) * 2))
    }

    if (direction != null) {
      int lineLength = Math.max(8, cellSize.intdiv(4))
      int ex = cx + (direction.dx * lineLength)
      int ey = cy + (direction.dy * lineLength)
      g2.setStroke(new java.awt.BasicStroke(Math.max(1.8f, (float) (cellSize * 0.05d)),
          java.awt.BasicStroke.CAP_ROUND,
          java.awt.BasicStroke.JOIN_ROUND))
      g2.setColor(new Color(17, 24, 39, 190))
      g2.drawLine(cx, cy, ex, ey)

      int head = Math.max(3, cellSize.intdiv(9))
      if (direction == FlowDirection.LEFT || direction == FlowDirection.RIGHT) {
        int sx = direction == FlowDirection.RIGHT ? -1 : 1
        g2.drawLine(ex, ey, ex + (sx * head), ey - head)
        g2.drawLine(ex, ey, ex + (sx * head), ey + head)
      } else {
        int sy = direction == FlowDirection.DOWN ? -1 : 1
        g2.drawLine(ex, ey, ex - head, ey + (sy * head))
        g2.drawLine(ex, ey, ex + head, ey + (sy * head))
      }
    }

    g2.stroke = oldStroke
  }

  private void drawCandyAtCell(Graphics2D g2,
                               Piece piece,
                               int left,
                               int top,
                               int cellSize,
                               float alpha) {
    if (piece == null) {
      return
    }
    int inset = Math.max(2, cellSize / 10)
    int drawSize = cellSize - (2 * inset)
    int drawLeft = left + inset
    int drawTop = top + inset
    if (piece.specialType == SpecialPieceType.BOMB ||
        piece.specialType == SpecialPieceType.SMALL_BOMB ||
        piece.specialType == SpecialPieceType.FISH) {
      drawCandyBase(g2, piece.color, drawLeft, drawTop, drawSize, alpha)
    } else {
      drawCandy(g2, piece.color, drawLeft, drawTop, drawSize, alpha)
    }
    drawSpecialOverlay(g2, piece, drawLeft, drawTop, drawSize, alpha)
  }

  private void drawBlockerOverlay(Graphics2D g2, Blocker blocker, int left, int top, int cellSize) {
    if (blocker == null) {
      return
    }

    int inset = Math.max(2, cellSize / 10)
    int drawLeft = left + inset
    int drawTop = top + inset
    int drawSize = cellSize - (2 * inset)
    int cornerArc = Math.max(6, drawSize.intdiv(4))
    Color base = BLOCKER_COLORS.getOrDefault(blocker.type, new Color(0x666666))

    Composite oldComposite = g2.composite
    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.42f)
    g2.setColor(base)
    g2.fillRoundRect(drawLeft, drawTop, drawSize, drawSize, cornerArc, cornerArc)
    g2.composite = oldComposite

    g2.setColor(new Color(20, 20, 20, 180))
    g2.drawRoundRect(drawLeft, drawTop, drawSize, drawSize, cornerArc, cornerArc)

    if (blocker.layers > 1) {
      String text = blocker.layers.toString()
      Font oldFont = g2.font
      g2.font = oldFont.deriveFont(Math.max(10f, (float) (drawSize * 0.26d)))
      java.awt.FontMetrics fm = g2.getFontMetrics()
      int tx = drawLeft + drawSize - fm.stringWidth(text) - Math.max(2, drawSize / 8)
      int ty = drawTop + fm.ascent + Math.max(1, drawSize / 10)
      g2.setColor(new Color(255, 255, 255, 220))
      g2.drawString(text, tx, ty)
      g2.font = oldFont
    }
  }

  private void drawCandy(Graphics2D g2,
                         CandyType candy,
                         int left,
                         int top,
                         int size,
                         float alpha) {
    Composite previous = g2.composite
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha))

    BufferedImage image = imageFor(candy)
    if (image != null) {
      g2.drawImage(image, left, top, size, size, null)
    } else {
      g2.setColor(colorFor(candy))
      g2.fillOval(left, top, size, size)
    }

    g2.setComposite(previous)
  }

  private void drawCandyBase(Graphics2D g2,
                             CandyType candy,
                             int left,
                             int top,
                             int size,
                             float alpha) {
    Composite previous = g2.composite
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha))
    g2.setColor(colorFor(candy))
    g2.fillOval(left, top, size, size)
    g2.setComposite(previous)
  }

  private void drawSpecialOverlay(Graphics2D g2,
                                  Piece piece,
                                  int left,
                                  int top,
                                  int size,
                                  float alpha) {
    if (piece?.specialType == null) {
      return
    }

    Composite previous = g2.composite
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0.45f, alpha)))
    g2.setColor(new Color(255, 255, 255, 220))

    int cx = left + size.intdiv(2)
    int cy = top + size.intdiv(2)

    switch (piece.specialType) {
      case SpecialPieceType.SWEEPER:
        java.awt.Stroke previousStroke = g2.stroke
        g2.stroke = new java.awt.BasicStroke(Math.max(2f, (float) (size * 0.08d)),
            java.awt.BasicStroke.CAP_ROUND,
            java.awt.BasicStroke.JOIN_ROUND)
        g2.setColor(new Color(55, 65, 81, 230))
        int lineInset = Math.max(2, size.intdiv(8))
        int darkShift = Math.max(1, size.intdiv(14))
        if (piece.sweeperHorizontal) {
          g2.drawLine(left + lineInset, cy, left + size - lineInset, cy)
          g2.drawLine(left + lineInset, cy - 2 - darkShift, left + size - lineInset, cy - 2 - darkShift)
          g2.drawLine(left + lineInset, cy + 2 + darkShift, left + size - lineInset, cy + 2 + darkShift)
          g2.setColor(new Color(255, 255, 255, 235))
          g2.drawLine(left + lineInset, cy, left + size - lineInset, cy)
          g2.drawLine(left + lineInset, cy - 2, left + size - lineInset, cy - 2)
          g2.drawLine(left + lineInset, cy + 2, left + size - lineInset, cy + 2)
        } else {
          g2.drawLine(cx, top + lineInset, cx, top + size - lineInset)
          g2.drawLine(cx - 2 - darkShift, top + lineInset, cx - 2 - darkShift, top + size - lineInset)
          g2.drawLine(cx + 2 + darkShift, top + lineInset, cx + 2 + darkShift, top + size - lineInset)
          g2.setColor(new Color(255, 255, 255, 235))
          g2.drawLine(cx, top + lineInset, cx, top + size - lineInset)
          g2.drawLine(cx - 2, top + lineInset, cx - 2, top + size - lineInset)
          g2.drawLine(cx + 2, top + lineInset, cx + 2, top + size - lineInset)
        }
        g2.stroke = previousStroke
        break
      case SpecialPieceType.BOMB:
        drawMushroomCloudOverlay(g2, cx, cy, size)
        break
      case SpecialPieceType.SMALL_BOMB:
        drawCartoonBombOverlay(g2, cx, cy, size)
        break
      case SpecialPieceType.FISH:
        drawFishOverlay(g2, cx, cy, size)
        break
    }

    g2.setComposite(previous)
  }

  private static void drawCartoonBombOverlay(Graphics2D g2, int cx, int cy, int size) {
    int clipInset = Math.max(1, (int) Math.round(size * 0.04d))
    def oldClip = g2.clip
    g2.setClip(new Ellipse2D.Double(
        (cx - size.intdiv(2) + clipInset) as double,
        (cy - size.intdiv(2) + clipInset) as double,
        (size - (clipInset * 2)) as double,
        (size - (clipInset * 2)) as double
    ))

    int bodyRadius = Math.max(5, (int) Math.round(size * 0.25d))
    int bodyCx = cx
    int bodyCy = cy + Math.max(1, (int) Math.round(size * 0.11d))
    int bodyLeft = bodyCx - bodyRadius
    int bodyTop = bodyCy - bodyRadius
    int bodyDiameter = bodyRadius * 2

    g2.setColor(new Color(8, 10, 12, 238))
    g2.fillOval(bodyLeft, bodyTop, bodyDiameter, bodyDiameter)

    int neckW = Math.max(6, (int) Math.round(size * 0.23d))
    int neckH = Math.max(4, (int) Math.round(size * 0.10d))
    int neckX = bodyCx - bodyRadius + Math.max(1, bodyRadius.intdiv(6))
    int neckY = bodyCy - bodyRadius - Math.max(1, size.intdiv(16))
    AffineTransform oldTx = g2.transform
    g2.rotate(Math.toRadians(-24), neckX + (neckW / 2.0d), neckY + (neckH / 2.0d))
    g2.setColor(new Color(8, 10, 12, 238))
    g2.fillRoundRect(neckX, neckY, neckW, neckH, neckH, neckH)
    g2.setColor(new Color(244, 244, 244, 230))
    g2.drawRoundRect(neckX, neckY, neckW, neckH, neckH, neckH)
    g2.setTransform(oldTx)

    int shineArcW = Math.max(6, (int) Math.round(bodyRadius * 0.70d))
    int shineArcH = Math.max(10, (int) Math.round(bodyRadius * 1.35d))
    g2.setColor(new Color(245, 245, 245, 220))
    g2.drawArc(
        bodyLeft + Math.max(1, (int) Math.round(bodyRadius * 0.16d)),
        bodyTop + Math.max(2, (int) Math.round(bodyRadius * 0.52d)),
        shineArcW,
        shineArcH,
        112,
        118
    )
    int specW = Math.max(5, (int) Math.round(bodyRadius * 0.38d))
    int specH = Math.max(3, (int) Math.round(bodyRadius * 0.22d))
    g2.fillOval(
        bodyCx + Math.max(2, (int) Math.round(bodyRadius * 0.22d)),
        bodyCy - Math.max(4, (int) Math.round(bodyRadius * 0.56d)),
        specW,
        specH
    )

    int fuseStartX = neckX + Math.max(2, neckW.intdiv(2))
    int fuseStartY = neckY - Math.max(1, neckH.intdiv(3))
    int fuseEndX = Math.min(cx + Math.max(5, (int) Math.round(size * 0.25d)), cx + size.intdiv(2) - clipInset - 3)
    int fuseEndY = cy - Math.max(8, (int) Math.round(size * 0.27d))
    int controlX = cx + Math.max(1, (int) Math.round(size * 0.01d))
    int controlY = fuseStartY - Math.max(8, (int) Math.round(size * 0.15d))
    java.awt.Stroke oldStroke = g2.stroke
    QuadCurve2D.Double fuseCurve = new QuadCurve2D.Double(fuseStartX, fuseStartY, controlX, controlY, fuseEndX, fuseEndY)
    g2.stroke = new java.awt.BasicStroke(Math.max(2f, (float) (size * 0.065d)),
        java.awt.BasicStroke.CAP_ROUND,
        java.awt.BasicStroke.JOIN_ROUND)
    g2.setColor(new Color(92, 64, 38, 236))
    g2.draw(fuseCurve)
    g2.stroke = new java.awt.BasicStroke(Math.max(1f, (float) (size * 0.03d)),
        java.awt.BasicStroke.CAP_ROUND,
        java.awt.BasicStroke.JOIN_ROUND)
    g2.setColor(new Color(184, 138, 78, 228))
    g2.draw(fuseCurve)

    int rayA = Math.max(3, (int) Math.round(size * 0.06d))
    int rayB = Math.max(7, (int) Math.round(size * 0.13d))
    g2.stroke = new java.awt.BasicStroke(Math.max(1.4f, (float) (size * 0.028d)),
        java.awt.BasicStroke.CAP_ROUND,
        java.awt.BasicStroke.JOIN_ROUND)
    Color[] sparkPalette = [
        new Color(216, 28, 30, 235),
        new Color(245, 136, 26, 235),
        new Color(255, 220, 82, 235)
    ] as Color[]
    for (int i = 0; i < 9; i++) {
      double angle = (Math.PI * 2.0d * i) / 9.0d
      int x1 = fuseEndX + (int) Math.round(Math.cos(angle) * rayA)
      int y1 = fuseEndY + (int) Math.round(Math.sin(angle) * rayA)
      int x2 = fuseEndX + (int) Math.round(Math.cos(angle) * rayB)
      int y2 = fuseEndY + (int) Math.round(Math.sin(angle) * rayB)
      g2.setColor(sparkPalette[i % sparkPalette.length])
      g2.drawLine(x1, y1, x2, y2)
    }

    int outerW = Math.max(8, (int) Math.round(size * 0.20d))
    int outerH = Math.max(10, (int) Math.round(size * 0.24d))
    int outerX = fuseEndX - outerW.intdiv(2)
    int outerY = fuseEndY - outerH + Math.max(1, outerH.intdiv(5))
    g2.setColor(new Color(222, 40, 34, 232))
    g2.fillOval(outerX, outerY, outerW, outerH)

    int midW = Math.max(5, (int) Math.round(outerW * 0.64d))
    int midH = Math.max(6, (int) Math.round(outerH * 0.60d))
    int midX = fuseEndX - midW.intdiv(2)
    int midY = outerY + Math.max(1, outerH.intdiv(5))
    g2.setColor(new Color(255, 196, 52, 240))
    g2.fillOval(midX, midY, midW, midH)

    int coreW = Math.max(2, (int) Math.round(midW * 0.46d))
    int coreH = Math.max(2, (int) Math.round(midH * 0.46d))
    int coreX = fuseEndX - coreW.intdiv(2)
    int coreY = midY + Math.max(1, midH.intdiv(4))
    g2.setColor(new Color(255, 250, 230, 245))
    g2.fillOval(coreX, coreY, coreW, coreH)

    g2.stroke = oldStroke
    g2.setClip(oldClip)
  }

  private static void drawMushroomCloudOverlay(Graphics2D g2, int cx, int cy, int size) {
    int cloudRadius = Math.max(5, (int) Math.round(size * 0.18d))
    int topY = cy - Math.max(2, size.intdiv(5))
    g2.setColor(new Color(248, 240, 224, 235))
    g2.fillOval(cx - cloudRadius * 2, topY - cloudRadius, cloudRadius * 2, cloudRadius * 2)
    g2.fillOval(cx - cloudRadius, topY - cloudRadius - 2, cloudRadius * 2, cloudRadius * 2)
    g2.fillOval(cx, topY - cloudRadius, cloudRadius * 2, cloudRadius * 2)
    g2.fillOval(cx - cloudRadius * 2, topY, cloudRadius * 4, cloudRadius + 4)

    int stemW = Math.max(4, (int) Math.round(size * 0.13d))
    int stemH = Math.max(8, (int) Math.round(size * 0.24d))
    int stemX = cx - stemW.intdiv(2)
    int stemY = topY + cloudRadius - 1
    g2.setColor(new Color(236, 210, 170, 230))
    g2.fillRoundRect(stemX, stemY, stemW, stemH, stemW, stemW)

    g2.setColor(new Color(255, 178, 102, 220))
    int glowW = Math.max(8, (int) Math.round(size * 0.34d))
    int glowH = Math.max(4, (int) Math.round(size * 0.12d))
    g2.fillOval(cx - glowW.intdiv(2), stemY + stemH - glowH.intdiv(2), glowW, glowH)

    g2.setColor(new Color(194, 164, 126, 220))
    g2.drawOval(cx - cloudRadius * 2, topY, cloudRadius * 4, cloudRadius + 4)
    g2.drawRoundRect(stemX, stemY, stemW, stemH, stemW, stemW)
  }

  private static void drawFishOverlay(Graphics2D g2, int cx, int cy, int size) {
    int bodyW = Math.max(13, (int) Math.round(size * 0.56d))
    int bodyH = Math.max(9, (int) Math.round(size * 0.34d))
    int tailW = Math.max(7, (int) Math.round(size * 0.18d))
    int bodyX = cx - (bodyW + tailW).intdiv(2) + 1
    int bodyY = cy - bodyH.intdiv(2)
    int bodyRight = bodyX + bodyW
    int tailX = bodyRight - 1

    Color fishFill = new Color(244, 250, 255, 245)
    Color fishOutline = new Color(58, 82, 110, 240)

    // Body + tail silhouette
    g2.setColor(fishFill)
    g2.fillOval(bodyX, bodyY, bodyW, bodyH)
    g2.fillPolygon(
        [tailX, tailX + tailW, tailX + tailW] as int[],
        [cy, bodyY + 1, bodyY + bodyH - 1] as int[],
        3
    )
    g2.setColor(fishOutline)
    g2.drawOval(bodyX, bodyY, bodyW, bodyH)
    g2.drawPolygon(
        [tailX, tailX + tailW, tailX + tailW] as int[],
        [cy, bodyY + 1, bodyY + bodyH - 1] as int[],
        3
    )

    int dorsalBaseX = bodyX + Math.max(4, bodyW.intdiv(3))
    int dorsalTipX = dorsalBaseX + Math.max(4, bodyW.intdiv(5))
    int dorsalTipY = bodyY - Math.max(4, bodyH.intdiv(2))
    int dorsalBaseY = bodyY + Math.max(1, bodyH.intdiv(6))
    int ventralBaseX = bodyX + Math.max(6, bodyW.intdiv(2))
    int ventralTipX = ventralBaseX + Math.max(4, bodyW.intdiv(6))
    int ventralTipY = bodyY + bodyH + Math.max(3, bodyH.intdiv(3))
    int ventralBaseY = bodyY + bodyH - Math.max(1, bodyH.intdiv(6))

    // Fins
    g2.setColor(fishFill)
    g2.fillPolygon(
        [dorsalBaseX, dorsalTipX, dorsalBaseX + Math.max(6, bodyW.intdiv(4))] as int[],
        [dorsalBaseY, dorsalTipY, dorsalBaseY] as int[],
        3
    )
    g2.fillPolygon(
        [ventralBaseX, ventralTipX, ventralBaseX + Math.max(6, bodyW.intdiv(5))] as int[],
        [ventralBaseY, ventralTipY, ventralBaseY] as int[],
        3
    )
    g2.setColor(fishOutline)
    g2.drawPolygon(
        [dorsalBaseX, dorsalTipX, dorsalBaseX + Math.max(6, bodyW.intdiv(4))] as int[],
        [dorsalBaseY, dorsalTipY, dorsalBaseY] as int[],
        3
    )
    g2.drawPolygon(
        [ventralBaseX, ventralTipX, ventralBaseX + Math.max(6, bodyW.intdiv(5))] as int[],
        [ventralBaseY, ventralTipY, ventralBaseY] as int[],
        3
    )

    // Face/details: mouth, gill and eye slit for readability without dot artifacts.
    java.awt.Stroke oldStroke = g2.stroke
    g2.stroke = new java.awt.BasicStroke(Math.max(1.5f, (float) (size * 0.028d)),
        java.awt.BasicStroke.CAP_ROUND,
        java.awt.BasicStroke.JOIN_ROUND)
    int mouthX = bodyX + 1
    g2.drawArc(mouthX, cy - 3, Math.max(5, bodyW.intdiv(5)), 6, 210, 120)
    int gillX = bodyX + Math.max(4, bodyW.intdiv(4))
    g2.drawArc(gillX, bodyY + 2, Math.max(5, bodyW.intdiv(5)), bodyH - 4, 70, 220)
    int eyeY = bodyY + Math.max(3, bodyH.intdiv(3))
    int eyeX1 = bodyX + Math.max(7, bodyW.intdiv(5))
    int eyeX2 = eyeX1 + Math.max(4, bodyW.intdiv(9))
    g2.drawLine(eyeX1, eyeY, eyeX2, eyeY)
    g2.stroke = oldStroke
  }

  private void drawTransitionEffects(Graphics2D g2,
                                     int cellSize,
                                     int offsetX,
                                     int offsetY) {
    TransitionState state = transitionState
    if (state == null) {
      return
    }

    double progress = state.progress()
    double explosionEnd = EXPLOSION_PHASE_RATIO
    double movementStart = state.movementStartRatio
    double movementProgress = progress <= movementStart
        ? 0.0d
        : (progress - movementStart) / (1.0d - movementStart)
    double easedMovement = easeOutCubic(movementProgress)

    state.sprites.each { FallingSprite sprite ->
      double cellX = sprite.fromX + ((double) (sprite.toX - sprite.fromX) * easedMovement)
      double cellY = sprite.fromY + ((double) (sprite.toY - sprite.fromY) * easedMovement)
      int left = offsetX + (int) Math.round(cellX * cellSize)
      int top = offsetY + (int) Math.round(cellY * cellSize)
      drawCandyAtCell(g2, sprite.piece, left, top, cellSize, 0.94f)
    }

    double burstWindow = Math.min(1.0d, explosionEnd + BURST_TAIL_RATIO)
    if (progress <= burstWindow) {
      double burstProgress = easeOutCubic(progress / burstWindow)
      state.bursts.each { Burst burst ->
        int centerX = offsetX + (burst.x * cellSize) + (cellSize.intdiv(2))
        int centerY = offsetY + (burst.y * cellSize) + (cellSize.intdiv(2))
        int radius = Math.max(2, (int) Math.round((cellSize * 0.10d) + (cellSize * 0.38d * burstProgress)))
        int alpha = Math.max(0, (int) Math.round(180 * (1.0d - burstProgress)))

        Color base = colorFor(burst.candy)
        g2.setColor(new Color(base.red, base.green, base.blue, alpha))
        g2.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2)

        int innerRadius = Math.max(1, (int) Math.round(radius * 0.52d))
        int innerAlpha = Math.max(0, (int) Math.round(140 * (1.0d - burstProgress)))
        g2.setColor(new Color(255, 255, 255, innerAlpha))
        g2.fillOval(centerX - innerRadius, centerY - innerRadius, innerRadius * 2, innerRadius * 2)
      }
    }

    drawSmallBombEffects(g2, state.smallBombs, progress, cellSize, offsetX, offsetY)
    drawBombBeamEffects(g2, state.bombBeams, progress, cellSize, offsetX, offsetY)
    drawFishSwimEffects(g2, state.fishSwims, progress, cellSize, offsetX, offsetY)
    drawSweeperEffects(g2, state.sweeps, state.fishSwims, progress, cellSize, offsetX, offsetY)
  }

  private void drawSmallBombEffects(Graphics2D g2,
                                    List<SmallBombActivation> bombs,
                                    double progress,
                                    int cellSize,
                                    int offsetX,
                                    int offsetY) {
    Board currentBoard = board
    if (bombs == null || bombs.isEmpty() || currentBoard == null) {
      return
    }

    double burstWindow = Math.min(1.0d, EXPLOSION_PHASE_RATIO + BURST_TAIL_RATIO)
    if (progress > burstWindow) {
      return
    }
    double burstProgress = easeOutCubic(progress / burstWindow)

    bombs.each { SmallBombActivation bomb ->
      if (currentBoard.inBounds(bomb.originX, bomb.originY)) {
        SMALL_BOMB_BLAST_DELTAS.each { int[] delta ->
          int cellX = bomb.originX + delta[0]
          int cellY = bomb.originY + delta[1]
          if (currentBoard.inBounds(cellX, cellY)) {
            int centerX = offsetX + (cellX * cellSize) + (cellSize.intdiv(2))
            int centerY = offsetY + (cellY * cellSize) + (cellSize.intdiv(2))
            int radius = (int) Math.round((cellSize * 0.24d) + (cellSize * 0.96d * burstProgress))

            int alpha = Math.max(0, (int) Math.round(235 * (1.0d - burstProgress)))
            g2.setColor(new Color(255, 180, 120, alpha))
            g2.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2)

            int ringAlpha = Math.max(0, (int) Math.round(255 * (1.0d - burstProgress)))
            g2.setColor(new Color(255, 255, 255, ringAlpha))
            g2.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2)

            int sparkAlpha = Math.max(0, (int) Math.round(230 * (1.0d - burstProgress)))
            g2.setColor(new Color(255, 248, 200, sparkAlpha))
            int ray = Math.max(2, (int) Math.round(radius * 1.35d))
            g2.drawLine(centerX - ray, centerY, centerX + ray, centerY)
            g2.drawLine(centerX, centerY - ray, centerX, centerY + ray)
            int diag = Math.max(2, (int) Math.round(ray * 0.72d))
            g2.drawLine(centerX - diag, centerY - diag, centerX + diag, centerY + diag)
            g2.drawLine(centerX - diag, centerY + diag, centerX + diag, centerY - diag)
          }
        }
      }
    }
  }

  private void drawSweeperEffects(Graphics2D g2,
                                  List<SweepActivation> sweeps,
                                  List<FishSwimActivation> fishSwims,
                                  double progress,
                                  int cellSize,
                                  int offsetX,
                                  int offsetY) {
    if (sweeps == null || sweeps.isEmpty() || board == null) {
      return
    }

    boolean delayedByFish = fishSwims != null && !fishSwims.isEmpty()
    double startRatio = delayedByFish ? SWEEP_AFTER_FISH_START_RATIO : 0.0d
    double endRatio = delayedByFish ? SWEEP_AFTER_FISH_END_RATIO : SWEEP_PHASE_RATIO
    if (progress < startRatio || progress > endRatio) {
      return
    }

    double normalized = delayedByFish
        ? Math.max(0.0d, Math.min(1.0d, (progress - startRatio) / Math.max(0.0001d, endRatio - startRatio)))
        : Math.max(0.0d, Math.min(1.0d, progress / SWEEP_PHASE_RATIO))
    double extent = easeOutCubic(normalized)
    int alphaBase = delayedByFish ? 245 : 225
    int alpha = Math.max(0, (int) Math.round(alphaBase * (1.0d - normalized)))
    if (alpha <= 0) {
      return
    }

    sweeps.each { SweepActivation sweep ->
      if (!board.inBounds(sweep.originX, sweep.originY)) {
        return
      }

      int centerX = offsetX + (sweep.originX * cellSize) + (cellSize.intdiv(2))
      int centerY = offsetY + (sweep.originY * cellSize) + (cellSize.intdiv(2))

      if (sweep.horizontal) {
        double maxLeft = sweep.originX + 0.5d
        double maxRight = (board.width - 1 - sweep.originX) + 0.5d
        int left = centerX - (int) Math.round(maxLeft * cellSize * extent)
        int right = centerX + (int) Math.round(maxRight * cellSize * extent)
        drawSweepBeam(g2, left, centerY, right, centerY, alpha, cellSize)
      } else {
        double maxUp = sweep.originY + 0.5d
        double maxDown = (board.height - 1 - sweep.originY) + 0.5d
        int top = centerY - (int) Math.round(maxUp * cellSize * extent)
        int bottom = centerY + (int) Math.round(maxDown * cellSize * extent)
        drawSweepBeam(g2, centerX, top, centerX, bottom, alpha, cellSize)
      }
    }
  }

  private void drawBombBeamEffects(Graphics2D g2,
                                   List<BombBeamActivation> bombBeams,
                                   double progress,
                                   int cellSize,
                                   int offsetX,
                                   int offsetY) {
    if (bombBeams == null || bombBeams.isEmpty() || board == null || progress > SWEEP_PHASE_RATIO) {
      return
    }

    double normalized = Math.max(0.0d, Math.min(1.0d, progress / SWEEP_PHASE_RATIO))
    double extent = easeOutCubic(normalized)
    int alpha = Math.max(0, (int) Math.round(210 * (1.0d - normalized)))
    if (alpha <= 0) {
      return
    }

    java.awt.Stroke oldStroke = g2.stroke
    bombBeams.each { BombBeamActivation beam ->
      if (!board.inBounds(beam.originX, beam.originY) || !board.inBounds(beam.targetX, beam.targetY)) {
        return
      }

      int startX = offsetX + (beam.originX * cellSize) + (cellSize.intdiv(2))
      int startY = offsetY + (beam.originY * cellSize) + (cellSize.intdiv(2))
      int targetX = offsetX + (beam.targetX * cellSize) + (cellSize.intdiv(2))
      int targetY = offsetY + (beam.targetY * cellSize) + (cellSize.intdiv(2))
      int beamEndX = startX + (int) Math.round((targetX - startX) * extent)
      int beamEndY = startY + (int) Math.round((targetY - startY) * extent)

      g2.stroke = new java.awt.BasicStroke(Math.max(2f, (float) (cellSize * 0.09d)),
          java.awt.BasicStroke.CAP_ROUND,
          java.awt.BasicStroke.JOIN_ROUND)
      g2.setColor(new Color(255, 214, 140, alpha))
      g2.drawLine(startX, startY, beamEndX, beamEndY)

      g2.stroke = new java.awt.BasicStroke(Math.max(1f, (float) (cellSize * 0.04d)),
          java.awt.BasicStroke.CAP_ROUND,
          java.awt.BasicStroke.JOIN_ROUND)
      g2.setColor(new Color(255, 250, 220, Math.min(255, alpha + 25)))
      g2.drawLine(startX, startY, beamEndX, beamEndY)

      int sparkRadius = Math.max(2, (int) Math.round(cellSize * 0.08d))
      g2.setColor(new Color(255, 244, 188, Math.max(0, alpha - 15)))
      g2.fillOval(beamEndX - sparkRadius, beamEndY - sparkRadius, sparkRadius * 2, sparkRadius * 2)
    }
    g2.stroke = oldStroke
  }

  private void drawFishSwimEffects(Graphics2D g2,
                                   List<FishSwimActivation> fishSwims,
                                   double progress,
                                   int cellSize,
                                   int offsetX,
                                   int offsetY) {
    if (fishSwims == null || fishSwims.isEmpty() || board == null) {
      return
    }

    if (progress > FISH_SWIM_PHASE_RATIO) {
      return
    }

    double t = easeOutCubic(Math.max(0.0d, Math.min(1.0d, progress / FISH_SWIM_PHASE_RATIO)))
    int fishSize = Math.max(14, (int) Math.round(cellSize * 0.72d))

    fishSwims.each { FishSwimActivation swim ->
      if (!board.inBounds(swim.originX, swim.originY) || !board.inBounds(swim.targetX, swim.targetY)) {
        return
      }

      double startX = offsetX + (swim.originX * cellSize) + (cellSize / 2.0d)
      double startY = offsetY + (swim.originY * cellSize) + (cellSize / 2.0d)
      double endX = offsetX + (swim.targetX * cellSize) + (cellSize / 2.0d)
      double endY = offsetY + (swim.targetY * cellSize) + (cellSize / 2.0d)
      double distance = Math.hypot(endX - startX, endY - startY)
      double lift = Math.max(cellSize * 0.60d, Math.min(cellSize * 2.2d, distance * 0.42d))
      double controlX = (startX + endX) / 2.0d
      double controlY = Math.min(startY, endY) - lift

      double px = quadraticPoint(startX, controlX, endX, t)
      double py = quadraticPoint(startY, controlY, endY, t)
      double tangentX = quadraticTangent(startX, controlX, endX, t)
      double tangentY = quadraticTangent(startY, controlY, endY, t)
      double heading = Math.atan2(tangentY, tangentX)

      for (int i = 1; i <= 4; i++) {
        double trailT = Math.max(0.0d, t - (i * 0.12d))
        double trailX = quadraticPoint(startX, controlX, endX, trailT)
        double trailY = quadraticPoint(startY, controlY, endY, trailT)
        int trailAlpha = Math.max(0, (int) Math.round((145 - (i * 24)) * (1.0d - t)))
        int bubbleR = Math.max(1, (int) Math.round(cellSize * (0.05d + (0.01d * i))))
        g2.setColor(new Color(210, 236, 255, trailAlpha))
        g2.fillOval((int) Math.round(trailX) - bubbleR, (int) Math.round(trailY) - bubbleR, bubbleR * 2, bubbleR * 2)
      }

      Composite oldComposite = g2.composite
      float fishAlpha = (float) Math.max(0.35d, 1.0d - (t * 0.10d))
      g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fishAlpha)
      AffineTransform oldTx = g2.transform
      g2.translate(px, py)
      g2.rotate(heading)
      drawFishOverlay(g2, 0, 0, fishSize)
      g2.setTransform(oldTx)
      g2.composite = oldComposite

      if (t > 0.72d) {
        double pulse = (t - 0.72d) / 0.28d
        int rippleAlpha = Math.max(0, (int) Math.round(170 * (1.0d - pulse)))
        int rippleRadius = Math.max(3, (int) Math.round((cellSize * 0.16d) + (cellSize * 0.44d * pulse)))
        int tx = (int) Math.round(endX)
        int ty = (int) Math.round(endY)
        g2.setColor(new Color(196, 236, 255, rippleAlpha))
        g2.drawOval(tx - rippleRadius, ty - rippleRadius, rippleRadius * 2, rippleRadius * 2)
      }

      // Dedicated fish impact burst so fish hits stay visible even when standard bursts are suppressed.
      if (t > 0.70d) {
        double impact = (t - 0.70d) / 0.30d
        double easedImpact = easeOutCubic(Math.max(0.0d, Math.min(1.0d, impact)))
        int tx = (int) Math.round(endX)
        int ty = (int) Math.round(endY)

        int outerRadius = Math.max(4, (int) Math.round((cellSize * 0.10d) + (cellSize * 0.36d * easedImpact)))
        int outerAlpha = Math.max(0, (int) Math.round(210 * (1.0d - easedImpact)))
        g2.setColor(new Color(255, 196, 124, outerAlpha))
        g2.fillOval(tx - outerRadius, ty - outerRadius, outerRadius * 2, outerRadius * 2)

        int innerRadius = Math.max(2, (int) Math.round(outerRadius * 0.52d))
        int innerAlpha = Math.max(0, (int) Math.round(190 * (1.0d - easedImpact)))
        g2.setColor(new Color(255, 245, 214, innerAlpha))
        g2.fillOval(tx - innerRadius, ty - innerRadius, innerRadius * 2, innerRadius * 2)

        int rayLen = Math.max(3, (int) Math.round(outerRadius * 1.20d))
        int rayAlpha = Math.max(0, (int) Math.round(185 * (1.0d - easedImpact)))
        g2.setColor(new Color(255, 236, 188, rayAlpha))
        g2.drawLine(tx - rayLen, ty, tx + rayLen, ty)
        g2.drawLine(tx, ty - rayLen, tx, ty + rayLen)
      }
    }
  }

  private static double quadraticPoint(double p0, double p1, double p2, double t) {
    double u = 1.0d - t
    (u * u * p0) + (2.0d * u * t * p1) + (t * t * p2)
  }

  private static double quadraticTangent(double p0, double p1, double p2, double t) {
    (2.0d * (1.0d - t) * (p1 - p0)) + (2.0d * t * (p2 - p1))
  }

  private static void drawSweepBeam(Graphics2D g2,
                                    int x1,
                                    int y1,
                                    int x2,
                                    int y2,
                                    int alpha,
                                    int cellSize) {
    int coreAlpha = Math.max(0, Math.min(255, alpha))
    int glowAlpha = Math.max(0, (int) Math.round(coreAlpha * 0.52d))
    int thickness = Math.max(2, (int) Math.round(cellSize * 0.18d))
    int glowThickness = Math.max(thickness + 2, (int) Math.round(cellSize * 0.34d))

    java.awt.Stroke oldStroke = g2.stroke
    g2.setColor(new Color(255, 255, 255, glowAlpha))
    g2.stroke = new java.awt.BasicStroke(glowThickness, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    g2.drawLine(x1, y1, x2, y2)

    g2.setColor(new Color(255, 248, 190, coreAlpha))
    g2.stroke = new java.awt.BasicStroke(thickness, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    g2.drawLine(x1, y1, x2, y2)
    g2.stroke = oldStroke
  }

  private void drawDragGhost(Graphics2D g2, int cellSize) {
    if (!dragging || draggedCandy == null) {
      return
    }

    int inset = Math.max(2, cellSize / 10)
    int drawSize = cellSize - (2 * inset)
    int left = dragPixelX - drawSize.intdiv(2)
    int top = dragPixelY - drawSize.intdiv(2)

    drawCandy(g2, draggedCandy, left, top, drawSize, 0.88f)

    g2.setColor(new Color(17, 24, 39, 120))
    g2.drawOval(left, top, drawSize, drawSize)
  }

  private Position resolveBoardCell(int pixelX, int pixelY) {
    Position cell = toBoardCell(pixelX, pixelY)
    if (cell != null) {
      return cell
    }

    double scaleX = uiScaleX()
    double scaleY = uiScaleY()
    if (scaleX > 1.0d || scaleY > 1.0d) {
      int normalizedX = (int) Math.round(pixelX / scaleX)
      int normalizedY = (int) Math.round(pixelY / scaleY)
      return toBoardCell(normalizedX, normalizedY)
    }

    null
  }

  private double uiScaleX() {
    try {
      graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0d
    } catch (Exception ignored) {
      1.0d
    }
  }

  private double uiScaleY() {
    try {
      graphicsConfiguration?.defaultTransform?.scaleY ?: 1.0d
    } catch (Exception ignored) {
      1.0d
    }
  }

  private void handleCellSelection(Position cell) {
    if (cell == null) {
      return
    }
    clearHintState(false)

    if (selectedCell == null) {
      selectedCell = cell
      repaint()
      return
    }

    if (selectedCell == cell) {
      selectedCell = null
      repaint()
      return
    }

    if (!isOrthogonallyAdjacent(selectedCell, cell)) {
      selectedCell = cell
      repaint()
      return
    }

    Position first = selectedCell
    trySwap(first, cell)
  }

  private int boardOffsetX(int panelWidth, int panelHeight) {
    if (board == null) {
      return 0
    }
    int cellSize = computeCellSize(panelWidth, panelHeight)
    int boardWidth = board.width * cellSize
    Math.max(0, (panelWidth - boardWidth).intdiv(2))
  }

  private int boardOffsetY(int panelWidth, int panelHeight) {
    if (board == null) {
      return 0
    }
    int cellSize = computeCellSize(panelWidth, panelHeight)
    int boardHeight = board.height * cellSize
    Math.max(0, (panelHeight - boardHeight).intdiv(2))
  }

  private boolean isHiddenAnimationTarget(int x, int y) {
    TransitionState state = transitionState
    if (state == null) {
      return false
    }
    double progress = state.progress()
    if (progress >= 1.0d || progress < state.movementStartRatio) {
      return false
    }
    state.targetCells.contains(new Position(x, y))
  }

  private boolean isDraggedSourceCell(int x, int y) {
    dragging && pressedCell != null && pressedCell.x == x && pressedCell.y == y
  }

  private void clearDragState() {
    pressedCell = null
    dragging = false
    draggedCandy = null
  }

  private void clearHintState(boolean doRepaint = true) {
    hintFirst = null
    hintSecond = null
    if (doRepaint) {
      repaint()
    }
  }

  private boolean isHintCell(int x, int y) {
    (hintFirst != null && hintFirst.x == x && hintFirst.y == y) ||
        (hintSecond != null && hintSecond.x == x && hintSecond.y == y)
  }

  private void startTransitionAnimation(Board previous, Board next) {
    stopTransitionAnimation()

    List<SweepActivation> sweeps = List.copyOf(pendingSweepActivations)
    List<SmallBombActivation> smallBombs = List.copyOf(pendingSmallBombActivations)
    List<FishSwimActivation> fishSwims = List.copyOf(pendingFishSwims)
    List<BombBeamActivation> bombBeams = List.copyOf(pendingBombBeams)
    pendingSweepActivations.clear()
    pendingSmallBombActivations.clear()
    pendingFishSwims.clear()
    pendingBombBeams.clear()
    TransitionState state = buildTransitionState(previous, next, sweeps, smallBombs, fishSwims, bombBeams)
    if (state == null) {
      repaint()
      return
    }

    transitionState = state
    visualAnimationRunning = true

    transitionTimer = new Timer(16, { ignored ->
      TransitionState current = transitionState
      if (current == null) {
        stopTransitionAnimation()
        return
      }

      repaint()
      if (current.progress() >= 1.0d) {
        stopTransitionAnimation()
        repaint()
      }
    })
    transitionTimer.setCoalesce(true)
    transitionTimer.start()
  }

  private void stopTransitionAnimation() {
    if (transitionTimer != null) {
      transitionTimer.stop()
      transitionTimer = null
    }
    transitionState = null
    visualAnimationRunning = false
  }

  private static boolean hasChanges(Board previous, Board next) {
    for (int y = 0; y < previous.height; y++) {
      for (int x = 0; x < previous.width; x++) {
        if (!piecesEqual(previous.getPiece(x, y), next.getPiece(x, y))) {
          return true
        }
      }
    }
    false
  }

  private static TransitionState buildTransitionState(Board previous,
                                                      Board next,
                                                      List<SweepActivation> sweeps = [],
                                                      List<SmallBombActivation> smallBombs = [],
                                                      List<FishSwimActivation> fishSwims = [],
                                                      List<BombBeamActivation> bombBeams = []) {
    List<FallingSprite> sprites = []
    List<Burst> bursts = []
    Set<Position> targets = new HashSet<>()
    Set<Position> forcedClearCells = collectForcedClearCells(previous, sweeps, smallBombs, fishSwims)
    boolean suppressStandardBursts =
        (sweeps != null && !sweeps.isEmpty()) ||
            (smallBombs != null && !smallBombs.isEmpty())

    for (int x = 0; x < previous.width; x++) {
      boolean[] oldUsed = new boolean[previous.height]

      for (int y = 0; y < previous.height; y++) {
        Position pos = new Position(x, y)
        if (!forcedClearCells.contains(pos) && piecesEqual(previous.getPiece(x, y), next.getPiece(x, y))) {
          oldUsed[y] = true
        }
      }

      int spawnOffset = 1
      for (int y = previous.height - 1; y >= 0; y--) {
        Piece incoming = next.getPiece(x, y)
        if (incoming == null) {
          continue
        }
        Position targetPos = new Position(x, y)
        boolean forcedTarget = forcedClearCells.contains(targetPos)
        if (!forcedTarget && piecesEqual(previous.getPiece(x, y), incoming) && oldUsed[y]) {
          continue
        }

        int sourceY = findSourceY(previous, x, y, incoming, oldUsed, forcedTarget)
        if (sourceY >= 0) {
          oldUsed[sourceY] = true
        } else {
          sourceY = -spawnOffset
          spawnOffset++
        }

        sprites << new FallingSprite(incoming, x, sourceY, x, y)
        targets.add(new Position(x, y))
      }

      for (int y = 0; y < previous.height; y++) {
        Piece oldPiece = previous.getPiece(x, y)
        Piece newPiece = next.getPiece(x, y)
        if (!suppressStandardBursts && oldPiece != null && !piecesEqual(oldPiece, newPiece)) {
          bursts << new Burst(oldPiece.color, x, y)
        }
      }
    }

    if (sprites.isEmpty() &&
        bursts.isEmpty() &&
        (sweeps == null || sweeps.isEmpty()) &&
        (smallBombs == null || smallBombs.isEmpty()) &&
        (fishSwims == null || fishSwims.isEmpty()) &&
        (bombBeams == null || bombBeams.isEmpty())) {
      return null
    }

    boolean hasSweeps = sweeps != null && !sweeps.isEmpty()
    boolean hasFish = fishSwims != null && !fishSwims.isEmpty()

    double movementStart = EXPLOSION_PHASE_RATIO
    if (hasSweeps && hasFish) {
      movementStart = Math.max(movementStart, SWEEP_AFTER_FISH_END_RATIO)
    } else if (hasFish) {
      movementStart = Math.max(movementStart, FISH_SWIM_PHASE_RATIO)
    } else if (hasSweeps) {
      movementStart = Math.max(movementStart, SWEEP_PHASE_RATIO)
    }
    movementStart = Math.min(0.96d, movementStart)

    long duration = TRANSITION_DURATION_NANOS
    if (hasSweeps && hasFish) {
      duration = (long) Math.round(TRANSITION_DURATION_NANOS * 1.45d)
    } else if (hasFish) {
      duration = (long) Math.round(TRANSITION_DURATION_NANOS * 1.18d)
    }

    new TransitionState(duration, movementStart, sprites, bursts, targets, sweeps ?: [], smallBombs ?: [], fishSwims ?: [], bombBeams ?: [])
  }

  private static Set<Position> collectForcedClearCells(Board board,
                                                       List<SweepActivation> sweeps,
                                                       List<SmallBombActivation> smallBombs,
                                                       List<FishSwimActivation> fishSwims) {
    Set<Position> cells = [] as Set<Position>
    if (board == null) {
      return cells
    }

    sweeps?.each { SweepActivation sweep ->
      if (sweep == null || !board.inBounds(sweep.originX, sweep.originY)) {
        return
      }
      if (sweep.horizontal) {
        for (int x = 0; x < board.width; x++) {
          cells << new Position(x, sweep.originY)
        }
      } else {
        for (int y = 0; y < board.height; y++) {
          cells << new Position(sweep.originX, y)
        }
      }
    }

    smallBombs?.each { SmallBombActivation bomb ->
      if (bomb == null || !board.inBounds(bomb.originX, bomb.originY)) {
        return
      }
      SMALL_BOMB_BLAST_DELTAS.each { int[] delta ->
        int x = bomb.originX + delta[0]
        int y = bomb.originY + delta[1]
        if (board.inBounds(x, y)) {
          cells << new Position(x, y)
        }
      }
    }

    fishSwims?.each { FishSwimActivation fish ->
      if (fish != null && board.inBounds(fish.targetX, fish.targetY)) {
        cells << new Position(fish.targetX, fish.targetY)
      }
    }

    return cells
  }

  private static int findSourceY(Board previous,
                                 int x,
                                 int targetY,
                                 Piece piece,
                                 boolean[] oldUsed,
                                 boolean forceDropFromAbove = false) {
    if (forceDropFromAbove) {
      for (int y = targetY - 1; y >= 0; y--) {
        if (!oldUsed[y] && piecesEqual(previous.getPiece(x, y), piece)) {
          return y
        }
      }
      return -1
    }

    for (int y = targetY; y >= 0; y--) {
      if (!oldUsed[y] && piecesEqual(previous.getPiece(x, y), piece)) {
        return y
      }
    }

    for (int y = previous.height - 1; y >= 0; y--) {
      if (!oldUsed[y] && piecesEqual(previous.getPiece(x, y), piece)) {
        return y
      }
    }

    -1
  }

  private static double easeOutCubic(double t) {
    double v = Math.max(0.0d, Math.min(1.0d, t))
    1.0d - Math.pow(1.0d - v, 3.0d)
  }

  private static boolean isOrthogonallyAdjacent(Position first, Position second) {
    Math.abs(first.x - second.x) + Math.abs(first.y - second.y) == 1
  }

  private static boolean piecesEqual(Piece left, Piece right) {
    if (left == null || right == null) {
      return left == right
    }
    left.color == right.color &&
        left.specialType == right.specialType &&
        left.sweeperHorizontal == right.sweeperHorizontal
  }

  private static Map<CandyType, BufferedImage> loadCandyImages() {
    Map<CandyType, String> svgResourceNames = [
        (CandyType.RED)   : '/images/red.svg',
        (CandyType.BLUE)  : '/images/blue.svg',
        (CandyType.GREEN) : '/images/green.svg',
        (CandyType.YELLOW): '/images/yellow.svg',
        (CandyType.PURPLE): '/images/purple.svg',
        (CandyType.ORANGE): '/images/orange.svg'
    ]
    Map<CandyType, String> pngFallbackNames = [
        (CandyType.RED)   : '/images/red.png',
        (CandyType.BLUE)  : '/images/blue.png',
        (CandyType.GREEN) : '/images/green.png',
        (CandyType.YELLOW): '/images/yellow.png',
        (CandyType.PURPLE): '/images/purple.png',
        (CandyType.ORANGE): '/images/orange.png'
    ]

    Map<CandyType, BufferedImage> images = [:]
    svgResourceNames.each { CandyType type, String svgPath ->
      BufferedImage image = loadSvgImage(svgPath, SVG_ICON_BASE_SIZE, SVG_ICON_BASE_SIZE)
      if (image == null) {
        image = loadPngImage(pngFallbackNames[type])
      }
      if (image != null) {
        images[type] = image
      }
    }

    images.asImmutable()
  }

  private static BufferedImage loadPngImage(String path) {
    if (!path) {
      return null
    }
    InputStream stream = BoardPanel.class.getResourceAsStream(path)
    if (stream == null) {
      return null
    }
    try {
      return ImageIO.read(stream)
    } catch (IOException ignored) {
      return null
    } finally {
      stream.close()
    }
  }

  private static BufferedImage loadSvgImage(String path, int width, int height) {
    if (!path) {
      return null
    }

    URL resource = BoardPanel.class.getResource(path)
    if (resource == null) {
      return null
    }

    try {
      SVGDocument document = new SVGLoader().load(resource)
      if (document == null) {
        return null
      }

      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      Graphics2D g2 = image.createGraphics()
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        document.render(null, g2, new ViewBox(0, 0, width, height))
      } finally {
        g2.dispose()
      }
      return image
    } catch (Exception ignored) {
      return null
    }
  }

  private static final class TransitionState {
    final long startNanos
    final long durationNanos
    final double movementStartRatio
    final List<FallingSprite> sprites
    final List<Burst> bursts
    final Set<Position> targetCells
    final List<SweepActivation> sweeps
    final List<SmallBombActivation> smallBombs
    final List<FishSwimActivation> fishSwims
    final List<BombBeamActivation> bombBeams

    TransitionState(long durationNanos,
                    double movementStartRatio,
                    List<FallingSprite> sprites,
                    List<Burst> bursts,
                    Set<Position> targetCells,
                    List<SweepActivation> sweeps,
                    List<SmallBombActivation> smallBombs,
                    List<FishSwimActivation> fishSwims,
                    List<BombBeamActivation> bombBeams) {
      this.startNanos = System.nanoTime()
      this.durationNanos = durationNanos
      this.movementStartRatio = movementStartRatio
      this.sprites = List.copyOf(sprites)
      this.bursts = List.copyOf(bursts)
      this.targetCells = Set.copyOf(targetCells)
      this.sweeps = List.copyOf(sweeps)
      this.smallBombs = List.copyOf(smallBombs)
      this.fishSwims = List.copyOf(fishSwims)
      this.bombBeams = List.copyOf(bombBeams)
    }

    double progress() {
      long elapsed = System.nanoTime() - startNanos
      Math.max(0.0d, Math.min(1.0d, elapsed / (double) durationNanos))
    }
  }

  private static final class FallingSprite {
    final Piece piece
    final int fromX
    final int fromY
    final int toX
    final int toY

    FallingSprite(Piece piece, int fromX, int fromY, int toX, int toY) {
      this.piece = piece
      this.fromX = fromX
      this.fromY = fromY
      this.toX = toX
      this.toY = toY
    }
  }

  private static final class Burst {
    final CandyType candy
    final int x
    final int y

    Burst(CandyType candy, int x, int y) {
      this.candy = candy
      this.x = x
      this.y = y
    }
  }

  private static final class SweepActivation {
    final int originX
    final int originY
    final boolean horizontal

    SweepActivation(int originX, int originY, boolean horizontal) {
      this.originX = originX
      this.originY = originY
      this.horizontal = horizontal
    }
  }

  private static final class SmallBombActivation {
    final int originX
    final int originY

    SmallBombActivation(int originX, int originY) {
      this.originX = originX
      this.originY = originY
    }
  }

  private static final class FishSwimActivation {
    final int originX
    final int originY
    final int targetX
    final int targetY

    FishSwimActivation(int originX, int originY, int targetX, int targetY) {
      this.originX = originX
      this.originY = originY
      this.targetX = targetX
      this.targetY = targetY
    }
  }

  private static final class BombBeamActivation {
    final int originX
    final int originY
    final int targetX
    final int targetY

    BombBeamActivation(int originX, int originY, int targetX, int targetY) {
      this.originX = originX
      this.originY = originY
      this.targetX = targetX
      this.targetY = targetY
    }
  }
}
