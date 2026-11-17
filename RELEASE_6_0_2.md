# Release 6.0.2

**Release Date:** 2026-11-17

Taihei Tsuji 6.0.2 is a security release with two security fixes; the details are in the advisories
that follow this release. Everyone on 6.0.0 should upgrade. 6.0.2 changes no public API and is
binary and source compatible with 6.0.0.

Version 6.0.1 was tagged but never published; 6.0.2 replaces it.

## Upgrading

Change the version to 6.0.2:

```scala
mvn"dev.taihei::taihei-tsuji::6.0.2"        // Mill
"dev.taihei" %%% "taihei-tsuji" % "6.0.2"   // sbt, JVM and Scala.js
```

## Compatibility

Scala 3.9 (LTS), JVM (Java 17 or later) and Scala.js 1.x, as 6.0.0.

On the JVM, a language tag with parts the JDK has no locale data for is now formatted and named
with the nearest locale the JDK has data for. Formatting and naming are unchanged for every locale
the JDK has data for.
