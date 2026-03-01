package se.alipsa.games.sc.ui

import se.alipsa.games.sc.io.LoadError
import se.alipsa.games.sc.io.LoadResult
import se.alipsa.games.sc.model.Track

import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import java.awt.Component
import java.awt.GraphicsEnvironment

class TrackSelectionDialog {

  static final String EMPTY_MESSAGE = 'No tracks could be loaded from the selected directory. Please choose a directory containing valid track JSON files.'

  private final List<Track> tracks
  private final List<LoadError> errors

  TrackSelectionDialog(LoadResult loadResult) {
    this.tracks = List.copyOf(loadResult?.tracks ?: [])
    this.errors = List.copyOf(loadResult?.errors ?: [])
  }

  List<String> displayedTrackIds() {
    tracks.collect { it.id }
  }

  List<String> displayedErrors() {
    errors.collect { "${it.file} | ${it.code} | ${it.message}" }
  }

  String infoMessage() {
    tracks.isEmpty() ? EMPTY_MESSAGE : ''
  }

  Optional<Track> showAndSelect(Component parent = null) {
    if (tracks.isEmpty()) {
      if (!GraphicsEnvironment.isHeadless()) {
        JOptionPane.showMessageDialog(parent, EMPTY_MESSAGE, 'No Tracks', JOptionPane.INFORMATION_MESSAGE)
      }
      return Optional.empty()
    }

    if (GraphicsEnvironment.isHeadless()) {
      return Optional.of(tracks.first())
    }

    DefaultListModel<String> listModel = new DefaultListModel<>()
    tracks.each { Track track ->
      listModel.addElement("${track.name} (${track.id})")
    }

    JList<String> list = new JList<>(listModel)
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.selectedIndex = 0

    JTextArea errorArea = new JTextArea(displayedErrors().join(System.lineSeparator()))
    errorArea.editable = false

    Object[] content = [new JScrollPane(list)]
    if (!errors.isEmpty()) {
      content = [
          new JScrollPane(list),
          new JScrollPane(errorArea)
      ]
    }

    int choice = JOptionPane.showConfirmDialog(
        parent,
        content,
        'Select Track',
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE
    )

    if (choice != JOptionPane.OK_OPTION) {
      return Optional.empty()
    }

    int selected = list.selectedIndex
    if (selected < 0 || selected >= tracks.size()) {
      return Optional.empty()
    }
    return Optional.of(tracks[selected])
  }
}
