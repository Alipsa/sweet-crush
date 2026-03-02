package se.alipsa.games.sc.core

import java.util.Objects

final class Ingredient {

  final IngredientType type

  Ingredient(IngredientType type) {
    this.type = Objects.requireNonNull(type, 'type')
  }
}
