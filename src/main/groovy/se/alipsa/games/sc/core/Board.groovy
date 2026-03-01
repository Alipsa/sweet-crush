package se.alipsa.games.sc.core

class Board implements Cloneable {

  final int width
  final int height
  private final Piece[][] cells

  Board(int width, int height) {
    if (width < 1 || height < 1) {
      throw new IllegalArgumentException('Board dimensions must be positive')
    }
    this.width = width
    this.height = height
    this.cells = new Piece[height][width]
  }

  boolean inBounds(int x, int y) {
    x >= 0 && x < width && y >= 0 && y < height
  }

  CandyType getCell(int x, int y) {
    requireBounds(x, y)
    cells[y][x]?.color
  }

  void setCell(int x, int y, CandyType value) {
    requireBounds(x, y)
    cells[y][x] = value == null ? null : Piece.normal(value)
  }

  Piece getPiece(int x, int y) {
    requireBounds(x, y)
    cells[y][x]
  }

  void setPiece(int x, int y, Piece piece) {
    requireBounds(x, y)
    cells[y][x] = piece
  }

  void swap(int x1, int y1, int x2, int y2) {
    requireBounds(x1, y1)
    requireBounds(x2, y2)

    Piece tmp = cells[y1][x1]
    cells[y1][x1] = cells[y2][x2]
    cells[y2][x2] = tmp
  }

  void copyFrom(Board other) {
    if (other.width != width || other.height != height) {
      throw new IllegalArgumentException('Cannot copy board with different dimensions')
    }
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        cells[y][x] = other.cells[y][x]
      }
    }
  }

  List<CandyType> nonNullCandies() {
    List<CandyType> candies = []
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        Piece cell = cells[y][x]
        if (cell != null) {
          candies << cell.color
        }
      }
    }
    candies
  }

  @Override
  Board clone() {
    Board copy = new Board(width, height)
    copy.copyFrom(this)
    return copy
  }

  private void requireBounds(int x, int y) {
    if (!inBounds(x, y)) {
      throw new IndexOutOfBoundsException("Coordinate out of bounds: (${x}, ${y})")
    }
  }
}
