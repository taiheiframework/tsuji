# Release 5.0.3

**Release Date:** 2026-11-17

Taihei Tsuji 5.0.3 is a security release with two security fixes; the details are in the advisories
that follow this release. Everyone on 5.0.0, 5.0.1 or 5.0.2 should upgrade. 5.0.3 changes no public
API and is binary and source compatible with 5.0.2.

## Upgrading

Change the version to 5.0.3:

```scala
mvn"dev.taihei::taihei-tsuji::5.0.3"        // Mill
"dev.taihei" %%% "taihei-tsuji" % "5.0.3"   // sbt, JVM and Scala.js
```

## Compatibility

Scala 3.9 (LTS), JVM (Java 17 or later) and Scala.js 1.x, as 5.0.2.

On the JVM, a language tag with parts the JDK has no locale data for is now formatted and named
with the nearest locale the JDK has data for. Formatting and naming are unchanged for every locale
the JDK has data for.
