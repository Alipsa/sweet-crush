package se.alipsa.games.sc.core

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import se.alipsa.games.sc.model.Track

class BoardResolver {

  private static final Logger log = LogManager.getLogger(BoardResolver)

  static final int RESHUFFLE_ATTEMPTS_PER_SEED = 100
  static final int RESHUFFLE_SEED_RETRIES = 5

  private final Random random
  private final MatchFinder matchFinder
  private final GravityRefill gravityRefill

  BoardResolver(Random random = new Random(),
                MatchFinder matchFinder = new MatchFinder(),
                GravityRefill gravityRefill = new GravityRefill()) {
    this.random = random
    this.matchFinder = matchFinder
    this.gravityRefill = gravityRefill
  }

  Board createInitialBoard(Track track, GameListener listener = null) {
    Map<CandyType, Integer> weights = GravityRefill.normalizeWeights(track.spawnWeights)
    boolean[][] playableMask = track?.playableMask()
    Map<Position, FlowDirection> oneWayTiles = track?.oneWayTiles
    Map<Position, Position> teleporters = track?.teleporters

    for (int retry = 0; retry < RESHUFFLE_SEED_RETRIES; retry++) {
      for (int attempt = 0; attempt < RESHUFFLE_ATTEMPTS_PER_SEED; attempt++) {
        Board candidate = generateBoard(track.width, track.height, weights, playableMask, oneWayTiles, teleporters)
        if (candidate != null && !matchFinder.hasAnyMatch(candidate) && hasLegalSwap(candidate)) {
          applyInitialBlockers(candidate, track)
          return candidate
        }
      }
    }

    listener?.onReshuffleExhausted()
    throw new IllegalStateException('Unable to generate a playable initial board within configured attempt limits')
  }

  CascadeResult resolve(Board board,
                        Map<CandyType, Integer> spawnWeights,
                        GameListener listener = null) {
    return resolve(board, spawnWeights, [:], [] as Set<Position>, null, listener)
  }

  CascadeResult resolve(Board board,
                        Map<CandyType, Integer> spawnWeights,
                        Map<SpecialPieceType, Integer> remainingSpecialPieces,
                        Set<Position> forcedActivations = [] as Set<Position>,
                        GameListener listener = null) {
    return resolve(board, spawnWeights, remainingSpecialPieces, forcedActivations, null, listener)
  }

  CascadeResult resolve(Board board,
                        Map<CandyType, Integer> spawnWeights,
                        Map<SpecialPieceType, Integer> remainingSpecialPieces,
                        Set<Position> forcedActivations,
                        SpecialSwapCombo specialSwapCombo,
                        GameListener listener) {
    Map<CandyType, Integer> weights = GravityRefill.normalizeWeights(spawnWeights)
    List<Integer> groupSizes = []
    List<Map<CandyType, Integer>> groupCandyCounts = []
    Map<BlockerType, Integer> clearedBlockers = new EnumMap<>(BlockerType)

    Set<Position> activationSeeds = new LinkedHashSet<>(forcedActivations ?: [])
    Set<Position> preferredCreationAnchors = new LinkedHashSet<>(forcedActivations ?: [])
    SpecialSwapCombo pendingSpecialSwapCombo = specialSwapCombo

    while (true) {
      MatchFinder.MatchAnalysis analysis = matchFinder.analyze(board)
      Set<Position> matchedPositions = [] as Set<Position>
      analysis.groups.each { Set<Position> group -> matchedPositions.addAll(group) }
      if (log.isDebugEnabled() && !analysis.creationCandidates.isEmpty()) {
        long sweeperCandidates = analysis.creationCandidates.count { it.specialType == SpecialPieceType.SWEEPER }
        log.debug('Cascade detected {} creation candidates (sweepers={}), matchedGroups={}, forcedAnchors={}',
            analysis.creationCandidates.size(),
            sweeperCandidates,
            analysis.groups*.size(),
            preferredCreationAnchors)
      }

      if (matchedPositions.isEmpty() && activationSeeds.isEmpty() && pendingSpecialSwapCombo == null) {
        break
      }

      Set<Position> clearPositions = new LinkedHashSet<>(matchedPositions)
      ArrayDeque<Position> queue = new ArrayDeque<>()
      Set<Position> activatedSpecials = [] as Set<Position>
      Map<Position, CandyType> forcedBombColorTargets = forcedBombActivationColors(board, activationSeeds)

      activationSeeds.each { Position seed ->
        if (board.inBounds(seed.x, seed.y)) {
          Piece piece = board.getPiece(seed.x, seed.y)
          if (canActivateFromForced(piece)) {
            clearPositions.add(seed)
            queue.add(seed)
          }
        }
      }
      activationSeeds.clear()

      matchedPositions.each { Position pos ->
        Piece piece = board.getPiece(pos.x, pos.y)
        if (canActivateFromMatch(board, piece, pos)) {
          queue.add(pos)
        }
      }

      if (pendingSpecialSwapCombo != null) {
        ComboApplicationResult comboResult = applySpecialSwapCombo(board, pendingSpecialSwapCombo, listener)
        clearPositions.addAll(comboResult.clearPositions)
        comboResult.chainActivationSeeds.each { Position seed ->
          if (board.inBounds(seed.x, seed.y)) {
            queue.add(seed)
          }
        }
        pendingSpecialSwapCombo = null
      }

      while (!queue.isEmpty()) {
        Position current = queue.removeFirst()
        if (!board.inBounds(current.x, current.y) || !activatedSpecials.add(current)) {
          continue
        }

        Piece piece = board.getPiece(current.x, current.y)
        if (piece == null || !piece.isSpecial()) {
          continue
        }

        listener?.onSpecialActivated(piece.specialType, current, piece.sweeperHorizontal)

        CandyType forcedBombColor = piece.specialType == SpecialPieceType.BOMB
            ? forcedBombColorTargets.get(current)
            : null
        List<Position> effectTargets = effectPositionsFor(piece, current, board, forcedBombColor, listener)
        if (piece.specialType == SpecialPieceType.FISH && listener != null) {
          effectTargets.each { Position target ->
            listener.onFishLaunched(current, target)
          }
        }

        effectTargets.each { Position effectPos ->
          if (board.inBounds(effectPos.x, effectPos.y)) {
            clearPositions.add(effectPos)
            Piece target = board.getPiece(effectPos.x, effectPos.y)
            if (canActivateFromChain(target) && !activatedSpecials.contains(effectPos)) {
              queue.add(effectPos)
            }
          }
        }
      }

      Map<Position, Piece> createdSpecials = createSpecialPieces(
          board, analysis.creationCandidates, clearPositions, preferredCreationAnchors, remainingSpecialPieces
      )
      preferredCreationAnchors.clear()

      Map<CandyType, Integer> counts = new EnumMap<>(CandyType)
      int clearedCount = 0
      clearPositions.each { Position pos ->
        Board.BlockerDamage blockerDamage = board.hitBlocker(pos.x, pos.y)
        if (blockerDamage.cleared && blockerDamage.type != null) {
          clearedBlockers[blockerDamage.type] = clearedBlockers.getOrDefault(blockerDamage.type, 0) + 1
        }
        Piece piece = board.getPiece(pos.x, pos.y)
        if (piece != null) {
          clearedCount++
          counts[piece.color] = counts.getOrDefault(piece.color, 0) + 1
        }
      }

      if (clearedCount > 0) {
        groupSizes << clearedCount
        groupCandyCounts << Collections.unmodifiableMap(new EnumMap<>(counts))
      }

      clearPositions.each { Position pos ->
        if (!createdSpecials.containsKey(pos)) {
          board.setPiece(pos.x, pos.y, null)
        }
      }
      createdSpecials.each { Position pos, Piece piece ->
        board.setPiece(pos.x, pos.y, piece)
      }

      gravityRefill.apply(board, weights, random)
    }

    ensurePlayable(board, weights, listener)
    return new CascadeResult(groupSizes, groupCandyCounts, clearedBlockers)
  }

  boolean ensurePlayable(Board board,
                         Map<CandyType, Integer> spawnWeights,
                         GameListener listener = null) {
    if (hasLegalSwap(board)) {
      return true
    }

    Map<CandyType, Integer> weights = GravityRefill.normalizeWeights(spawnWeights)
    Map<Position, Blocker> blockers = captureBlockers(board)
    Map<Position, Ingredient> ingredients = captureIngredients(board)
    boolean[][] playableMask = board.copyPlayableMask()
    Map<Position, FlowDirection> oneWayTiles = board.copyOneWayTiles()
    Map<Position, Position> teleporters = board.copyTeleporters()

    for (int retry = 0; retry < RESHUFFLE_SEED_RETRIES; retry++) {
      for (int attempt = 0; attempt < RESHUFFLE_ATTEMPTS_PER_SEED; attempt++) {
        Board candidate = generateBoard(board.width, board.height, weights, playableMask, oneWayTiles, teleporters)
        restoreBlockers(candidate, blockers)
        restoreIngredients(candidate, ingredients)
        if (candidate != null && !matchFinder.hasAnyMatch(candidate) && hasLegalSwap(candidate)) {
          board.copyFrom(candidate)
          return true
        }
      }
    }

    listener?.onReshuffleExhausted()
    return false
  }

  boolean hasLegalSwap(Board board) {
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (!board.isPlayable(x, y) || board.getPiece(x, y) == null) {
          continue
        }
        if (x + 1 < board.width && createsMatchAfterSwap(board, x, y, x + 1, y)) {
          return true
        }
        if (y + 1 < board.height && createsMatchAfterSwap(board, x, y, x, y + 1)) {
          return true
        }
      }
    }
    false
  }

  private boolean createsMatchAfterSwap(Board board, int x1, int y1, int x2, int y2) {
    if (!board.isPlayable(x1, y1) || !board.isPlayable(x2, y2)) {
      return false
    }
    Piece first = board.getPiece(x1, y1)
    Piece second = board.getPiece(x2, y2)
    if (first == null || second == null) {
      return false
    }
    if (isSpecialSwapCombo(first, second) || isSwapTriggeredSpecial(first) || isSwapTriggeredSpecial(second)) {
      return true
    }

    board.swap(x1, y1, x2, y2)
    boolean hasMatch = matchFinder.hasAnyMatch(board)
    board.swap(x1, y1, x2, y2)
    hasMatch
  }

  private Map<Position, Piece> createSpecialPieces(Board board,
                                                   List<MatchFinder.SpecialCreationCandidate> candidates,
                                                   Set<Position> clearPositions,
                                                   Set<Position> preferredAnchors,
                                                   Map<SpecialPieceType, Integer> remainingSpecialPieces) {
    if (candidates == null || candidates.isEmpty() || remainingSpecialPieces == null || remainingSpecialPieces.isEmpty()) {
      return [:]
    }

    List<MatchFinder.SpecialCreationCandidate> sorted = candidates.toList().sort { a, b ->
      int prio = Integer.compare(b.priority, a.priority)
      if (prio != 0) {
        return prio
      }
      boolean aHasPreferredAnchor = candidateHasPreferredAnchor(board, a, clearPositions, preferredAnchors)
      boolean bHasPreferredAnchor = candidateHasPreferredAnchor(board, b, clearPositions, preferredAnchors)
      int preferredComp = Boolean.compare(bHasPreferredAnchor, aHasPreferredAnchor)
      if (preferredComp != 0) {
        return preferredComp
      }
      int yComp = Integer.compare(a.anchor.y, b.anchor.y)
      if (yComp != 0) {
        return yComp
      }
      Integer.compare(a.anchor.x, b.anchor.x)
    }

    for (MatchFinder.SpecialCreationCandidate candidate : sorted) {
      if (log.isDebugEnabled()) {
        log.debug('Evaluating candidate {} at {} (color={}, priority={}, sweeperOrientation={})',
            candidate.specialType,
            candidate.anchor,
            candidate.color,
            candidate.priority,
            candidate.sweeperHorizontal ? 'horizontal' : 'vertical')
      }
      Position creationAnchor = chooseCreationAnchor(board, candidate, clearPositions, preferredAnchors, [] as Set<Position>)
      if (creationAnchor != null) {
        int remaining = remainingSpecialPieces.getOrDefault(candidate.specialType, 0)
        if (remaining > 0) {
          CandyType color = board.getCell(creationAnchor.x, creationAnchor.y) ?: candidate.color
          if (color != null) {
            Piece specialPiece
            switch (candidate.specialType) {
              case SpecialPieceType.SWEEPER:
                specialPiece = Piece.sweeper(color, candidate.sweeperHorizontal)
                break
              case SpecialPieceType.SMALL_BOMB:
                specialPiece = Piece.smallBomb(color)
                break
              case SpecialPieceType.BOMB:
                specialPiece = Piece.bomb(color)
                break
              case SpecialPieceType.FISH:
                specialPiece = Piece.fish(color)
                break
            }

            if (specialPiece != null) {
              remainingSpecialPieces[candidate.specialType] = remaining - 1
              log.debug('Created {} at {} (candidateAnchor={}, remainingAfter={})',
                  candidate.specialType,
                  creationAnchor,
                  candidate.anchor,
                  remainingSpecialPieces[candidate.specialType])
              return [(creationAnchor): specialPiece]
            }
          } else {
            log.debug('Skipped {} candidate at {} because creation color resolved to null',
                candidate.specialType,
                creationAnchor)
          }
        } else {
          log.debug('Skipped {} candidate at {} because budget is exhausted',
              candidate.specialType,
              creationAnchor)
        }
      } else {
        log.debug('Skipped {} candidate at {} because no valid creation anchor was available (clearContainsAnchor={}, preferredAnchors={})',
            candidate.specialType,
            candidate.anchor,
            clearPositions.contains(candidate.anchor),
            preferredAnchors)
      }
    }

    return [:]
  }

  private static boolean candidateHasPreferredAnchor(Board board,
                                                     MatchFinder.SpecialCreationCandidate candidate,
                                                     Set<Position> clearPositions,
                                                     Set<Position> preferredAnchors) {
    preferredAnchors?.any { Position pos ->
      clearPositions.contains(pos) && isCandidateColorCompatible(board, candidate, pos)
    } ?: false
  }

  private static Position chooseCreationAnchor(Board board,
                                               MatchFinder.SpecialCreationCandidate candidate,
                                               Set<Position> clearPositions,
                                               Set<Position> preferredAnchors,
                                               Set<Position> usedAnchors) {
    Position preferred = preferredAnchors?.find { Position pos ->
      clearPositions.contains(pos) &&
          !usedAnchors.contains(pos) &&
          isCandidateColorCompatible(board, candidate, pos)
    }
    if (preferred != null) {
      return preferred
    }

    if (clearPositions.contains(candidate.anchor) &&
        !usedAnchors.contains(candidate.anchor) &&
        isCandidateColorCompatible(board, candidate, candidate.anchor)) {
      return candidate.anchor
    }

    return null
  }

  private static boolean isCandidateColorCompatible(Board board,
                                                    MatchFinder.SpecialCreationCandidate candidate,
                                                    Position pos) {
    if (candidate.color == null) {
      return true
    }
    board.getCell(pos.x, pos.y) == candidate.color
  }

  private ComboApplicationResult applySpecialSwapCombo(Board board,
                                                       SpecialSwapCombo combo,
                                                       GameListener listener) {
    Set<Position> clearPositions = [] as Set<Position>
    Set<Position> chainSeeds = [] as Set<Position>
    if (combo == null) {
      return new ComboApplicationResult(clearPositions, chainSeeds)
    }

    Position firstPos = combo.firstPos
    Position secondPos = combo.secondPos
    if (firstPos != null && board.inBounds(firstPos.x, firstPos.y)) {
      clearPositions << firstPos
    }
    if (secondPos != null && board.inBounds(secondPos.x, secondPos.y)) {
      clearPositions << secondPos
    }

    Piece firstPiece = combo.firstPiece ?: (firstPos != null && board.inBounds(firstPos.x, firstPos.y) ? board.getPiece(firstPos.x, firstPos.y) : null)
    Piece secondPiece = combo.secondPiece ?: (secondPos != null && board.inBounds(secondPos.x, secondPos.y) ? board.getPiece(secondPos.x, secondPos.y) : null)
    if (!(firstPiece?.isSpecial()) || !(secondPiece?.isSpecial())) {
      return new ComboApplicationResult(clearPositions, chainSeeds)
    }

    ComboKind kind = ComboKind.from(firstPiece.specialType, secondPiece.specialType)
    if (kind == null) {
      return new ComboApplicationResult(clearPositions, chainSeeds)
    }

    Position sweeperPos = firstPiece.specialType == SpecialPieceType.SWEEPER ? firstPos : secondPos
    Piece sweeperPiece = firstPiece.specialType == SpecialPieceType.SWEEPER ? firstPiece : secondPiece
    Position smallBombPos = firstPiece.specialType == SpecialPieceType.SMALL_BOMB ? firstPos : secondPos
    Piece smallBombPiece = firstPiece.specialType == SpecialPieceType.SMALL_BOMB ? firstPiece : secondPiece
    Position fishPos = firstPiece.specialType == SpecialPieceType.FISH ? firstPos : secondPos
    Piece fishPiece = firstPiece.specialType == SpecialPieceType.FISH ? firstPiece : secondPiece

    Closure<Void> addSweep = { Position origin, boolean horizontal ->
      if (origin != null && board.inBounds(origin.x, origin.y)) {
        listener?.onSpecialActivated(SpecialPieceType.SWEEPER, origin, horizontal)
        clearPositions.addAll(sweeperTargets(horizontal, origin, board))
      }
      return null
    }
    Closure<Void> addSmallBomb = { Position origin ->
      if (origin != null && board.inBounds(origin.x, origin.y)) {
        listener?.onSpecialActivated(SpecialPieceType.SMALL_BOMB, origin, true)
        clearPositions.addAll(smallBombTargets(origin, board))
      }
      return null
    }
    Closure<Void> addBomb = { Position origin ->
      if (origin != null && board.inBounds(origin.x, origin.y)) {
        listener?.onSpecialActivated(SpecialPieceType.BOMB, origin, true)
        clearPositions.addAll(bombTargets(origin, board))
      }
      return null
    }
    Closure<Void> addFishLaunchHit = { Position origin, Position target ->
      if (origin != null && target != null && board.inBounds(target.x, target.y)) {
        listener?.onFishLaunched(origin, target)
        clearPositions << target
      }
      return null
    }

    switch (kind) {
      case ComboKind.SWEEPER_SWEEPER:
        addSweep(firstPos, true)
        addSweep(firstPos, false)
        addSweep(secondPos, true)
        addSweep(secondPos, false)
        break
      case ComboKind.SWEEPER_SMALL_BOMB:
        boolean horizontal = sweeperPiece?.sweeperHorizontal ?: true
        for (int offset = -1; offset <= 1; offset++) {
          Position lineOrigin = horizontal
              ? new Position(sweeperPos.x, sweeperPos.y + offset)
              : new Position(sweeperPos.x + offset, sweeperPos.y)
          addSweep(lineOrigin, horizontal)
        }
        break
      case ComboKind.SWEEPER_FISH:
        Position leastTarget = chooseFishTarget(board, fishPos, false, [firstPos, secondPos] as Set<Position>)
        Position sweepTarget = leastTarget ?: sweeperPos
        if (leastTarget != null) {
          listener?.onFishLaunched(fishPos, leastTarget)
        }
        addSweep(sweepTarget, sweeperPiece?.sweeperHorizontal ?: true)
        break
      case ComboKind.SWEEPER_BOMB:
        List<Position> sweeperOrigins = positionsMatchingColor(board, sweeperPiece?.color, [firstPos, secondPos] as Set<Position>)
        sweeperOrigins.each { Position origin ->
          addSweep(origin, random.nextBoolean())
        }
        break
      case ComboKind.SMALL_BOMB_SMALL_BOMB:
        addSmallBomb(firstPos)
        addSmallBomb(secondPos)
        clearPositions.addAll(doubleBombTargets(firstPos, secondPos, board))
        break
      case ComboKind.SMALL_BOMB_FISH:
        Position leastBombTarget = chooseFishTarget(board, fishPos, false, [firstPos, secondPos] as Set<Position>)
        Position bombTarget = leastBombTarget ?: smallBombPos
        if (leastBombTarget != null) {
          listener?.onFishLaunched(fishPos, leastBombTarget)
        }
        addSmallBomb(bombTarget)
        break
      case ComboKind.SMALL_BOMB_BOMB:
        List<Position> bombOrigins = positionsMatchingColor(board, smallBombPiece?.color, [firstPos, secondPos] as Set<Position>)
        bombOrigins.each { Position origin ->
          addSmallBomb(origin)
        }
        break
      case ComboKind.FISH_FISH:
        Set<Position> usedTargets = [firstPos, secondPos].findAll { it != null } as Set<Position>
        [firstPos, secondPos].findAll { it != null }.each { Position origin ->
          Position target = chooseFishTarget(board, origin, true, usedTargets)
          if (target != null) {
            usedTargets << target
            addFishLaunchHit(origin, target)
          }
        }
        break
      case ComboKind.FISH_BOMB:
        List<Position> fishOrigins = positionsMatchingColor(board, fishPiece?.color, [firstPos, secondPos] as Set<Position>)
        Set<Position> usedTargets = [firstPos, secondPos].findAll { it != null } as Set<Position>
        fishOrigins.each { Position origin ->
          Position target = chooseFishTarget(board, origin, true, usedTargets)
          if (target != null) {
            usedTargets << target
            listener?.onFishLaunched(origin, target)
            addBomb(target)
          }
        }
        break
      case ComboKind.BOMB_BOMB:
        addBomb(firstPos)
        addBomb(secondPos)
        for (int y = 0; y < board.height; y++) {
          for (int x = 0; x < board.width; x++) {
            if (board.getPiece(x, y) != null) {
              clearPositions << new Position(x, y)
            }
          }
        }
        break
    }

    clearPositions.each { Position pos ->
      if (pos == null || !board.inBounds(pos.x, pos.y)) {
        return
      }
      boolean isComboCell = (firstPos != null && pos == firstPos) || (secondPos != null && pos == secondPos)
      if (!isComboCell) {
        Piece target = board.getPiece(pos.x, pos.y)
        if (canActivateFromChain(target)) {
          chainSeeds << pos
        }
      }
    }

    return new ComboApplicationResult(clearPositions, chainSeeds)
  }

  private static List<Position> doubleBombTargets(Position first, Position second, Board board) {
    if (first == null || second == null) {
      return []
    }
    int centerX = (first.x + second.x).intdiv(2)
    int centerY = (first.y + second.y).intdiv(2)
    List<Position> targets = []
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -2; dx <= 2; dx++) {
        int x = centerX + dx
        int y = centerY + dy
        if (board.inBounds(x, y)) {
          targets << new Position(x, y)
        }
      }
    }
    targets
  }

  private static List<Position> positionsMatchingColor(Board board,
                                                       CandyType color,
                                                       Set<Position> excluded = [] as Set<Position>) {
    if (color == null) {
      return []
    }
    List<Position> positions = []
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        Position pos = new Position(x, y)
        if (!excluded.contains(pos) && board.getCell(x, y) == color) {
          positions << pos
        }
      }
    }
    positions
  }

  private static Map<Position, CandyType> forcedBombActivationColors(Board board, Set<Position> activationSeeds) {
    Map<Position, CandyType> colors = [:]
    if (board == null || activationSeeds == null || activationSeeds.isEmpty()) {
      return colors
    }

    List<Position> seeds = activationSeeds.findAll { Position pos ->
      pos != null && board.inBounds(pos.x, pos.y)
    }.toList()
    seeds.each { Position pos ->
      Piece piece = board.getPiece(pos.x, pos.y)
      if (piece?.specialType == SpecialPieceType.BOMB) {
        Position partner = seeds.find { Position candidate -> candidate != pos }
        CandyType targetColor = partner == null ? null : board.getCell(partner.x, partner.y)
        if (targetColor != null) {
          colors[pos] = targetColor
        }
      }
    }
    colors
  }

  private List<Position> effectPositionsFor(Piece piece, Position origin, Board board) {
    effectPositionsFor(piece, origin, board, null, null)
  }

  private List<Position> effectPositionsFor(Piece piece,
                                            Position origin,
                                            Board board,
                                            CandyType forcedBombColor,
                                            GameListener listener) {
    switch (piece.specialType) {
      case SpecialPieceType.SWEEPER:
        return sweeperTargets(piece.sweeperHorizontal, origin, board)
      case SpecialPieceType.SMALL_BOMB:
        return smallBombTargets(origin, board)
      case SpecialPieceType.BOMB:
        return forcedBombColor == null
            ? bombTargets(origin, board)
            : colorBombTargets(origin, forcedBombColor, board, listener)
      case SpecialPieceType.FISH:
        return fishTargets(origin, board)
      default:
        return []
    }
  }

  private static List<Position> sweeperTargets(boolean horizontal, Position origin, Board board) {
    List<Position> targets = []
    if (horizontal) {
      for (int x = 0; x < board.width; x++) {
        targets << new Position(x, origin.y)
      }
    } else {
      for (int y = 0; y < board.height; y++) {
        targets << new Position(origin.x, y)
      }
    }
    targets
  }

  private static boolean isSwapTriggeredSpecial(Piece piece) {
    piece?.isSpecial() && piece.specialType != SpecialPieceType.SWEEPER
  }

  private static boolean isSpecialSwapCombo(Piece first, Piece second) {
    first?.isSpecial() && second?.isSpecial()
  }

  private static boolean canActivateFromForced(Piece piece) {
    isSwapTriggeredSpecial(piece)
  }

  private static boolean canActivateFromChain(Piece piece) {
    piece?.isSpecial() && piece.specialType != SpecialPieceType.SWEEPER
  }

  private static boolean canActivateFromMatch(Board board, Piece piece, Position pos) {
    piece?.isSpecial()
  }

  private static List<Position> bombTargets(Position origin, Board board) {
    List<Position> targets = []
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        int x = origin.x + dx
        int y = origin.y + dy
        if (board.inBounds(x, y)) {
          targets << new Position(x, y)
        }
      }
    }
    targets
  }

  private static List<Position> colorBombTargets(Position origin,
                                                 CandyType targetColor,
                                                 Board board,
                                                 GameListener listener) {
    if (targetColor == null || board == null) {
      return []
    }
    List<Position> targets = []
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        if (board.getCell(x, y) == targetColor) {
          Position target = new Position(x, y)
          targets << target
          if (origin != null && target != origin) {
            listener?.onBombBeam(origin, target)
          }
        }
      }
    }
    targets
  }

  private static List<Position> smallBombTargets(Position origin, Board board) {
    List<Position> targets = []
    int[][] deltas = [
        [0, 0],
        [-1, 0],
        [1, 0],
        [0, -1],
        [0, 1]
    ] as int[][]
    deltas.each { int[] delta ->
      int x = origin.x + delta[0]
      int y = origin.y + delta[1]
      if (board.inBounds(x, y)) {
        targets << new Position(x, y)
      }
    }
    targets
  }

  private List<Position> fishTargets(Position origin, Board board) {
    Position chosen = chooseFishTarget(board, origin, true)
    chosen == null ? [] : [chosen]
  }

  private Position chooseFishTarget(Board board,
                                    Position origin,
                                    boolean preferPromising,
                                    Set<Position> excluded = [] as Set<Position>) {
    if (board == null || origin == null) {
      return null
    }

    Set<Position> blocked = new LinkedHashSet<>(excluded ?: [])
    blocked << origin

    List<Position> candidates = []
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        Position candidate = new Position(x, y)
        if (!blocked.contains(candidate) && board.getPiece(x, y) != null) {
          candidates << candidate
        }
      }
    }

    if (candidates.isEmpty()) {
      return null
    }

    int baselineMatchedCells = matchedCellCount(board)
    Integer selectedScore = null
    List<Position> selectedTargets = []
    candidates.each { Position pos ->
      int score = fishTargetScore(board, pos, baselineMatchedCells)
      boolean better = selectedScore == null || (preferPromising ? score > selectedScore : score < selectedScore)
      if (better) {
        selectedScore = score
        selectedTargets = [pos]
      } else if (score == selectedScore) {
        selectedTargets << pos
      }
    }

    Position chosen = selectedTargets.size() == 1
        ? selectedTargets[0]
        : selectedTargets[random.nextInt(selectedTargets.size())]

    if (log.isDebugEnabled()) {
      Piece chosenPiece = board.getPiece(chosen.x, chosen.y)
      log.debug('Fish target selected {} (mode={}, score={}, targetSpecialType={}) from {} candidates',
          chosen,
          preferPromising ? 'best' : 'least',
          selectedScore,
          chosenPiece?.specialType,
          candidates.size())
    }
    chosen
  }

  private int fishTargetScore(Board board, Position pos, int baselineMatchedCells) {
    Piece piece = board.getPiece(pos.x, pos.y)
    if (piece == null) {
      return Integer.MIN_VALUE
    }

    if (piece.isSpecial()) {
      switch (piece.specialType) {
        case SpecialPieceType.BOMB:
          return 500
        case SpecialPieceType.SMALL_BOMB:
          return 400
        case SpecialPieceType.SWEEPER:
          return 300
        case SpecialPieceType.FISH:
          return 200
        default:
          return 100
      }
    }

    if (createsAdditionalMatchAfterFishHit(board, pos, baselineMatchedCells)) {
      return 100
    }
    return 10
  }

  private boolean createsAdditionalMatchAfterFishHit(Board board, Position target, int baselineMatchedCells) {
    Board simulated = board.clone()
    simulated.setPiece(target.x, target.y, null)
    applyGravityWithoutRefill(simulated)
    int afterMatchedCells = matchedCellCount(simulated)
    afterMatchedCells > baselineMatchedCells
  }

  private int matchedCellCount(Board board) {
    Set<Position> matched = [] as Set<Position>
    matchFinder.analyze(board).groups.each { Set<Position> group ->
      matched.addAll(group)
    }
    matched.size()
  }

  private void applyGravityWithoutRefill(Board board) {
    gravityRefill.applyWithoutRefill(board)
  }

  private Board generateBoard(int width,
                              int height,
                              Map<CandyType, Integer> spawnWeights,
                              boolean[][] playableMask = null,
                              Map<Position, FlowDirection> oneWayTiles = null,
                              Map<Position, Position> teleporters = null) {
    Board board = new Board(width, height, playableMask, oneWayTiles, teleporters)

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (!board.isPlayable(x, y)) {
          continue
        }
        Set<CandyType> forbidden = [] as Set<CandyType>

        if (x >= 2) {
          CandyType left1 = board.getCell(x - 1, y)
          CandyType left2 = board.getCell(x - 2, y)
          if (left1 != null && left1 == left2) {
            forbidden << left1
          }
        }

        if (y >= 2) {
          CandyType up1 = board.getCell(x, y - 1)
          CandyType up2 = board.getCell(x, y - 2)
          if (up1 != null && up1 == up2) {
            forbidden << up1
          }
        }

        CandyType next = GravityRefill.pickCandy(spawnWeights, random, forbidden)
        if (next == null) {
          return null
        }

        board.setCell(x, y, next)
      }
    }

    board
  }

  private static void applyInitialBlockers(Board board, Track track) {
    if (board == null || track?.blockers == null || track.blockers.isEmpty()) {
      return
    }
    track.blockers.each { Position pos, Blocker blocker ->
      if (pos != null && blocker != null && board.inBounds(pos.x, pos.y)) {
        board.setBlocker(pos.x, pos.y, blocker)
      }
    }
  }

  private static Map<Position, Blocker> captureBlockers(Board board) {
    Map<Position, Blocker> blockers = [:]
    if (board == null) {
      return blockers
    }
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        Blocker blocker = board.getBlocker(x, y)
        if (blocker != null) {
          blockers[new Position(x, y)] = blocker
        }
      }
    }
    blockers
  }

  private static void restoreBlockers(Board board, Map<Position, Blocker> blockers) {
    if (board == null || blockers == null || blockers.isEmpty()) {
      return
    }
    blockers.each { Position pos, Blocker blocker ->
      if (pos != null && blocker != null && board.inBounds(pos.x, pos.y)) {
        board.setBlocker(pos.x, pos.y, blocker)
      }
    }
  }

  private static Map<Position, Ingredient> captureIngredients(Board board) {
    Map<Position, Ingredient> ingredients = [:]
    if (board == null) {
      return ingredients
    }
    for (int y = 0; y < board.height; y++) {
      for (int x = 0; x < board.width; x++) {
        Ingredient ingredient = board.getIngredient(x, y)
        if (ingredient != null) {
          ingredients[new Position(x, y)] = ingredient
        }
      }
    }
    ingredients
  }

  private static void restoreIngredients(Board board, Map<Position, Ingredient> ingredients) {
    if (board == null || ingredients == null || ingredients.isEmpty()) {
      return
    }
    ingredients.each { Position pos, Ingredient ingredient ->
      if (pos != null && ingredient != null && board.inBounds(pos.x, pos.y)) {
        board.setIngredient(pos.x, pos.y, ingredient)
      }
    }
  }

  static class CascadeResult {
    final List<Integer> groupSizes
    final List<Map<CandyType, Integer>> groupCandyCounts
    final Map<BlockerType, Integer> clearedBlockers

    CascadeResult(List<Integer> groupSizes = [],
                  List<Map<CandyType, Integer>> groupCandyCounts = [],
                  Map<BlockerType, Integer> clearedBlockers = [:]) {
      this.groupSizes = List.copyOf(groupSizes)
      this.groupCandyCounts = List.copyOf(groupCandyCounts)
      Map<BlockerType, Integer> normalized = new EnumMap<>(BlockerType)
      if (clearedBlockers != null) {
        normalized.putAll(clearedBlockers)
      }
      this.clearedBlockers = Collections.unmodifiableMap(normalized)
    }
  }

  static class SpecialSwapCombo {
    final Position firstPos
    final Piece firstPiece
    final Position secondPos
    final Piece secondPiece

    SpecialSwapCombo(Position firstPos, Piece firstPiece, Position secondPos, Piece secondPiece) {
      this.firstPos = firstPos
      this.firstPiece = firstPiece
      this.secondPos = secondPos
      this.secondPiece = secondPiece
    }
  }

  private static final class ComboApplicationResult {
    final Set<Position> clearPositions
    final Set<Position> chainActivationSeeds

    ComboApplicationResult(Set<Position> clearPositions, Set<Position> chainActivationSeeds) {
      this.clearPositions = clearPositions ?: ([] as Set<Position>)
      this.chainActivationSeeds = chainActivationSeeds ?: ([] as Set<Position>)
    }
  }

  private static enum ComboKind {
    SWEEPER_SWEEPER(SpecialPieceType.SWEEPER, SpecialPieceType.SWEEPER),
    SWEEPER_SMALL_BOMB(SpecialPieceType.SWEEPER, SpecialPieceType.SMALL_BOMB),
    SWEEPER_FISH(SpecialPieceType.SWEEPER, SpecialPieceType.FISH),
    SWEEPER_BOMB(SpecialPieceType.SWEEPER, SpecialPieceType.BOMB),
    SMALL_BOMB_SMALL_BOMB(SpecialPieceType.SMALL_BOMB, SpecialPieceType.SMALL_BOMB),
    SMALL_BOMB_FISH(SpecialPieceType.SMALL_BOMB, SpecialPieceType.FISH),
    SMALL_BOMB_BOMB(SpecialPieceType.SMALL_BOMB, SpecialPieceType.BOMB),
    FISH_FISH(SpecialPieceType.FISH, SpecialPieceType.FISH),
    FISH_BOMB(SpecialPieceType.FISH, SpecialPieceType.BOMB),
    BOMB_BOMB(SpecialPieceType.BOMB, SpecialPieceType.BOMB)

    final SpecialPieceType first
    final SpecialPieceType second

    ComboKind(SpecialPieceType first, SpecialPieceType second) {
      this.first = first
      this.second = second
    }

    static ComboKind from(SpecialPieceType left, SpecialPieceType right) {
      if (left == null || right == null) {
        return null
      }
      values().find { ComboKind kind ->
        (kind.first == left && kind.second == right) || (kind.first == right && kind.second == left)
      }
    }
  }
}
