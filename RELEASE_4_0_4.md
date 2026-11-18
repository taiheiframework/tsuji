# Release 4.0.4

**Release Date:** 2026-11-18

taihei-i18n 4.0.4 is a security release: it bounds the work that formatting one message may do,
whatever its template, arguments and locale hold. Everyone on taihei-i18n 4.x should upgrade.
4.0.4 changes no public API and is binary and source compatible with 4.0.3.

## Upgrading

Change the version to 4.0.4. No code changes are needed:

```scala
mvn"dev.taihei::taihei-i18n:4.0.4"       // Mill, JVM
mvn"dev.taihei::taihei-i18n::4.0.4"      // Mill, Scala.js
"dev.taihei" %%% "taihei-i18n" % "4.0.4" // sbt
```

## Compatibility

Scala 3.9 (LTS), on the JVM and on Scala.js 1.x, as 4.0.3.

`MessageFormat2.format` reads the first 255 characters of its locale; formatting is unchanged for
every locale up to that length. A function applied to a value that `:plural` produced now gets the
value itself, so `{$p :number}` on such a value formats the number.

Bound the size of the messages and templates you accept from outside.
