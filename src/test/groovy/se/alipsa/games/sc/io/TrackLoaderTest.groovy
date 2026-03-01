package se.alipsa.games.sc.io

import groovy.json.JsonOutput
import se.alipsa.games.sc.core.SpecialPieceType
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
