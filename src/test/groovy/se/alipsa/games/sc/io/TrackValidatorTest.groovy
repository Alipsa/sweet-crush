package se.alipsa.games.sc.io

import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.Path

class TrackValidatorTest extends Specification {

  private final TrackValidator validator = new TrackValidator()
  private static final Path TEST_FILE = Path.of('test-track.json')

  @Unroll
  def "rejects invalid track id '#badId'"() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.id = badId

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_TRACK_ID }

    where:
    badId << [null, '', '   ', '#bad', 'bad id', "${'a' * 65}"]
  }

  def 'rejects missing required fields'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.remove('name')

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.MISSING_REQUIRED_FIELD && it.message.contains('name') }
  }

  def 'rejects bad dimensions below and above allowed range'() {
    given:
    Map<String, Object> tooSmall = validTrack()
    tooSmall.width = 2
    Map<String, Object> tooLarge = validTrack()
    tooLarge.height = 21

    expect:
    validator.validate(TEST_FILE, tooSmall).any { it.code == LoadErrorCode.INVALID_DIMENSIONS }
    validator.validate(TEST_FILE, tooLarge).any { it.code == LoadErrorCode.INVALID_DIMENSIONS }
  }

  def 'rejects non-positive gameplay solvability settings'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.moves = 0
    rawTrack.targetScore = -5

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.count { it.code == LoadErrorCode.MISSING_REQUIRED_FIELD } >= 2
  }

  def 'rejects unknown candy type in spawn weights'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.spawnWeights = [
        RED: 3,
        BLUE: 3,
        GREEN: 3,
        GHOST: 1
    ]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.UNKNOWN_CANDY_TYPE }
  }

  def 'rejects negative spawn weights'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.spawnWeights = [RED: 3, BLUE: -1, GREEN: 3]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_SPAWN_WEIGHTS }
  }

  def 'rejects zero total spawn weights'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.spawnWeights = [RED: 0, BLUE: 0, GREEN: 0]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_SPAWN_WEIGHTS && it.message.contains('total positive weight') }
  }

  def 'rejects fewer than three positive spawn-weight candy types'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.spawnWeights = [RED: 1, BLUE: 1, GREEN: 0]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_SPAWN_WEIGHTS && it.message.contains('at least 3 candy types') }
  }

  def 'accepts omitted spawn weights'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.remove('spawnWeights')

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'accepts partial spawn weights where omitted keys are treated as zero'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.spawnWeights = [RED: 4, BLUE: 2, GREEN: 1]

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'accepts scoreColors set to ALL'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.scoreColors = 'ALL'

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'accepts scoreColors as explicit candy-type list'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.scoreColors = ['RED', 'BLUE']

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'rejects invalid scoreColors configuration'() {
    given:
    Map<String, Object> invalidType = validTrack()
    invalidType.scoreColors = 42
    Map<String, Object> emptyList = validTrack()
    emptyList.scoreColors = []
    Map<String, Object> unknownColor = validTrack()
    unknownColor.scoreColors = ['RED', 'GHOST']

    expect:
    validator.validate(TEST_FILE, invalidType).any { it.code == LoadErrorCode.INVALID_SCORING_RULES }
    validator.validate(TEST_FILE, emptyList).any { it.code == LoadErrorCode.INVALID_SCORING_RULES }
    validator.validate(TEST_FILE, unknownColor).any { it.code == LoadErrorCode.INVALID_SCORING_RULES }
  }

  def 'accepts valid specialPieces configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.specialPieces = [
        SWEEPER  : 3,
        SMALL_BOMB: 2,
        BOMB     : 2,
        FISH     : 1
    ]

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'rejects invalid specialPieces configuration'() {
    given:
    Map<String, Object> unknownType = validTrack()
    unknownType.specialPieces = [ROCKET: 2]

    Map<String, Object> negativeCount = validTrack()
    negativeCount.specialPieces = [SWEEPER: -1]

    Map<String, Object> wrongType = validTrack()
    wrongType.specialPieces = 42

    expect:
    validator.validate(TEST_FILE, unknownType).any { it.code == LoadErrorCode.INVALID_SPECIAL_PIECES }
    validator.validate(TEST_FILE, negativeCount).any { it.code == LoadErrorCode.INVALID_SPECIAL_PIECES }
    validator.validate(TEST_FILE, wrongType).any { it.code == LoadErrorCode.INVALID_SPECIAL_PIECES }
  }

  def 'accepts valid blockers and objectives configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.blockers = [
        [x: 1, y: 1, type: 'JELLY', layers: 2],
        [x: 3, y: 2, type: 'CRATE', layers: 1]
    ]
    rawTrack.objectives = [
        [type: 'SCORE', target: 1200],
        [type: 'CLEAR_BLOCKER', target: 2],
        [type: 'COLLECT_COLOR', target: 6, color: 'RED']
    ]

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'accepts valid board mask configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.board = [
        mask: [
            '...#...',
            '..###..',
            '.......',
            '...#...',
            '...#...',
            '..###..',
            '...#...',
            '...#...',
            '.......'
        ]
    ]

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'rejects invalid board mask configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.board = [
        mask: [
            '...#..X',
            '....',
            '....',
            '....',
            '....',
            '....',
            '....',
            '....',
            '....'
        ]
    ]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_DIMENSIONS && it.message.contains('board.mask') }
  }

  def 'accepts valid one-way and teleporter geometry configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.board = [
        oneWay    : [
            [x: 1, y: 1, direction: 'LEFT'],
            [x: 4, y: 4, direction: 'DOWN']
        ],
        teleporters: [
            [from: [x: 0, y: 0], to: [x: 6, y: 8]],
            [from: [x: 6, y: 0], to: [x: 0, y: 8]]
        ]
    ]

    expect:
    validator.validate(TEST_FILE, rawTrack).isEmpty()
  }

  def 'rejects invalid one-way and teleporter geometry configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.board = [
        oneWay    : [
            [x: 1, y: 1, direction: 'SIDEWAYS']
        ],
        teleporters: [
            [from: [x: 0, y: 0], to: [x: 6, y: 8]],
            [from: [x: 0, y: 0], to: [x: 5, y: 8]]
        ]
    ]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_BOARD_GEOMETRY && it.message.contains('Unknown board.oneWay direction') }
    errors.any { it.code == LoadErrorCode.INVALID_BOARD_GEOMETRY && it.message.contains('Duplicate board.teleporters source') }
  }

  def 'rejects invalid blockers configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.blockers = [
        [x: 1, y: 1, type: 'UNKNOWN', layers: 2],
        [x: 1, y: 1, type: 'JELLY', layers: 4],
        [x: 999, y: 0, type: 'ICE', layers: 1]
    ]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_BLOCKERS }
  }

  def 'rejects blocker placement on hole mask cells'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.board = [
        mask: [
            '.......',
            '...#...',
            '.......',
            '.......',
            '.......',
            '.......',
            '.......',
            '.......',
            '.......'
        ]
    ]
    rawTrack.blockers = [
        [x: 3, y: 1, type: 'JELLY', layers: 1]
    ]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_BLOCKERS && it.message.contains('hole cell') }
  }

  def 'rejects invalid objectives configuration'() {
    given:
    Map<String, Object> rawTrack = validTrack()
    rawTrack.objectives = [
        [type: 'UNKNOWN', target: 5],
        [type: 'COLLECT_COLOR', target: 4],
        [type: 'CLEAR_BLOCKER', target: 3, blockerType: 'MISSING']
    ]

    when:
    List<LoadError> errors = validator.validate(TEST_FILE, rawTrack)

    then:
    errors.any { it.code == LoadErrorCode.INVALID_OBJECTIVES }
  }

  private static Map<String, Object> validTrack() {
    [
        id          : 'classic-01',
        name        : 'Classic Level 1',
        width       : 7,
        height      : 9,
        moves       : 25,
        targetScore : 3000,
        spawnWeights: [
            RED   : 3,
            BLUE  : 3,
            GREEN : 3,
            YELLOW: 2,
            PURPLE: 2,
            ORANGE: 1
        ]
    ] as Map<String, Object>
  }
}
