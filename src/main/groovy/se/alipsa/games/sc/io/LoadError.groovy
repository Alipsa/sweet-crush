package se.alipsa.games.sc.io

import groovy.transform.Canonical

@Canonical
class LoadError {
  String file
  LoadErrorCode code
  String message
}
