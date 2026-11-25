# Release 6.0.4

**Release Date:** 2026-11-25

Taihei Tsuji 6.0.4 moves its JSON reader and writer, jsoniter-scala-core, from 2.42.0 to 2.43.0.
That version brings the upstream reader fixes of 2.42.2: a `java.time.Duration` written without
the `T` is read, building the hex dump of a parse error no longer throws an
`ArrayIndexOutOfBoundsException`, and a `BigDecimal` with a huge exponent no longer throws an
`ArithmeticException`. 6.0.4 changes no public API and is binary and source compatible with 6.0.3.

## Upgrading

Change the version to 6.0.4:

```scala
mvn"dev.taihei::taihei-tsuji::6.0.4"        // Mill
"dev.taihei" %%% "taihei-tsuji" % "6.0.4"   // sbt, JVM and Scala.js
```

A build that pins jsoniter-scala-core itself should pin 2.43.0 or later.

## Compatibility

Scala 3.9 (LTS), JVM (Java 17 or later) and Scala.js 1.x, as 6.0.3. The library's behaviour is
unchanged.
