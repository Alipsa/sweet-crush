package se.alipsa.games.sc.core

import spock.lang.Specification

class MatchFinderTest extends Specification {

  private final MatchFinder matchFinder = new MatchFinder()

  def 'detects a horizontal match run'() {
    given:
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.GREEN, CandyType.BLUE, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.ORANGE, CandyType.GREEN, CandyType.BLUE, CandyType.YELLOW]
    ])

    when:
    List<Set<Position>> groups = matchFinder.findMatchGroups(board)

    then:
    groups.size() == 1
    groups[0].size() == 3
    groups[0].containsAll([
        new Position(0, 0),
        new Position(1, 0),
        new Position(2, 0)
    ])
  }

  def 'merges overlapping horizontal and vertical runs into one group'() {
    given:
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.GREEN],
        [CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.YELLOW, CandyType.RED, CandyType.ORANGE]
    ])

    when:
    List<Set<Position>> groups = matchFinder.findMatchGroups(board)

    then:
    groups.size() == 1
    groups[0].size() == 5
  }

  def 'keeps disconnected runs as separate groups'() {
    given:
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.GREEN, CandyType.BLUE, CandyType.YELLOW, CandyType.PURPLE],
        [CandyType.ORANGE, CandyType.GREEN, CandyType.BLUE, CandyType.YELLOW],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.GREEN, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.GREEN],
        [CandyType.RED, CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW],
        [CandyType.PURPLE, CandyType.ORANGE, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.GREEN, CandyType.YELLOW, CandyType.RED],
        [CandyType.ORANGE, CandyType.PURPLE, CandyType.BLUE, CandyType.RED]
    ])

    when:
    List<Set<Position>> groups = matchFinder.findMatchGroups(board)

    then:
    groups.size() == 2
    groups*.size().sort() == [3, 3]
  }

  def 'detects 2x2 square as a match group'() {
    given:
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.RED, CandyType.RED, CandyType.GREEN],
        [CandyType.YELLOW, CandyType.ORANGE, CandyType.PURPLE]
    ])

    when:
    List<Set<Position>> groups = matchFinder.findMatchGroups(board)

    then:
    groups.size() == 1
    groups[0].size() == 4
    groups[0].containsAll([
        new Position(0, 0),
        new Position(1, 0),
        new Position(0, 1),
        new Position(1, 1)
    ])
  }

  def 'emits creation candidates for 4-line, 5-line and square patterns'() {
    given:
    Board board = boardOf([
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.BLUE, CandyType.BLUE, CandyType.BLUE, CandyType.GREEN],
        [CandyType.YELLOW, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.GREEN],
        [CandyType.YELLOW, CandyType.YELLOW, CandyType.PURPLE, CandyType.ORANGE, CandyType.GREEN]
    ])

    when:
    MatchFinder.MatchAnalysis analysis = matchFinder.analyze(board)
    List<SpecialPieceType> candidateTypes = analysis.creationCandidates*.specialType

    then:
    candidateTypes.contains(SpecialPieceType.BOMB)
    candidateTypes.contains(SpecialPieceType.SWEEPER)
    candidateTypes.contains(SpecialPieceType.FISH)
  }

  def 'emits small bomb creation candidate for T-shape 5-group'() {
    given:
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE],
        [CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE]
    ])

    when:
    MatchFinder.MatchAnalysis analysis = matchFinder.analyze(board)
    List<SpecialPieceType> candidateTypes = analysis.creationCandidates*.specialType

    then:
    candidateTypes.contains(SpecialPieceType.SMALL_BOMB)
  }

  def 'emits small bomb and suppresses fish for intersecting T-shape larger than 5 cells'() {
    given:
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.RED, CandyType.RED, CandyType.RED, CandyType.RED],
        [CandyType.BLUE, CandyType.RED, CandyType.RED, CandyType.BLUE],
        [CandyType.YELLOW, CandyType.GREEN, CandyType.ORANGE, CandyType.PURPLE]
    ])

    when:
    MatchFinder.MatchAnalysis analysis = matchFinder.analyze(board)
    List<SpecialPieceType> candidateTypes = analysis.creationCandidates*.specialType

    then:
    candidateTypes.contains(SpecialPieceType.SMALL_BOMB)
    !candidateTypes.contains(SpecialPieceType.FISH)
  }

  def 'does not classify 4-line plus side cell as small bomb'() {
    given:
    Board board = boardOf([
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE],
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE],
        [CandyType.BLUE, CandyType.RED, CandyType.BLUE],
        [CandyType.BLUE, CandyType.RED, CandyType.RED]
    ])

    when:
    MatchFinder.MatchAnalysis analysis = matchFinder.analyze(board)
    List<SpecialPieceType> candidateTypes = analysis.creationCandidates*.specialType

    then:
    !candidateTypes.contains(SpecialPieceType.SMALL_BOMB)
    candidateTypes.contains(SpecialPieceType.SWEEPER)
  }

  private static Board boardOf(List<List<CandyType>> rows) {
    int height = rows.size()
    int width = rows[0].size()
    Board board = new Board(width, height)

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        board.setCell(x, y, rows[y][x])
      }
    }

    board
  }
}
