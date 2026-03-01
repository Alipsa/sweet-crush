package se.alipsa.games.sc.io

import groovy.transform.ToString
import se.alipsa.games.sc.model.Track

@ToString
class LoadResult {
  final List<Track> tracks
  final List<LoadError> errors

  LoadResult(List<Track> tracks = [], List<LoadError> errors = []) {
    this.tracks = List.copyOf(tracks)
    this.errors = List.copyOf(errors)
  }
}
