package se.alipsa.games.sc.ui

import spock.lang.Specification

class TrackCreationDialogTest extends Specification {

  def 'creates base id from track name'() {
    expect:
    TrackCreationDialog.baseIdForName('My First Track!') == 'my-first-track'
    TrackCreationDialog.baseIdForName('  ') == 'track'
    TrackCreationDialog.baseIdForName('___Hello___World___') == 'hello-world'
  }

  def 'creates unique id with counter suffix when duplicate exists'() {
    given:
    Set<String> existing = ['new-track', 'new-track-2', 'new-track-3'] as Set<String>

    expect:
    TrackCreationDialog.uniqueIdForName('New Track', existing) == 'new-track-4'
  }
}
