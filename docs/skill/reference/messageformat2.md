# messageformat2 - ICU MessageFormat 2.0

Package `dev.taihei.tsuji`. The template language of every message, and a formatter you can call
on a raw template.

```scala
object MessageFormat2:
  def format(template: String, args: Map[String, Any] = Map.empty, language: Language = Language.English): String
  def validate(template: String): List[TemplateError]
  def warnings(template: String, language: Language): List[TemplateError]  // since 7.0.0

final case class TemplateError private[tsuji] (position: Int, message: String)  // read, not built
```

- `format` never throws; a variable without a value shows as `$name`; `language` is the reader's (a `String` tag up to 6.x). A
  template whose text would pass a million characters, or that holds a whole number of more than
  ten thousand digits, is returned as written (a message falls back).
- Error messages escape anything but printable ASCII and are cut short, so they can be logged.
- `validate` reports syntax errors, unknown functions, a `.match` without a `*` variant, a variant
  with the wrong number of keys, and text after the pattern. A template over 64K characters is
  refused unparsed. Which variables exist is checked by a message definition, not here.
- `warnings` names a `:plural` key that `language` never selects (`few` in English) or that is no
  category and no number (`ones`). It refuses nothing: such a template formats, and `validate`
  and the macro accept it. `Catalogues.verify` reports these too.
- The parser, AST and evaluation are internal.

## Placeholders and functions

| Syntax | Effect |
| --- | --- |
| `{$name}` | the variable; a `Language` shows as its name in the reader's language, a number as `:number` writes it, a date or time as `:datetime` writes it; text stays text |
| `{$n :number}` | grouped digits and up to three decimals as the reader's language writes them, every digit kept (`Long`, `BigInt`, `BigDecimal`); options `minimumFractionDigits`, `maximumFractionDigits` (0 to 20), `useGrouping=never`, `style=percent`, `style=currency currency=EUR` (the code is required) |
| `{$n :integer}` | rounded to a whole number (a half away from zero), grouped; `useGrouping` is its one option, `style`, `currency` and the fraction options are refused |
| `{$d :datetime dateStyle=long timeStyle=short}` | a date and/or time; styles `short`, `medium`, `long`, `full`, any case, unknown gives `medium` |
| `{$d :date dateStyle=long}` | the date part (of a `LocalDate` or `LocalDateTime`) |
| `{$t :time}` | the time part (of a `LocalTime` or `LocalDateTime`) |
| `{$x :string}` | the value as text, unformatted: a number as its digits (an order number, a year) |

A bare `{name}` formats as a variable for compatibility, but `validate` refuses it: write `{$name}`
or a literal `{(text)}`.

## Declarations

| Syntax | Effect |
| --- | --- |
| `.input {$n :number}` | an argument, with a function applied: a placeholder shows its text, a later `.match` or `:plural` selects by the number as written (`:integer` of 0.6 is 1), a later function gets the value |
| `.local $x = {$n :number}` | a local variable (also `.local {$x = (text)}` and `.let`) |

After declarations, a simple pattern ends at the line break.

## Matching

```
.match {$count :plural} when =0 {No messages} when one {One message} when * {{$count} messages}
```

- One or more `{selector}`s; variants `when <keys> {pattern}`, one key per selector, on one line
  or on several.
- A `when * {...}` variant (every key `*`) is required by `validate` and the macro.
- Plural keys: CLDR's categories `zero`, `one`, `two`, `few`, `many`, `other`, and exact `=0`,
  `=1`, `2` (a whole number in `Int` range; `=1` matches 1 and 1.0, not 1.5). Since 7.0.0 the
  category is CLDR's (CLDR 49) for every language, the same on the JVM and Scala.js: Polish 3 is
  `few`, 5 `many`; Arabic 0 is `zero`; 1.5 is `other` in English and `one` in French.
- Variants are tried in template order: an exact key before a category wins. `other` and `*`
  match any value; write them last.
- A tag falls back by subtags (`pt-PT` has its own rules, `pt-BR` takes `pt`); a language CLDR
  does not know takes root, where every number is `other`.
- In a message shown through the fallback chain, the rules are the template's language's (an
  English fallback says "21 items" to a Russian reader); numbers are formatted for the reader. A
  template found by cutting subtags off the reader's tag keeps the reader's regional rules (a
  `pt-PT` reader of the `pt` template has `pt-PT`'s).
- A whole `Double` or `Float` is read as its integer (1.0 is 1), any other as its shortest digits
  (1.1f is 1.1), on both platforms; on Scala.js a `Double` with a fraction that is exactly a
  `Float` value (a widened `Float`) reads as that `Float`. A text or `BigDecimal` keeps its
  fraction digits (`"1.0"` is `other` in English); a negative number takes its absolute value's category; a text over 1100
  characters is no number (only `other` and `*` match), and so is every `:plural` selector past
  the 64th of one `.match`.
- A `.match` on a `Language` compares its tag (`when nl {...}`).
- Functions inside variant patterns format (`when * {{$n} on {$d :date}}`).

## Platform notes

On the JVM a long or full `timeStyle` on a value without a time zone formats as medium; Scala.js
prints `UTC`. On Scala.js a date after year 275760 or before year -271821 (outside the JavaScript
Date range) formats as its ISO text, such as `+300000-01-01`. Bare `en`, `nl`, `fr` format as US, NL and FR.
