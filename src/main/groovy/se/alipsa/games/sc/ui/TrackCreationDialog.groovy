package se.alipsa.games.sc.ui

import groovy.json.JsonOutput
import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.SpecialPieceType
import se.alipsa.games.sc.model.Track

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class TrackCreationDialog {

  private static final int MAX_ID_LENGTH = 64
  private static final Pattern NON_ALNUM = ~/[^a-z0-9]+/
  private static final Pattern TRIM_DASH = ~/(^-+)|(-+$)/

  private final Path directory
  private final Set<String> existingIds

  private final JTextField nameField = new JTextField('New Track', 20)
  private final JLabel idPreviewLabel = new JLabel('id: new-track')

  private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(7, 3, 20, 1))
  private final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(8, 3, 20, 1))
  private final JSpinner movesSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 200, 1))
  private final JSpinner targetScoreSpinner = new JSpinner(new SpinnerNumberModel(1500, 100, 50000, 50))

  private final Map<CandyType, JSpinner> spawnWeightSpinners = [:]
  private final Map<CandyType, JCheckBox> scoreColorChecks = [:]
  private final Map<SpecialPieceType, JSpinner> specialPieceSpinners = [:]

  TrackCreationDialog(Path directory, Set<String> existingIds = [] as Set<String>) {
    this.directory = directory
    this.existingIds = new LinkedHashSet<>(existingIds ?: [])
    initializeWidgets()
    updateIdPreview()
  }

  Optional<CreatedTrack> showAndCreate(Component parent) {
    while (true) {
      int result = JOptionPane.showConfirmDialog(
          parent,
          buildPanel(),
          'Create Track',
          JOptionPane.OK_CANCEL_OPTION,
          JOptionPane.PLAIN_MESSAGE
      )
      if (result != JOptionPane.OK_OPTION) {
        return Optional.empty()
      }

      String name = nameField.text?.trim()
      if (!name) {
        JOptionPane.showMessageDialog(parent, 'Name is required', 'Invalid Track', JOptionPane.WARNING_MESSAGE)
        continue
      }

      List<CandyType> selectedScoreColors = scoreColorChecks.findAll { it.value.selected }.keySet().toList().sort { it.name() }
      if (selectedScoreColors.isEmpty()) {
        JOptionPane.showMessageDialog(parent, 'Select at least one scoring color', 'Invalid Track', JOptionPane.WARNING_MESSAGE)
        continue
      }

      String id = uniqueIdForName(name, existingIds)
      Path targetFile = uniqueFileForId(directory, id)

      Map<String, Object> jsonTrack = [
          id          : id,
          name        : name,
          width       : spinnerValue(widthSpinner),
          height      : spinnerValue(heightSpinner),
          moves       : spinnerValue(movesSpinner),
          targetScore : spinnerValue(targetScoreSpinner),
          scoreColors : selectedScoreColors*.name(),
          spawnWeights: spawnWeightSpinners.collectEntries { CandyType type, JSpinner spinner ->
            [(type.name()): spinnerValue(spinner)]
          },
          specialPieces: specialPieceSpinners.collectEntries { SpecialPieceType type, JSpinner spinner ->
            [(type.name()): spinnerValue(spinner)]
          }
      ]

      try {
        Files.createDirectories(directory)
        Files.writeString(targetFile, JsonOutput.prettyPrint(JsonOutput.toJson(jsonTrack)))
        existingIds.add(id)
        return Optional.of(new CreatedTrack(id, targetFile))
      } catch (Exception e) {
        JOptionPane.showMessageDialog(parent,
            "Failed to write track file: ${e.message}",
            'Create Track Failed',
            JOptionPane.ERROR_MESSAGE)
      }
    }
  }

  Optional<CreatedTrack> showAndEdit(Component parent, Track track, Path file) {
    populateFromTrack(track)
    String editId = track.id

    while (true) {
      int result = JOptionPane.showConfirmDialog(
          parent,
          buildPanel(),
          'Edit Track',
          JOptionPane.OK_CANCEL_OPTION,
          JOptionPane.PLAIN_MESSAGE
      )
      if (result != JOptionPane.OK_OPTION) {
        return Optional.empty()
      }

      String name = nameField.text?.trim()
      if (!name) {
        JOptionPane.showMessageDialog(parent, 'Name is required', 'Invalid Track', JOptionPane.WARNING_MESSAGE)
        continue
      }

      List<CandyType> selectedScoreColors = scoreColorChecks.findAll { it.value.selected }.keySet().toList().sort { it.name() }
      if (selectedScoreColors.isEmpty()) {
        JOptionPane.showMessageDialog(parent, 'Select at least one scoring color', 'Invalid Track', JOptionPane.WARNING_MESSAGE)
        continue
      }

      Map<String, Object> jsonTrack = [
          id          : editId,
          name        : name,
          width       : spinnerValue(widthSpinner),
          height      : spinnerValue(heightSpinner),
          moves       : spinnerValue(movesSpinner),
          targetScore : spinnerValue(targetScoreSpinner),
          scoreColors : selectedScoreColors*.name(),
          spawnWeights: spawnWeightSpinners.collectEntries { CandyType type, JSpinner spinner ->
            [(type.name()): spinnerValue(spinner)]
          },
          specialPieces: specialPieceSpinners.collectEntries { SpecialPieceType type, JSpinner spinner ->
            [(type.name()): spinnerValue(spinner)]
          }
      ]

      try {
        Files.writeString(file, JsonOutput.prettyPrint(JsonOutput.toJson(jsonTrack)))
        return Optional.of(new CreatedTrack(editId, file))
      } catch (Exception e) {
        JOptionPane.showMessageDialog(parent,
            "Failed to write track file: ${e.message}",
            'Edit Track Failed',
            JOptionPane.ERROR_MESSAGE)
      }
    }
  }

  private void populateFromTrack(Track track) {
    nameField.text = track.name ?: ''
    idPreviewLabel.text = track.id ?: ''
    widthSpinner.value = track.width
    heightSpinner.value = track.height
    movesSpinner.value = track.moves
    targetScoreSpinner.value = track.targetScore

    CandyType.values().each { CandyType type ->
      int weight = track.spawnWeights?.getOrDefault(type, 1) ?: 1
      spawnWeightSpinners[type]?.value = Math.max(1, Math.min(5, weight))
      scoreColorChecks[type]?.selected = track.scoreColors?.contains(type)
    }

    SpecialPieceType.values().each { SpecialPieceType type ->
      int count = track.specialPieces?.getOrDefault(type, 0) ?: 0
      specialPieceSpinners[type]?.value = Math.max(0, count)
    }
  }

  static String uniqueIdForName(String name, Set<String> existingIds = [] as Set<String>) {
    Set<String> used = new LinkedHashSet<>(existingIds ?: [])
    String base = baseIdForName(name)
    if (!used.contains(base)) {
      return base
    }

    int counter = 2
    while (true) {
      String suffix = "-${counter}"
      int maxBaseLength = Math.max(1, MAX_ID_LENGTH - suffix.length())
      String truncatedBase = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base
      String candidate = "${truncatedBase}${suffix}"
      if (!used.contains(candidate)) {
        return candidate
      }
      counter++
    }
  }

  static String baseIdForName(String name) {
    String normalized = (name ?: '')
        .toLowerCase(Locale.ROOT)
        .replaceAll(NON_ALNUM, '-')
        .replaceAll(TRIM_DASH, '')

    if (!normalized) {
      normalized = 'track'
    }
    if (normalized.length() > MAX_ID_LENGTH) {
      normalized = normalized.substring(0, MAX_ID_LENGTH)
      normalized = normalized.replaceAll(TRIM_DASH, '')
      if (!normalized) {
        normalized = 'track'
      }
    }

    normalized
  }

  private static Path uniqueFileForId(Path directory, String id) {
    Path base = directory.resolve("${id}.json")
    if (!Files.exists(base)) {
      return base
    }

    int counter = 2
    while (true) {
      Path candidate = directory.resolve("${id}-${counter}.json")
      if (!Files.exists(candidate)) {
        return candidate
      }
      counter++
    }
  }

  private void initializeWidgets() {
    Map<CandyType, Integer> weightDefaults = [
        (CandyType.RED)   : 3,
        (CandyType.BLUE)  : 3,
        (CandyType.GREEN) : 3,
        (CandyType.YELLOW): 2,
        (CandyType.PURPLE): 2,
        (CandyType.ORANGE): 1
    ]

    CandyType.values().each { CandyType type ->
      int defaultWeight = weightDefaults.getOrDefault(type, 1)
      spawnWeightSpinners[type] = new JSpinner(new SpinnerNumberModel(defaultWeight, 1, 5, 1))
      scoreColorChecks[type] = new JCheckBox(type.name(), true)
    }

    SpecialPieceType.values().each { SpecialPieceType type ->
      specialPieceSpinners[type] = new JSpinner(new SpinnerNumberModel(0, 0, 50, 1))
    }

    nameField.document.addDocumentListener(new javax.swing.event.DocumentListener() {
      @Override
      void insertUpdate(javax.swing.event.DocumentEvent e) {
        updateIdPreview()
      }

      @Override
      void removeUpdate(javax.swing.event.DocumentEvent e) {
        updateIdPreview()
      }

      @Override
      void changedUpdate(javax.swing.event.DocumentEvent e) {
        updateIdPreview()
      }
    })
  }

  private JComponent buildPanel() {
    JPanel panel = new JPanel()
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
    panel.preferredSize = new Dimension(720, 520)

    panel.add(row('Name', nameField))
    panel.add(row('Id (auto)', idPreviewLabel))

    // Basic settings: two per row
    panel.add(doubleRow('Width', widthSpinner, 'Height', heightSpinner))
    panel.add(doubleRow('Moves', movesSpinner, 'Target score', targetScoreSpinner))

    // Candy colors: weight spinner + score checkbox on one row per type
    panel.add(sectionLabel('Candy Colors — Weight (1..5) / Scores'))
    CandyType.values().each { CandyType type ->
      panel.add(candyRow(type.name(), spawnWeightSpinners[type], scoreColorChecks[type]))
    }

    // Special pieces: two per row
    panel.add(sectionLabel('Special Pieces (default 0)'))
    List<SpecialPieceType> specials = SpecialPieceType.values().toList()
    for (int i = 0; i < specials.size(); i += 2) {
      if (i + 1 < specials.size()) {
        panel.add(doubleRow(specials[i].name(), specialPieceSpinners[specials[i]],
            specials[i + 1].name(), specialPieceSpinners[specials[i + 1]]))
      } else {
        panel.add(row(specials[i].name(), specialPieceSpinners[specials[i]]))
      }
    }

    panel
  }

  private static JPanel row(String label, JComponent component) {
    JPanel row = new JPanel(new BorderLayout(8, 0))
    row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    JLabel jLabel = new JLabel(label)
    jLabel.preferredSize = new Dimension(120, 24)
    row.add(jLabel, BorderLayout.WEST)
    row.add(component, BorderLayout.CENTER)
    row
  }

  private static JPanel doubleRow(String leftLabel, JComponent leftComponent,
                                   String rightLabel, JComponent rightComponent) {
    JPanel row = new JPanel(new GridLayout(1, 2, 16, 0))
    row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    row.add(labeledField(leftLabel, leftComponent))
    row.add(labeledField(rightLabel, rightComponent))
    row
  }

  private static JPanel labeledField(String label, JComponent component) {
    JPanel field = new JPanel(new BorderLayout(8, 0))
    JLabel jLabel = new JLabel(label)
    jLabel.preferredSize = new Dimension(100, 24)
    field.add(jLabel, BorderLayout.WEST)
    field.add(component, BorderLayout.CENTER)
    field
  }

  private static JPanel candyRow(String typeName, JSpinner weightSpinner, JCheckBox scoreCheck) {
    JPanel row = new JPanel(new BorderLayout(8, 0))
    row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    JLabel jLabel = new JLabel(typeName)
    jLabel.preferredSize = new Dimension(120, 24)
    row.add(jLabel, BorderLayout.WEST)

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0))
    controls.add(new JLabel('Weight:'))
    controls.add(weightSpinner)
    controls.add(scoreCheck)
    row.add(controls, BorderLayout.CENTER)
    row
  }

  private static JLabel sectionLabel(String text) {
    JLabel label = new JLabel(text)
    label.border = BorderFactory.createEmptyBorder(12, 0, 4, 0)
    label
  }

  private void updateIdPreview() {
    idPreviewLabel.text = uniqueIdForName(nameField.text?.trim(), existingIds)
  }

  private static int spinnerValue(JSpinner spinner) {
    (spinner.value as Number).intValue()
  }

  static final class CreatedTrack {
    final String id
    final Path file

    CreatedTrack(String id, Path file) {
      this.id = id
      this.file = file
    }
  }
}
