#!/usr/bin/env zsh

if [[ -f sweet-crush.log ]]; then
  rm sweet-crush.log
fi
mvn compile exec:java
