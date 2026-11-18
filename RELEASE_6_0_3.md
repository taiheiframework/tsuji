# Release 6.0.3

**Release Date:** 2026-11-18

Taihei Tsuji 6.0.3 is a security release: it bounds the work that formatting one message may do,
whatever its templates and arguments hold. Everyone on 6.0.0 or 6.0.2 should upgrade.
6.0.3 changes no public API and is binary and source compatible with 6.0.2.

## Upgrading

Change the version to 6.0.3:

```scala
mvn"dev.taihei::taihei-tsuji::6.0.3"        // Mill
"dev.taihei" %%% "taihei-tsuji" % "6.0.3"   // sbt, JVM and Scala.js
```

## Compatibility

Scala 3.9 (LTS), JVM (Java 17 or later) and Scala.js 1.x, as 6.0.2.

One message, with its nested messages, keeps its budget of four million characters, and the budget
now also pays for the work a template does without writing text: each declaration, placeholder,
selector, variant and key, and each number, date, time or language name formatted. A message past
its budget shows its own template, as before. A list of 10000 nested messages that format two
numbers each still fits; a message whose nested messages together format more than about 30000
values now shows its template.

Load catalogue files only from sources your application trusts, and bound the size of the message
JSON you accept from outside.

A date or time style other than `short`, `medium`, `long` or `full` is written as `medium`, as
before, and is no longer read. `:integer` reads its value as `:number` does, up to 1100 characters.
A function applied to a value that `:plural` produced now gets the value itself, so
`{$p :integer}` on such a value shows the number. `MessageFormat2.format` reads the first 255
characters of its locale.
