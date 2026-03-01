package se.alipsa.games.sc.ui

import se.alipsa.games.sc.io.LoadError
import se.alipsa.games.sc.io.LoadErrorCode
import se.alipsa.games.sc.io.LoadResult
import se.alipsa.games.sc.model.Track
import se.alipsa.games.sc.core.CandyType
import spock.lang.Specification

class TrackSelectionDialogTest extends Specification {

  def 'displays tracks in deterministic input order'() {
    given:
    List<Track> tracks = [
        track('a-id', 'A'),
        track('b-id', 'a'),
        track('z-id', 'B')
    ]
    TrackSelectionDialog dialog = new TrackSelectionDialog(new LoadResult(tracks, []))

    expect:
    dialog.displayedTrackIds() == ['a-id', 'b-id', 'z-id']
  }

  def 'shows structured load errors with file code and message'() {
    given:
    List<LoadError> errors = [
        new LoadError('/tmp/a.json', LoadErrorCode.MALFORMED_JSON, 'Malformed JSON'),
        new LoadError('/tmp/b.json', LoadErrorCode.DUPLICATE_TRACK_ID, 'Duplicate id')
    ]
    TrackSelectionDialog dialog = new TrackSelectionDialog(new LoadResult([], errors))

    when:
    List<String> displayed = dialog.displayedErrors()

    then:
    displayed.size() == 2
    displayed[0].contains('/tmp/a.json')
    displayed[0].contains('MALFORMED_JSON')
    displayed[1].contains('DUPLICATE_TRACK_ID')
  }

  def 'shows empty-directory message when there are zero tracks'() {
    given:
    TrackSelectionDialog dialog = new TrackSelectionDialog(new LoadResult([], []))

    expect:
    dialog.infoMessage() == TrackSelectionDialog.EMPTY_MESSAGE
  }

  private static Track track(String id, String name) {
    new Track(id, name, 7, 9, 25, 3000,
        CandyType.values().collectEntries { CandyType type -> [(type): 1] } as Map<CandyType, Integer>)
  }
}
