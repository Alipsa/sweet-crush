package se.alipsa.games.sc.io

import groovy.json.JsonSlurper
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import se.alipsa.games.sc.model.Track

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

class TrackLoader {

  private static final Logger log = LogManager.getLogger(TrackLoader)

  private final TrackValidator validator

  TrackLoader(TrackValidator validator = new TrackValidator()) {
    this.validator = validator
  }

  LoadResult loadTracks(Path directory) {
    List<LoadError> errors = []
    List<TrackCandidate> candidates = []

    if (directory == null || !Files.isDirectory(directory)) {
      errors << new LoadError(directory?.toString() ?: '<null>', LoadErrorCode.MISSING_REQUIRED_FIELD,
          'Selected path must be an existing directory')
      return new LoadResult([], errors)
    }

    List<Path> trackFiles
    try (Stream<Path> pathStream = Files.list(directory)) {
      trackFiles = pathStream
          .filter { Path path -> Files.isRegularFile(path) && path.fileName.toString().toLowerCase(Locale.ROOT).endsWith('.json') }
          .sorted(fileComparator())
          .toList()
    }

    if (trackFiles.isEmpty()) {
      errors << new LoadError(directory.toString(), LoadErrorCode.NO_TRACKS_FOUND,
          'No track JSON files were found in the selected directory')
      return new LoadResult([], errors)
    }

    trackFiles.each { Path file ->
      Object parsed
      try {
        parsed = new JsonSlurper().parse(file.toFile())
      } catch (Exception e) {
        errors << new LoadError(file.toString(), LoadErrorCode.MALFORMED_JSON, "Malformed JSON: ${e.message}")
        return
      }

      if (!(parsed instanceof Map)) {
        errors << new LoadError(file.toString(), LoadErrorCode.MALFORMED_JSON,
            'Track JSON must be a single JSON object')
        return
      }

      Map<String, ?> rawTrack = parsed as Map<String, ?>
      List<LoadError> validationErrors = validator.validate(file, rawTrack)
      if (!validationErrors.isEmpty()) {
        errors.addAll(validationErrors)
        return
      }

      candidates << new TrackCandidate(file, Track.fromMap(rawTrack))
    }

    candidates.sort(candidateComparator())

    List<Track> tracks = []
    Set<String> seenIds = new HashSet<>()

    candidates.each { TrackCandidate candidate ->
      if (seenIds.add(candidate.track.id)) {
        tracks << candidate.track
      } else {
        String message = "Duplicate track id '${candidate.track.id}' ignored; first definition wins"
        log.warn(message + " [{}]", candidate.file)
        errors << new LoadError(candidate.file.toString(), LoadErrorCode.DUPLICATE_TRACK_ID, message)
      }
    }

    return new LoadResult(tracks, errors)
  }

  private static Comparator<Path> fileComparator() {
    Comparator.comparing({ Path path -> path.fileName.toString().toLowerCase(Locale.ROOT) })
        .thenComparing({ Path path -> path.fileName.toString() })
        .thenComparing({ Path path -> normalizePath(path) })
  }

  private static Comparator<TrackCandidate> candidateComparator() {
    Comparator.comparing({ TrackCandidate candidate -> candidate.file.fileName.toString().toLowerCase(Locale.ROOT) })
        .thenComparing({ TrackCandidate candidate -> candidate.file.fileName.toString() })
        .thenComparing({ TrackCandidate candidate -> candidate.track.id })
        .thenComparing({ TrackCandidate candidate -> normalizePath(candidate.file) })
  }

  private static String normalizePath(Path file) {
    return file.toAbsolutePath().normalize().toString()
  }

  private static final class TrackCandidate {
    final Path file
    final Track track

    TrackCandidate(Path file, Track track) {
      this.file = file
      this.track = track
    }
  }
}
