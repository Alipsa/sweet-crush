package se.alipsa.games.sc.io

import groovy.json.JsonOutput
import se.alipsa.games.sc.core.SpecialPieceType
import se.alipsa.games.sc.core.BlockerType
import se.alipsa.games.sc.core.FlowDirection
import se.alipsa.games.sc.core.Position
import se.alipsa.games.sc.model.ObjectiveType
import se.alipsa.games.sc.model.Track
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class TrackLoaderTest extends Specification {

  @TempDir
  Path tempDir

  def 'returns an error for invalid folder path'() {
    given:
    TrackLoader loader = new TrackLoader()
    Path missingDir = tempDir.resolve('missing')

    when:
    LoadResult result = loader.loadTracks(missingDir)

    then:
    result.tracks.isEmpty()
    result.errors*.code.contains(LoadErrorCode.MISSING_REQUIRED_FIELD)
  }

  def 'returns NO_TRACKS_FOUND when directory has no json files'() {
    given:
    TrackLoader loader = new TrackLoader()

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.tracks.isEmpty()
    result.errors.size() == 1
    result.errors[0].code == LoadErrorCode.NO_TRACKS_FOUND
  }

  def 'captures malformed json parse errors'() {
    given:
    TrackLoader loader = new TrackLoader()
    Files.writeString(tempDir.resolve('bad.json'), '{not-json')

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.tracks.isEmpty()
    result.errors.any { it.code == LoadErrorCode.MALFORMED_JSON && it.file.endsWith('bad.json') }
  }

  def 'keeps first duplicate id in deterministic order and reports duplicate error'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('A.json'), [id: 'dup-id', name: 'Upper A'])
    writeTrack(tempDir.resolve('a.json'), [id: 'dup-id', name: 'Lower a'])
    writeTrack(tempDir.resolve('b.json'), [id: 'unique-id', name: 'Unique'])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.tracks*.id == ['dup-id', 'unique-id']
    result.tracks[0].name == 'Upper A'
    result.errors.any { it.code == LoadErrorCode.DUPLICATE_TRACK_ID && it.file.endsWith('a.json') }
  }

  def 'orders tracks deterministically by filename lowercased then exact filename then id'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('B.json'), [id: 'z-id', name: 'B'])
    writeTrack(tempDir.resolve('a.json'), [id: 'b-id', name: 'a'])
    writeTrack(tempDir.resolve('A.json'), [id: 'a-id', name: 'A'])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    result.tracks*.id == ['a-id', 'b-id', 'z-id']
  }

  def 'returns structured per-file validation and parse errors'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('invalid.json'), [id: 'valid-id', name: ' ', width: 7, height: 9, moves: 25, targetScore: 3000])
    Files.writeString(tempDir.resolve('broken.json'), '{broken')

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.tracks.isEmpty()
    result.errors.size() >= 2
    result.errors.every { it.file }
    result.errors.every { it.message }
    result.errors*.code.contains(LoadErrorCode.MALFORMED_JSON)
    result.errors*.code.contains(LoadErrorCode.MISSING_REQUIRED_FIELD)
  }

  def 'normalizes partial spawn weights to zero for omitted candy types'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('partial.json'), [
        id          : 'partial-weights',
        name        : 'Partial',
        spawnWeights: [RED: 4, BLUE: 3, GREEN: 1]
    ])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    Track track = result.tracks[0] as Track
    track.spawnWeights.values().count { it > 0 } == 3
    track.spawnWeights.values().sum() == 8
    track.spawnWeights.find { it.key.name() == 'YELLOW' }.value == 0
  }

  def 'loads scoreColors rule and applies default all-colors scoring when omitted'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('red-only.json'), [
        id         : 'red-only',
        name       : 'Red only scores',
        scoreColors: ['RED']
    ])
    writeTrack(tempDir.resolve('all-colors.json'), [
        id  : 'all-colors',
        name: 'All colors score'
    ])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    Track redOnly = result.tracks.find { it.id == 'red-only' } as Track
    Track allColors = result.tracks.find { it.id == 'all-colors' } as Track
    redOnly.scoreColors*.name() == ['RED']
    allColors.scoresAllColors()
  }

  def 'loads special piece budgets and defaults omitted special keys to zero'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('specials.json'), [
        id           : 'specials',
        name         : 'Specials',
        specialPieces: [SWEEPER: 4, SMALL_BOMB: 3, BOMB: 1]
    ])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    Track track = result.tracks.find { it.id == 'specials' } as Track
    track.specialPieceBudget(SpecialPieceType.SWEEPER) == 4
    track.specialPieceBudget(SpecialPieceType.SMALL_BOMB) == 3
    track.specialPieceBudget(SpecialPieceType.BOMB) == 1
    track.specialPieceBudget(SpecialPieceType.FISH) == 0
  }

  def 'loads blockers and objectives for M8a tracks'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('advanced.json'), [
        id        : 'advanced-01',
        name      : 'Advanced',
        blockers  : [
            [x: 1, y: 1, type: 'JELLY', layers: 2],
            [x: 2, y: 3, type: 'ICE', layers: 1]
        ],
        objectives: [
            [type: 'SCORE', target: 1400],
            [type: 'CLEAR_BLOCKER', target: 2],
            [type: 'COLLECT_COLOR', target: 5, color: 'RED']
        ]
    ])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    Track track = result.tracks.find { it.id == 'advanced-01' } as Track
    track != null
    track.blockers[new Position(1, 1)]?.type == BlockerType.JELLY
    track.blockers[new Position(1, 1)]?.layers == 2
    track.objectives*.type == [ObjectiveType.SCORE, ObjectiveType.CLEAR_BLOCKER, ObjectiveType.COLLECT_COLOR]
  }

  def 'loads board mask and preserves playable-hole layout'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('mask.json'), [
        id   : 'mask-01',
        name : 'Mask',
        board: [
            mask: [
                '...#...',
                '..###..',
                '.......',
                '...#...',
                '...#...',
                '..###..',
                '.......',
                '.......',
                '...#...'
            ]
        ]
    ])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    Track track = result.tracks.find { it.id == 'mask-01' } as Track
    track != null
    track.hasMask()
    !track.isPlayable(3, 0)
    track.isPlayable(0, 0)
  }

  def 'loads one-way tiles and teleporters for M8c geometry tracks'() {
    given:
    TrackLoader loader = new TrackLoader()
    writeTrack(tempDir.resolve('geometry.json'), [
        id   : 'geometry-01',
        name : 'Geometry',
        board: [
            oneWay    : [
                [x: 1, y: 1, direction: 'LEFT'],
                [x: 5, y: 2, direction: 'DOWN']
            ],
            teleporters: [
                [from: [x: 0, y: 0], to: [x: 6, y: 8]],
                [from: [x: 6, y: 0], to: [x: 0, y: 8]]
            ]
        ]
    ])

    when:
    LoadResult result = loader.loadTracks(tempDir)

    then:
    result.errors.isEmpty()
    Track track = result.tracks.find { it.id == 'geometry-01' } as Track
    track != null
    track.hasOneWayTiles()
    track.hasTeleporters()
    track.oneWayTiles[new Position(1, 1)] == FlowDirection.LEFT
    track.teleporters[new Position(0, 0)] == new Position(6, 8)
  }

  private void writeTrack(Path path, Map overrides = [:]) {
    Map<String, Object> base = [
        id         : 'classic-01',
        name       : 'Classic Level 1',
        width      : 7,
        height     : 9,
        moves      : 25,
        targetScore: 3000,
        spawnWeights: [
            RED   : 3,
            BLUE  : 3,
            GREEN : 3,
            YELLOW: 2,
            PURPLE: 2,
            ORANGE: 1
        ]
    ] as Map<String, Object>

    base.putAll(overrides)
    Files.writeString(path, JsonOutput.prettyPrint(JsonOutput.toJson(base)))
  }
}
