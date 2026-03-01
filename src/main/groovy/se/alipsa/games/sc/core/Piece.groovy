package se.alipsa.games.sc.core

import java.util.Objects

final class Piece {

  final CandyType color
  final SpecialPieceType specialType
  final boolean sweeperHorizontal

  Piece(CandyType color, SpecialPieceType specialType = null, boolean sweeperHorizontal = true) {
    this.color = Objects.requireNonNull(color, 'color')
    this.specialType = specialType
    this.sweeperHorizontal = sweeperHorizontal
  }

  static Piece normal(CandyType color) {
    new Piece(color)
  }

  static Piece sweeper(CandyType color, boolean horizontal) {
    new Piece(color, SpecialPieceType.SWEEPER, horizontal)
  }

  static Piece bomb(CandyType color) {
    new Piece(color, SpecialPieceType.BOMB, true)
  }

  static Piece smallBomb(CandyType color) {
    new Piece(color, SpecialPieceType.SMALL_BOMB, true)
  }

  static Piece fish(CandyType color) {
    new Piece(color, SpecialPieceType.FISH, true)
  }

  boolean isSpecial() {
    specialType != null
  }
}
