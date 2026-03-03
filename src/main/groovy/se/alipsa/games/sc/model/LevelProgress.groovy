package se.alipsa.games.sc.model

class LevelProgress {
  String trackId
  boolean completed
  int stars
  int bestScore

  LevelProgress(String trackId, boolean completed = false, int stars = 0, int bestScore = 0) {
    this.trackId = trackId
    this.completed = completed
    this.stars = stars
    this.bestScore = bestScore
  }
}
