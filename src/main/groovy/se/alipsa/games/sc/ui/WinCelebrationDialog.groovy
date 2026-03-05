package se.alipsa.games.sc.ui

import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window

class WinCelebrationDialog extends JDialog {

  private static final int DURATION_MS = 3000

  private final CelebrationPanel celebrationPanel

  private WinCelebrationDialog(Window owner, String message) {
    super(owner, 'Level Complete', ModalityType.APPLICATION_MODAL)
    setDefaultCloseOperation(DISPOSE_ON_CLOSE)
    setResizable(false)

    celebrationPanel = new CelebrationPanel(message, DURATION_MS)
    add(celebrationPanel)
    pack()
    setLocationRelativeTo(owner)
  }

  static void show(Component parent, String message) {
    Window owner = parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent)
    WinCelebrationDialog dialog = new WinCelebrationDialog(owner, message)
    dialog.celebrationPanel.start {
      dialog.dispose()
    }
    dialog.setVisible(true)
    dialog.celebrationPanel.stop()
  }

  private static final class CelebrationPanel extends JPanel {
    private final String message
    private final int durationMs
    private final List<Burst> bursts
    private long startedAtMs
    private Timer timer

    CelebrationPanel(String message, int durationMs) {
      this.message = message
      this.durationMs = durationMs
      this.bursts = createBursts()
      preferredSize = new Dimension(620, 300)
      opaque = true
    }

    void start(Runnable onFinish) {
      startedAtMs = System.currentTimeMillis()
      timer = new Timer(16, { ignored ->
        long elapsed = System.currentTimeMillis() - startedAtMs
        repaint()
        if (elapsed >= durationMs) {
          stop()
          onFinish?.run()
        }
      })
      timer.setCoalesce(true)
      timer.start()
    }

    void stop() {
      if (timer != null) {
        timer.stop()
        timer = null
      }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics)
      Graphics2D g2 = (Graphics2D) graphics.create()
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

      GradientPaint background = new GradientPaint(
          0f, 0f, new Color(16, 30, 72),
          0f, getHeight(), new Color(40, 19, 84)
      )
      g2.setPaint(background)
      g2.fillRect(0, 0, getWidth(), getHeight())

      long elapsed = startedAtMs <= 0 ? 0 : (System.currentTimeMillis() - startedAtMs)
      bursts.each { Burst burst ->
        drawBurst(g2, burst, elapsed)
      }

      g2.setColor(new Color(255, 255, 255, 245))
      g2.setFont(getFont().deriveFont(Font.BOLD, 34f))
      int textY = getHeight().intdiv(2) + 12
      drawCenteredString(g2, message, textY)

      g2.setColor(new Color(220, 235, 255, 190))
      g2.setFont(getFont().deriveFont(Font.PLAIN, 16f))
      drawCenteredString(g2, 'Continuing in 3 seconds...', textY + 34)

      g2.dispose()
    }

    private void drawBurst(Graphics2D g2, Burst burst, long elapsedMs) {
      long local = elapsedMs - burst.delayMs
      if (local < 0 || local > burst.durationMs) {
        return
      }

      double t = local / (double) burst.durationMs
      double eased = 1.0d - Math.pow(1.0d - t, 3.0d)
      int radius = (int) Math.round(6 + (burst.maxRadius * eased))
      int alpha = Math.max(0, (int) Math.round(255 * (1.0d - t)))

      g2.setStroke(new BasicStroke(2.0f))
      g2.setColor(new Color(burst.color.red, burst.color.green, burst.color.blue, alpha))
      int spoke = Math.max(6, (int) Math.round(radius * 0.9d))

      for (int i = 0; i < burst.spokes; i++) {
        double angle = (Math.PI * 2.0d * i) / burst.spokes
        int dx = (int) Math.round(Math.cos(angle) * spoke)
        int dy = (int) Math.round(Math.sin(angle) * spoke)
        g2.drawLine(burst.x, burst.y, burst.x + dx, burst.y + dy)
      }

      int core = Math.max(3, radius.intdiv(4))
      g2.setColor(new Color(255, 255, 255, Math.max(0, alpha - 40)))
      g2.fillOval(burst.x - core, burst.y - core, core * 2, core * 2)
    }

    private void drawCenteredString(Graphics2D g2, String text, int y) {
      int textWidth = g2.fontMetrics.stringWidth(text)
      int x = Math.max(0, (getWidth() - textWidth).intdiv(2))
      g2.drawString(text, x, y)
    }

    private List<Burst> createBursts() {
      Random random = new Random(2026L)
      Color[] palette = [
          new Color(255, 96, 96),
          new Color(255, 196, 72),
          new Color(89, 215, 255),
          new Color(157, 255, 127),
          new Color(221, 145, 255)
      ] as Color[]

      List<Burst> values = []
      for (int i = 0; i < 18; i++) {
        int delay = random.nextInt(Math.max(1, durationMs - 900))
        int x = 70 + random.nextInt(480)
        int y = 40 + random.nextInt(150)
        int radius = 22 + random.nextInt(24)
        int spokes = 8 + random.nextInt(5)
        Color color = palette[random.nextInt(palette.length)]
        values << new Burst(x, y, delay, 880, radius, spokes, color)
      }
      values
    }
  }

  private static final class Burst {
    final int x
    final int y
    final int delayMs
    final int durationMs
    final int maxRadius
    final int spokes
    final Color color

    Burst(int x, int y, int delayMs, int durationMs, int maxRadius, int spokes, Color color) {
      this.x = x
      this.y = y
      this.delayMs = delayMs
      this.durationMs = durationMs
      this.maxRadius = maxRadius
      this.spokes = spokes
      this.color = color
    }
  }
}
