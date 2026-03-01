package se.alipsa.games.sc

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import se.alipsa.games.sc.ui.MainFrame

import javax.swing.SwingUtilities

class SweetCrush {

  private static final Logger log = LogManager.getLogger(SweetCrush)

  static void main(String[] args) {
    String banner = 'Sweet Crush starting up...'
    println banner
    log.info(banner)

    SwingUtilities.invokeLater {
      MainFrame frame = new MainFrame()
      frame.setVisible(true)
    }
  }
}
