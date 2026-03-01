package se.alipsa.games.sc.core

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class MatchFinder {

  private static final Logger log = LogManager.getLogger(MatchFinder)

  MatchAnalysis analyze(Board board) {
    List<LineRun> horizontalRuns = findHorizontalRuns(board)
    List<LineRun> verticalRuns = findVerticalRuns(board)
    List<Set<Position>> squareMatches = findSquareMatches(board)

    List<Set<Position>> mergeCandidates = []
    mergeCandidates.addAll(horizontalRuns.collect { LineRun run -> new LinkedHashSet<>(run.positions) })
    mergeCandidates.addAll(verticalRuns.collect { LineRun run -> new LinkedHashSet<>(run.positions) })
    mergeCandidates.addAll(squareMatches)

    List<Set<Position>> groups = mergeOverlappingRuns(mergeCandidates)
    List<SpecialCreationCandidate> creationCandidates = collectCreationCandidates(horizontalRuns, verticalRuns, squareMatches, groups, board)

    new MatchAnalysis(groups, creationCandidates)
  }

  List<Set<Position>> findMatchGroups(Board board) {
    analyze(board).groups
  }

  boolean hasAnyMatch(Board board) {
    !analyze(board).groups.isEmpty()
  }

  private static List<LineRun> findHorizontalRuns(Board board) {
    List<LineRun> runs = []
    for (int y = 0; y < board.height; y++) {
      int x = 0
      while (x < board.width) {
        CandyType value = board.getCell(x, y)
        if (value == null) {
          x++
          continue
        }

        int end = x + 1
        while (end < board.width && board.getCell(end, y) == value) {
          end++
        }

        if (end - x >= 3) {
          Set<Position> run = new LinkedHashSet<>()
          for (int i = x; i < end; i++) {
            run << new Position(i, y)
          }
          runs << new LineRun(value, true, List.copyOf(run))
        }

        x = end
      }
    }
    runs
  }

  private static List<LineRun> findVerticalRuns(Board board) {
    List<LineRun> runs = []
    for (int x = 0; x < board.width; x++) {
      int y = 0
      while (y < board.height) {
        CandyType value = board.getCell(x, y)
        if (value == null) {
          y++
          continue
        }

        int end = y + 1
        while (end < board.height && board.getCell(x, end) == value) {
          end++
        }

        if (end - y >= 3) {
          Set<Position> run = new LinkedHashSet<>()
          for (int i = y; i < end; i++) {
            run << new Position(x, i)
          }
          runs << new LineRun(value, false, List.copyOf(run))
        }

        y = end
      }
    }
    runs
  }

  private static List<Set<Position>> findSquareMatches(Board board) {
    Set<Set<Position>> matches = new LinkedHashSet<>()
    for (int y = 0; y < board.height - 1; y++) {
      for (int x = 0; x < board.width - 1; x++) {
        CandyType topLeft = board.getCell(x, y)
        if (topLeft == null) {
          continue
        }
        if (board.getCell(x + 1, y) == topLeft &&
            board.getCell(x, y + 1) == topLeft &&
            board.getCell(x + 1, y + 1) == topLeft) {
          Set<Position> square = new LinkedHashSet<>()
          square << new Position(x, y)
          square << new Position(x + 1, y)
          square << new Position(x, y + 1)
          square << new Position(x + 1, y + 1)
          matches << Collections.unmodifiableSet(square)
        }
      }
    }
    matches as List<Set<Position>>
  }

  private static List<SpecialCreationCandidate> collectCreationCandidates(List<LineRun> horizontalRuns,
                                                                          List<LineRun> verticalRuns,
                                                                          List<Set<Position>> squareMatches,
                                                                          List<Set<Position>> groups,
                                                                          Board board) {
    List<SpecialCreationCandidate> candidates = []
    List<Set<Position>> smallBombEligibleGroups = groups.findAll { Set<Position> group ->
      qualifiesForSmallBomb(group)
    }

    (horizontalRuns + verticalRuns).each { LineRun run ->
      if (run.positions.size() >= 5) {
        Position anchor = run.positions[(run.positions.size() - 1).intdiv(2)]
        log.debug('Detected color-bomb candidate from {} run (len={}, color={}, anchor={})',
            run.horizontal ? 'horizontal' : 'vertical',
            run.positions.size(),
            run.color,
            anchor)
        candidates << new SpecialCreationCandidate(SpecialPieceType.BOMB, anchor, run.color, run.horizontal, 30)
      } else if (run.positions.size() == 4) {
        Position anchor = run.positions[(run.positions.size() - 1).intdiv(2)]
        log.debug('Detected sweeper candidate from {} run (len=4, color={}, anchor={}, orientation={})',
            run.horizontal ? 'horizontal' : 'vertical',
            run.color,
            anchor,
            run.horizontal ? 'horizontal' : 'vertical')
        candidates << new SpecialCreationCandidate(SpecialPieceType.SWEEPER, anchor, run.color, run.horizontal, 10)
      }
    }

    squareMatches.each { Set<Position> square ->
      if (smallBombEligibleGroups.any { Set<Position> group -> group.containsAll(square) }) {
        log.debug('Skipped fish candidate for square {} because it overlaps a small-bomb eligible intersecting group', square)
        return
      }
      List<Position> ordered = square.toList().sort { Position a, Position b ->
        a.y == b.y ? Integer.compare(a.x, b.x) : Integer.compare(a.y, b.y)
      }
      if (!ordered.isEmpty()) {
        Position anchor = ordered.first()
        log.debug('Detected fish candidate from 2x2 square (anchor={})', anchor)
        candidates << new SpecialCreationCandidate(SpecialPieceType.FISH, anchor, null, true, 20)
      }
    }

    candidates.addAll(collectSmallBombCandidates(groups, board))

    return candidates
  }

  private static List<SpecialCreationCandidate> collectSmallBombCandidates(List<Set<Position>> groups, Board board) {
    List<SpecialCreationCandidate> candidates = []

    groups.each { Set<Position> group ->
      if (!qualifiesForSmallBomb(group)) {
        return
      }

      Position anchor = findSmallBombAnchor(group)
      if (anchor == null) {
        return
      }

      CandyType color = board.getCell(anchor.x, anchor.y)
      if (color == null) {
        return
      }

      log.debug('Detected small-bomb candidate from intersecting group (size={}, anchor={}, color={})',
          group.size(),
          anchor,
          color)
      candidates << new SpecialCreationCandidate(SpecialPieceType.SMALL_BOMB, anchor, color, true, 25)
    }

    candidates
  }

  private static boolean qualifiesForSmallBomb(Set<Position> group) {
    if (group == null || group.size() < 5 || isStraightLine(group)) {
      return false
    }
    if (!hasPerpendicularTripleRuns(group)) {
      return false
    }
    return findSmallBombAnchor(group) != null
  }

  private static boolean hasPerpendicularTripleRuns(Set<Position> group) {
    boolean hasHorizontal = false
    boolean hasVertical = false
    group.each { Position pos ->
      int horizontal = 1 + contiguousCount(group, pos, -1, 0) + contiguousCount(group, pos, 1, 0)
      int vertical = 1 + contiguousCount(group, pos, 0, -1) + contiguousCount(group, pos, 0, 1)
      if (horizontal >= 3) {
        hasHorizontal = true
      }
      if (vertical >= 3) {
        hasVertical = true
      }
    }
    hasHorizontal && hasVertical
  }

  private static int contiguousCount(Set<Position> group, Position origin, int dx, int dy) {
    int count = 0
    int x = origin.x + dx
    int y = origin.y + dy
    while (group.contains(new Position(x, y))) {
      count++
      x += dx
      y += dy
    }
    count
  }

  private static boolean isStraightLine(Set<Position> group) {
    Set<Integer> xs = group.collect { Position p -> p.x } as Set<Integer>
    Set<Integer> ys = group.collect { Position p -> p.y } as Set<Integer>
    xs.size() == 1 || ys.size() == 1
  }

  private static Position findSmallBombAnchor(Set<Position> group) {
    Position highDegree = group.find { Position pos ->
      neighborsInGroup(pos, group).size() >= 3
    }
    if (highDegree != null) {
      return highDegree
    }

    group.find { Position pos ->
      Set<Position> neighbors = neighborsInGroup(pos, group)
      if (neighbors.size() != 2) {
        return false
      }

      boolean hasHorizontal = neighbors.any { Position n -> n.y == pos.y }
      boolean hasVertical = neighbors.any { Position n -> n.x == pos.x }
      hasHorizontal && hasVertical
    }
  }

  private static Set<Position> neighborsInGroup(Position pos, Set<Position> group) {
    Set<Position> neighbors = [] as Set<Position>
    Position left = new Position(pos.x - 1, pos.y)
    Position right = new Position(pos.x + 1, pos.y)
    Position up = new Position(pos.x, pos.y - 1)
    Position down = new Position(pos.x, pos.y + 1)
    if (group.contains(left)) {
      neighbors << left
    }
    if (group.contains(right)) {
      neighbors << right
    }
    if (group.contains(up)) {
      neighbors << up
    }
    if (group.contains(down)) {
      neighbors << down
    }
    neighbors
  }

  private static List<Set<Position>> mergeOverlappingRuns(List<Set<Position>> runs) {
    List<Set<Position>> groups = []

    runs.each { Set<Position> run ->
      Set<Position> merged = new LinkedHashSet<>(run)
      boolean changed = true
      while (changed) {
        changed = false
        for (int i = groups.size() - 1; i >= 0; i--) {
          Set<Position> existing = groups[i]
          if (!Collections.disjoint(existing, merged)) {
            merged.addAll(existing)
            groups.remove(i)
            changed = true
          }
        }
      }
      groups << merged
    }

    groups
  }

  static final class MatchAnalysis {
    final List<Set<Position>> groups
    final List<SpecialCreationCandidate> creationCandidates

    MatchAnalysis(List<Set<Position>> groups = [], List<SpecialCreationCandidate> creationCandidates = []) {
      this.groups = List.copyOf(groups)
      this.creationCandidates = List.copyOf(creationCandidates)
    }
  }

  static final class SpecialCreationCandidate {
    final SpecialPieceType specialType
    final Position anchor
    final CandyType color
    final boolean sweeperHorizontal
    final int priority

    SpecialCreationCandidate(SpecialPieceType specialType,
                             Position anchor,
                             CandyType color,
                             boolean sweeperHorizontal,
                             int priority) {
      this.specialType = specialType
      this.anchor = anchor
      this.color = color
      this.sweeperHorizontal = sweeperHorizontal
      this.priority = priority
    }
  }

  private static final class LineRun {
    final CandyType color
    final boolean horizontal
    final List<Position> positions

    LineRun(CandyType color, boolean horizontal, List<Position> positions) {
      this.color = color
      this.horizontal = horizontal
      this.positions = List.copyOf(positions)
    }
  }
}
