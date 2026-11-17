# Release 4.0.3

**Release Date:** 2026-11-17

taihei-i18n 4.0.3 is a security release with two security fixes; the details are in the advisories
that follow this release. Everyone on taihei-i18n 4.x should upgrade. 4.0.3 changes no public API
and is binary and source compatible with 4.0.2.

## Upgrading

Change the version to 4.0.3. No code changes are needed:

```scala
mvn"dev.taihei::taihei-i18n:4.0.3"       // Mill, JVM
mvn"dev.taihei::taihei-i18n::4.0.3"      // Mill, Scala.js
"dev.taihei" %%% "taihei-i18n" % "4.0.3" // sbt
```

## Compatibility

Scala 3.9 (LTS), on the JVM and on Scala.js 1.x, as 4.0.2.

On the JVM, `PlatformLocales.javaLocale` now returns a locale the JDK has locale data for, so a
tag with extensions, variants or private use the JDK has no data for, or a language without JDK
data, maps to a less specific locale. `PlatformLanguageNames.displayName` names only the calendar
and digits extensions. Formatting and naming through the library are unchanged for every locale
the JDK has data for.
