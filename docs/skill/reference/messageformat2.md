# messageformat2 - ICU MessageFormat 2.0

Package `dev.taihei.tsuji`. The template language of every message, and a formatter you can call
on a raw template.

```scala
object MessageFormat2:
  def format(template: String, args: Map[String, Any] = Map.empty, locale: String = "en"): String
  def validate(template: String): List[TemplateError]

final case class TemplateError private[tsuji] (position: Int, message: String)  // read, not built
```

- `format` never throws; a variable without a value shows as `$name`; `locale` is a BCP 47 tag. A
  template whose text would pass a million characters is returned as written (a message falls back).
- Error messages escape anything but printable ASCII and are cut short, so they can be logged.
- `validate` reports syntax errors, unknown functions, a `.match` without a `*` variant, a variant
  with the wrong number of keys, and text after the pattern. A template over 64K characters is
  refused unparsed. Which variables exist is checked by a message definition, not here.
- The parser, AST and evaluation are internal.

## Placeholders and functions

| Syntax | Effect |
| --- | --- |
| `{$name}` | the variable; a `Language` shows as its name in the reader's language |
| `{$n :number}` | a number for the locale; `style=percent`, `style=currency` (USD) |
| `{$n :integer}` | rounded to a whole number |
| `{$d :datetime dateStyle=long timeStyle=short}` | a date and/or time; styles `short`, `medium`, `long`, `full`, any case, unknown gives `medium` |
| `{$d :date dateStyle=long}` | the date part (of a `LocalDate` or `LocalDateTime`) |
| `{$t :time}` | the time part (of a `LocalTime` or `LocalDateTime`) |
| `{$x :string}` | the value as text |

A bare `{name}` formats as a variable for compatibility, but `validate` refuses it: write `{$name}`
or a literal `{(text)}`.

## Declarations

| Syntax | Effect |
| --- | --- |
| `.input {$n :number}` | an argument, with a function applied |
| `.local $x = {$n :number}` | a local variable (also `.local {$x = (text)}` and `.let`) |

After declarations, a simple pattern ends at the line break.

## Matching

```
.match {$count :plural} when =0 {No messages} when one {One message} when * {{$count} messages}
```

- One or more `{selector}`s; variants `when <keys> {pattern}`, one key per selector, on one line
  or on several.
- A `when * {...}` variant (every key `*`) is required by `validate` and the macro.
- Plural keys: `one`, `other`, exact `=0`, `=1`, `2`, any number. French treats 0 and 1 as `one`;
  English and Dutch only 1. A key that is not a number in `Int` range matches nothing.
- A `.match` on a `Language` compares its tag (`when nl {...}`).
- Functions inside variant patterns format (`when * {{$n} on {$d :date}}`).

## Platform notes

On the JVM a long or full `timeStyle` on a value without a time zone formats as medium; Scala.js
prints `UTC`. On Scala.js a date after year 275760 or before year -271821 (outside the JavaScript
Date range) formats as its ISO text, such as `+300000-01-01`. Bare `en`, `nl`, `fr` format as US, NL and FR.
