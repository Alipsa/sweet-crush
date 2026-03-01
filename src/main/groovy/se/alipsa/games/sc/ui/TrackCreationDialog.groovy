package se.alipsa.games.sc.ui

import groovy.json.JsonOutput
import se.alipsa.games.sc.core.CandyType
import se.alipsa.games.sc.core.SpecialPieceType

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
import java.awt.Component
import java.awt.Dimension
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

  private JPanel buildPanel() {
    JPanel panel = new JPanel()
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
    panel.preferredSize = new Dimension(540, 640)

    panel.add(row('Name', nameField))
    panel.add(row('Id (auto)', idPreviewLabel))

    panel.add(row('Width', widthSpinner))
    panel.add(row('Height', heightSpinner))
    panel.add(row('Moves', movesSpinner))
    panel.add(row('Target score', targetScoreSpinner))

    panel.add(sectionLabel('Spawn Weights (1..5)'))
    CandyType.values().each { CandyType type ->
      panel.add(row(type.name(), spawnWeightSpinners[type]))
    }

    panel.add(sectionLabel('Score Colors'))
    CandyType.values().each { CandyType type ->
      panel.add(row(type.name(), scoreColorChecks[type]))
    }

    panel.add(sectionLabel('Special Pieces (default 0)'))
    SpecialPieceType.values().each { SpecialPieceType type ->
      panel.add(row(type.name(), specialPieceSpinners[type]))
    }

    panel
  }

  private static JPanel row(String label, JComponent component) {
    JPanel row = new JPanel(new java.awt.BorderLayout(8, 0))
    row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    JLabel jLabel = new JLabel(label)
    jLabel.preferredSize = new Dimension(180, 24)
    row.add(jLabel, java.awt.BorderLayout.WEST)
    row.add(component, java.awt.BorderLayout.CENTER)
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
