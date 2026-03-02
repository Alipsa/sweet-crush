package se.alipsa.games.sc.core

class Board implements Cloneable {

  final int width
  final int height
  private final Piece[][] cells
  private final boolean[][] playable
  private final Blocker[][] blockers
  private final Ingredient[][] ingredients
  private final Map<Position, FlowDirection> oneWayTiles
  private final Map<Position, Position> teleporters

  Board(int width, int height) {
    this(width, height, null, null, null)
  }

  Board(int width, int height, boolean[][] playableMask) {
    this(width, height, playableMask, null, null)
  }

  Board(int width,
        int height,
        boolean[][] playableMask,
        Map<Position, FlowDirection> oneWayTiles,
        Map<Position, Position> teleporters) {
    if (width < 1 || height < 1) {
      throw new IllegalArgumentException('Board dimensions must be positive')
    }
    this.width = width
    this.height = height
    this.cells = new Piece[height][width]
    this.playable = normalizePlayableMask(width, height, playableMask)
    this.blockers = new Blocker[height][width]
    this.ingredients = new Ingredient[height][width]
    this.oneWayTiles = Collections.unmodifiableMap(normalizeOneWayTiles(width, height, this.playable, oneWayTiles))
    this.teleporters = Collections.unmodifiableMap(normalizeTeleporters(width, height, this.playable, teleporters))
  }

  boolean inBounds(int x, int y) {
    x >= 0 && x < width && y >= 0 && y < height
  }

  CandyType getCell(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    cells[y][x]?.color
  }

  void setCell(int x, int y, CandyType value) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return
    }
    setPiece(x, y, value == null ? null : Piece.normal(value))
  }

  Piece getPiece(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    cells[y][x]
  }

  void setPiece(int x, int y, Piece piece) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return
    }
    if (piece != null && ingredients[y][x] != null) {
      return
    }
    cells[y][x] = piece
  }

  Blocker getBlocker(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    blockers[y][x]
  }

  void setBlocker(int x, int y, Blocker blocker) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return
    }
    blockers[y][x] = blocker
  }

  Ingredient getIngredient(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    ingredients[y][x]
  }

  void setIngredient(int x, int y, Ingredient ingredient) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return
    }
    if (ingredient != null) {
      cells[y][x] = null
    }
    ingredients[y][x] = ingredient
  }

  Ingredient removeIngredient(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    Ingredient existing = ingredients[y][x]
    ingredients[y][x] = null
    existing
  }

  boolean hasIngredient(int x, int y) {
    requireBounds(x, y)
    isPlayable(x, y) && ingredients[y][x] != null
  }

  BlockerDamage hitBlocker(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return BlockerDamage.none()
    }
    Blocker blocker = blockers[y][x]
    if (blocker == null) {
      return BlockerDamage.none()
    }

    Blocker remaining = blocker.hit()
    blockers[y][x] = remaining
    return new BlockerDamage(blocker.type, blocker.layers, remaining?.layers ?: 0, remaining == null)
  }

  void swap(int x1, int y1, int x2, int y2) {
    requireBounds(x1, y1)
    requireBounds(x2, y2)
    if (!isPlayable(x1, y1) || !isPlayable(x2, y2)) {
      return
    }

    Piece tmp = cells[y1][x1]
    cells[y1][x1] = cells[y2][x2]
    cells[y2][x2] = tmp
  }

  void copyFrom(Board other) {
    if (other.width != width || other.height != height) {
      throw new IllegalArgumentException('Cannot copy board with different dimensions')
    }
    if (!hasSameTopology(other)) {
      throw new IllegalArgumentException('Cannot copy board with different topology')
    }
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        cells[y][x] = other.cells[y][x]
        blockers[y][x] = other.blockers[y][x]
        ingredients[y][x] = other.ingredients[y][x]
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
    Board copy = new Board(width, height, copyPlayableMask(), copyOneWayTiles(), copyTeleporters())
    copy.copyFrom(this)
    return copy
  }

  boolean isPlayable(int x, int y) {
    requireBounds(x, y)
    playable[y][x]
  }

  boolean[][] copyPlayableMask() {
    boolean[][] copy = new boolean[height][width]
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        copy[y][x] = playable[y][x]
      }
    }
    copy
  }

  Map<Position, FlowDirection> copyOneWayTiles() {
    Map<Position, FlowDirection> copy = new LinkedHashMap<>()
    oneWayTiles.each { Position pos, FlowDirection direction ->
      copy[pos] = direction
    }
    copy
  }

  Map<Position, Position> copyTeleporters() {
    Map<Position, Position> copy = new LinkedHashMap<>()
    teleporters.each { Position from, Position to ->
      copy[from] = to
    }
    copy
  }

  FlowDirection flowDirectionAt(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    oneWayTiles[new Position(x, y)]
  }

  Position teleporterTargetAt(int x, int y) {
    requireBounds(x, y)
    if (!isPlayable(x, y)) {
      return null
    }
    teleporters[new Position(x, y)]
  }

  boolean hasOneWayTiles() {
    !oneWayTiles.isEmpty()
  }

  boolean hasTeleporters() {
    !teleporters.isEmpty()
  }

  private void requireBounds(int x, int y) {
    if (!inBounds(x, y)) {
      throw new IndexOutOfBoundsException("Coordinate out of bounds: (${x}, ${y})")
    }
  }

  private static boolean[][] normalizePlayableMask(int width, int height, boolean[][] sourceMask) {
    boolean[][] normalized = new boolean[height][width]
    if (sourceMask == null) {
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          normalized[y][x] = true
        }
      }
      return normalized
    }

    if (sourceMask.length != height) {
      throw new IllegalArgumentException("Playable mask height ${sourceMask.length} does not match board height ${height}")
    }

    for (int y = 0; y < height; y++) {
      if (sourceMask[y] == null || sourceMask[y].length != width) {
        throw new IllegalArgumentException("Playable mask row ${y} does not match board width ${width}")
      }
      for (int x = 0; x < width; x++) {
        normalized[y][x] = sourceMask[y][x]
      }
    }
    normalized
  }

  private static Map<Position, FlowDirection> normalizeOneWayTiles(int width,
                                                                    int height,
                                                                    boolean[][] playableMask,
                                                                    Map<Position, FlowDirection> sourceOneWayTiles) {
    if (sourceOneWayTiles == null || sourceOneWayTiles.isEmpty()) {
      return [:]
    }
    Map<Position, FlowDirection> normalized = new LinkedHashMap<>()
    sourceOneWayTiles.each { Position pos, FlowDirection direction ->
      if (pos == null || direction == null) {
        return
      }
      if (pos.x < 0 || pos.x >= width || pos.y < 0 || pos.y >= height) {
        return
      }
      if (!playableMask[pos.y][pos.x]) {
        return
      }
      normalized[new Position(pos.x, pos.y)] = direction
    }
    normalized
  }

  private static Map<Position, Position> normalizeTeleporters(int width,
                                                               int height,
                                                               boolean[][] playableMask,
                                                               Map<Position, Position> sourceTeleporters) {
    if (sourceTeleporters == null || sourceTeleporters.isEmpty()) {
      return [:]
    }
    Map<Position, Position> normalized = new LinkedHashMap<>()
    sourceTeleporters.each { Position from, Position to ->
      if (from == null || to == null) {
        return
      }
      if (from.x < 0 || from.x >= width || from.y < 0 || from.y >= height) {
        return
      }
      if (to.x < 0 || to.x >= width || to.y < 0 || to.y >= height) {
        return
      }
      if (!playableMask[from.y][from.x] || !playableMask[to.y][to.x]) {
        return
      }
      normalized[new Position(from.x, from.y)] = new Position(to.x, to.y)
    }
    normalized
  }

  private boolean hasSameTopology(Board other) {
    if (other == null) {
      return false
    }
    if (oneWayTiles != other.oneWayTiles || teleporters != other.teleporters) {
      return false
    }
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (playable[y][x] != other.playable[y][x]) {
          return false
        }
      }
    }
    true
  }

  static final class BlockerDamage {
    final BlockerType type
    final int beforeLayers
    final int afterLayers
    final boolean cleared

    private BlockerDamage(BlockerType type, int beforeLayers, int afterLayers, boolean cleared) {
      this.type = type
      this.beforeLayers = beforeLayers
      this.afterLayers = afterLayers
      this.cleared = cleared
    }

    static BlockerDamage none() {
      return new BlockerDamage(null, 0, 0, false)
    }
  }
}
