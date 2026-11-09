package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.Language.*

object PluralRulesSpec extends ZIOSpecDefault:

  // Every category, in a template that names them all; `*` catches what none of them takes.
  private val all =
    ".match {$n :plural} when zero {zero} when one {one} when two {two} when few {few} when many {many} " +
    "when * {other}"

  private def cat(n: Any, locale: String): String = MessageFormat2.format(all, Map("n" -> n), locale)

  // A CLDR sample: a plain decimal, or a compact one such as 1.1c6, which is 1100000 with e = 6.
  private def sample(text: String): Operands =
    text.split('c').toList match
      case List(m)    => Operands.parse(m).get
      case List(m, e) =>
        val (int, frac) = m.span(_ != '.')
        val digits      = frac.drop(1)
        val shift       = e.toInt
        val rest        = digits.drop(shift)
        val plain       = int + digits.take(shift).padTo(shift, '0') + (if rest.isEmpty then "" else "." + rest)
        Operands.parse(plain).get.copy(e = shift)
      case _          => sys.error(s"not a sample: $text")

  // CLDR's sample lists: values, and ranges such as 2~16 or 0.0~1.5, stepped at their precision.
  private def expand(list: String): List[String] =
    list.split(", ").toList.map(_.trim).filter(s => s.nonEmpty && s != "…").flatMap: s =>
      s.split('~').toList match
        case List(one)      => List(one)
        case List(from, to) =>
          val scale = from.dropWhile(_ != '.').drop(1).length
          val a     = BigDecimal(from)
          val b     = BigDecimal(to)
          val step  = BigDecimal(1) / BigDecimal(10).pow(scale)
          Iterator.iterate(a)(_ + step).takeWhile(_ <= b).map(_.setScale(scale).toString).toList
        case _              => sys.error(s"not a range: $s")

  private val old6Locales = List("en", "nl", "fr", "en-US", "nl-BE", "fr-CA")

  // 6.0's rule, which en, nl and fr keep for every whole number that is not negative.
  private def old6(n: Int, locale: String): String =
    locale.take(2) match
      case "fr" => if n == 0 || n == 1 then "one" else "other"
      case _    => if n == 1 then "one" else "other"

  def spec = suite("CLDR plural rules")(
    suite("the table")(
      test("comes from one CLDR release, read on both platforms") {
        assertTrue(CldrPlurals.Version == CldrPluralSamples.Version, CldrPlurals.RuleSets.size > 30)
      },
      test("every relation of every rule parses") {
        val relations =
          for
            (_, rules)  <- CldrPlurals.RuleSets
            (_, text)   <- rules
            conjunction <- text.split(" or ").toList
            relation    <- conjunction.split(" and ").toList
          yield relation
        val unread    = relations.filter(r => PluralRules.relation(r).isEmpty)
        assertTrue(relations.size > 100, unread.isEmpty)
      },
      test("every locale gives CLDR's category for each of CLDR's samples") {
        val checked  =
          for
            (locales, category, integers, decimals) <- CldrPluralSamples.Samples
            locale                                  <- locales.split(' ').toList
            tag                                     <- List(locale, locale.replace('_', '-'))
            value                                   <- expand(integers) ++ expand(decimals)
          yield (tag, value, category, PluralRules.category(tag, sample(value)))
        val wrong    = checked.filter(
          (
            _,
            _,
            expected,
            got,
          ) => expected != got
        )
        val locales  = checked.map(_._1).distinct
        val integers = checked.count(
          (
            _,
            v,
            _,
            _,
          ) => !v.contains('.')
        )
        assertTrue(locales.size > 200, checked.size > 20000, integers > 5000, wrong.take(10).isEmpty)
      },
      test("every locale has the categories its samples name, and other") {
        val wrong = CldrPluralSamples.Samples.groupBy(_._1).toList.flatMap: (locales, rules) =>
          val expected = rules.map(_._2).toSet
          locales.split(' ').toList.filter(l => PluralRules.categories(l).toSet != expected)
        assertTrue(wrong.isEmpty, PluralRules.categories("pl") == List("one", "few", "many", "other"))
      },
      test("a relation that is no CLDR relation is not read") {
        assertTrue(
          PluralRules.relation("n = 1").isDefined,
          PluralRules.relation("n % 0 = 1").isEmpty,
          PluralRules.relation("x = 1").isEmpty,
          PluralRules.relation("n < 1").isEmpty,
          PluralRules.relation("n = 1..").isEmpty,
          PluralRules.relation("").isEmpty,
        )
      },
    ),
    suite(":plural selects CLDR's category")(
      test("Polish") {
        assertTrue(
          List(1, 2, 4, 5, 12, 14, 21, 22, 25, 0, 112, 122).map(cat(_, "pl")) ==
          List("one", "few", "few", "many", "many", "many", "many", "few", "many", "many", "many", "few"),
          cat(1.5, "pl") == "other",
        )
      },
      test("the README's Polish example") {
        val files = ".match {$n :plural} when one {{$n} plik} when few {{$n} pliki} when many {{$n} plików} " +
          "when * {{$n} pliku}"
        assertTrue(
          List[Any](1, 3, 5, 1.5).map(n => MessageFormat2.format(files, Map("n" -> n), "pl")) ==
          List("1 plik", "3 pliki", "5 plików", "1.5 pliku")
        )
      },
      test("Russian and Ukrainian") {
        assertTrue(
          List(1, 21, 101, 2, 3, 24, 5, 11, 12, 111, 0).map(cat(_, "ru")) ==
          List("one", "one", "one", "few", "few", "few", "many", "many", "many", "many", "many"),
          cat("1.5", "ru") == "other",
          cat(21, "uk") == "one",
        )
      },
      test("Arabic") {
        assertTrue(
          List(0, 1, 2, 3, 10, 103, 11, 99, 111, 100, 102, 1000).map(cat(_, "ar")) ==
          List("zero", "one", "two", "few", "few", "few", "many", "many", "many", "other", "other", "other"),
          cat(0.5, "ar") == "other",
        )
      },
      test("Welsh") {
        assertTrue(
          List(0, 1, 2, 3, 4, 5, 6, 7, 100).map(cat(_, "cy")) ==
          List("zero", "one", "two", "few", "other", "other", "many", "other", "other")
        )
      },
      test("Czech and Slovak") {
        assertTrue(
          List(1, 2, 3, 4, 5, 0, 11, 21).map(cat(_, "cs")) ==
          List("one", "few", "few", "few", "other", "other", "other", "other"),
          cat(1.5, "cs") == "many",
          cat("2.0", "sk") == "many",
        )
      },
      test("Slovenian") {
        assertTrue(
          List(1, 101, 2, 102, 3, 4, 103, 5, 11, 0).map(cat(_, "sl")) ==
          List("one", "one", "two", "two", "few", "few", "few", "other", "other", "other"),
          cat(1.5, "sl") == "few",
        )
      },
      test("decimals count their visible fraction digits") {
        assertTrue(
          cat(1.5, "en") == "other",
          cat(BigDecimal("1.5"), "en") == "other",
          cat("1.0", "en") == "other",
          cat(BigDecimal("1.0"), "en") == "other",
          cat(1.0, "en") == "one",   // a Double has no scale: 1.0 is 1
          cat(1.0f, "en") == "one",
          cat(1.5, "fr") == "one",   // French: i = 0..1
          cat(0.5, "fr") == "one",
          cat(2.5, "fr") == "other",
          cat("1.0", "es") == "one", // Spanish: n = 1
          cat("0.1", "da") == "one", // Danish: t != 0 and i = 0,1
        )
      },
      test("a Float and a Double are read as their shortest digits, the same on both platforms") {
        def digits(v: Any) = Operands.of(v).map(o => (o.i, o.v, o.f))
        assertTrue(
          cat(1.1f, "hr") == "one", // f % 10 = 1
          cat(2.3f, "hr") == "few",
          cat(0.1f, "lv") == "one",
          digits(1.1f).contains((BigInt(1), 1, BigInt(1))),
          digits(0.3f).contains((BigInt(0), 1, BigInt(3))),
          digits(2.0e-3).contains((BigInt(0), 3, BigInt(2))),
          digits(1.0e23).contains((BigInt("100000000000000000000000"), 0, BigInt(0))),
          digits(0.1 + 0.2).contains((BigInt(0), 17, BigInt("30000000000000004"))),
          digits(-0.0).contains((BigInt(0), 0, BigInt(0))),
          digits(Float.NaN).isEmpty,
        )
      },
      test("negative numbers take the category of their absolute value, as CLDR's n") {
        assertTrue(cat(-1, "en") == "one", cat(-2, "pl") == "few", cat(-5, "pl") == "many")
      },
      test("en, nl and fr keep 6.0's choice for every whole number that is not negative") {
        val numbers = (0 to 2000).toList ++ List(10000, 100000, 1000000, 2000000, Int.MaxValue)
        val keys    = ".match {$n :plural} when one {one} when * {other}"
        val wrong   =
          for
            locale <- old6Locales
            n      <- numbers
            if MessageFormat2.format(keys, Map("n" -> n), locale) != old6(n, locale)
          yield s"$locale $n"
        assertTrue(wrong.isEmpty)
      },
      test("French and Spanish say many for a million only when the template has the key") {
        val keys = ".match {$n :plural} when one {one} when other {other} when * {star}"
        assertTrue(
          cat(1000000, "fr") == "many",
          cat(1000001, "fr") == "other",
          cat(2000000, "es") == "many",
          MessageFormat2.format(keys, Map("n" -> 1000000), "fr") == "other",
        )
      },
    ),
    suite("a message shown through the fallback chain")(
      test("takes the plural rules of the template's language, and formats for the reader") {
        object Items extends MessageFamily("items"):
          val count = message[(n: Int)](
            ".match {$n :plural} when one {{$n} item} when * {{$n} items}",
            Language("pl") -> ".match {$n :plural} when one {{$n} rzecz} when few {{$n} rzeczy} when * {{$n} rzeczy!}",
          )
        given Catalogues = Catalogues()
        assertTrue(
          Items.count(21).localize(Language("ru")) == "21 items",
          Items.count(5).localize(Language("fil")) == "5 items",
          Items.count(0).localize(Language("hi")) == "0 items",
          Items.count(1).localize(Language("ru")) == "1 item",
          Items.count(3).localize(Language("pl-PL")) == "3 rzeczy",
          Items.count(1).localize(Language("pl")) == "1 rzecz",
        )
      }
    ),
    suite("languages fall back as Tsuji's do")(
      test("a region or script falls back to its language, and pt-PT has its own rules") {
        assertTrue(
          cat(1, "en-GB") == "one",
          cat(5, "pl-PL") == "many",
          cat(0, "pt-BR") == "one",
          cat(0, "pt") == "one",
          cat(0, "pt-PT") == "other",
          cat(0, "pt_PT") == "other",
          cat(0, "fr-CA") == "one",
          cat(2, "sr-Latn-RS") == "few",
          cat(5, "pl-u-nu-latn") == "many",
          cat(1, "EN") == "one",
        )
      },
      test("a language CLDR has no rules for takes root's, where every number is other") {
        assertTrue(
          cat(1, "zh") == "other",
          cat(1, "ja-JP") == "other",
          cat(1, "xx") == "other",
          cat(1, "") == "other",
          cat(1, "-") == "other",
          PluralRules.categories("qaa") == List("other"),
        )
      },
    ),
    suite("exact keys")(
      test("keep their meaning and the template's order") {
        val exactFirst = ".match {$n :plural} when =1 {exact} when one {one} when * {other}"
        val oneFirst   = ".match {$n :plural} when one {one} when =1 {exact} when * {other}"
        val bare       = ".match {$n :plural} when 0 {zero} when =2 {two} when * {other}"
        assertTrue(
          MessageFormat2.format(exactFirst, Map("n" -> 1), "en") == "exact",
          MessageFormat2.format(oneFirst, Map("n" -> 1), "en") == "one",
          MessageFormat2.format(exactFirst, Map("n" -> 1.0), "en") == "exact",
          MessageFormat2.format(exactFirst, Map("n" -> "1.0"), "en") == "exact",
          MessageFormat2.format(bare, Map("n" -> 0), "pl") == "zero",
          MessageFormat2.format(bare, Map("n" -> 2), "pl") == "two",
          MessageFormat2.format(bare, Map("n" -> -2), "pl") == "other",
          MessageFormat2.format(bare, Map("n" -> 1), "en") == "other",
        )
      },
      test("a decimal is not the whole number it starts with") {
        val exact = ".match {$n :plural} when =1 {exact} when 2 {two} when * {other}"
        assertTrue(
          MessageFormat2.format(exact, Map("n" -> 1.5), "en") == "other",
          MessageFormat2.format(exact, Map("n" -> "2.5"), "en") == "other",
        )
      },
      test("a value that is no number takes only other and *") {
        assertTrue(
          cat("many", "pl") == "other",
          cat(Double.NaN, "en") == "other",
          cat(Double.PositiveInfinity, "pl") == "other",
          MessageFormat2.format(".match {$n :plural} when other {o} when * {s}", Map("n" -> "x")) == "o",
        )
      },
    ),
    suite("warnings")(
      test("name a category key the language never selects") {
        val t = ".match {$n :plural} when one {one} when few {few} when * {other}"
        val w = MessageFormat2.warnings(t, English)
        assertTrue(
          w.map(_.message) == List("the plural key few never matches in en, whose categories are one, other"),
          w.head.position == t.indexOf("when few"),
          MessageFormat2.warnings(t, Language("pl")).isEmpty,
          MessageFormat2.warnings(t, Language("en-GB")).size == 1,
          MessageFormat2.warnings(t, Language("ja")).map(_.message).exists(_.contains("one never matches")),
        )
      },
      test("name a key that is no category and no number in any language") {
        val t = ".match {$n :plural} when ones {a} when =abc {b} when 99999999999 {c} when * {d}"
        val w = MessageFormat2.warnings(t, Language("pl")).map(_.message)
        assertTrue(
          w.size == 3,
          w.head.contains("ones is no plural category"),
          w(1).contains("=abc is no exact number key"),
          w(2).contains("99999999999 is no exact number key"),
        )
      },
      test("pass every key a number can match, and the keys of other selectors") {
        val ok = List(
          ".match {$n :plural} when =0 {a} when 1 {b} when =2 {c} when one {d} when other {e} when * {f}",
          ".match {$g} {$n :plural} when few one {a} when * * {b}",
          ".input {$n :plural}\n.match {$n} when one {a} when * {b}",
          "Hello {$name}",
          ".match {$n :plural",
        )
        assertTrue(ok.forall(t => MessageFormat2.warnings(t, English).isEmpty))
      },
      test("see a selector declared :plural, and stop at declarations that name each other") {
        val t      = ".input {$n :plural}\n.match {$n} when few {a} when * {b}"
        val circle = ".local $a = {$b}\n.local $b = {$a}\n.match {$a} when few {a} when * {b}"
        assertTrue(MessageFormat2.warnings(t, English).size == 1, MessageFormat2.warnings(circle, English).isEmpty)
      },
      test("refuse nothing validate and check accepted in 6.0") {
        val t = ".match {$n :plural} when one {one} when few {few} when * {other}"
        assertTrue(
          MessageFormat2.validate(t).isEmpty,
          MessageFormat2.check(t, Map("n" -> ArgKind.Number))._1.isEmpty,
          MessageFormat2.format(t, Map("n" -> 3), "en") == "other",
        )
      },
      test("are reported by Catalogues.verify, in the template's language") {
        object Few extends MessageFamily("few"):
          val items = message[(n: Int)](
            ".match {$n :plural} when one {one item} when few {a few items} when * {{$n} items}",
            Language("pl") -> ".match {$n :plural} when one {a} when few {b} when many {c} when * {d}",
            Dutch -> ".match {$n :plural} when one {a} when many {b} when * {c}",
          )
        val de       = Catalogue(Language("de"), Map(Few.items.key -> ".match {$n :plural} when two {a} when * {b}"))
        val problems = Catalogues.verify(Seq(Few), Seq(de))
        assertTrue(
          problems.map(p => (p.language.map(_.tag), p.message.takeWhile(_ != ','))).toSet == Set(
            Some("en") -> "the plural key few never matches in en",
            Some("nl") -> "the plural key many never matches in nl",
            Some("de") -> "the plural key two never matches in de",
          ),
          problems.size == 3,
          problems.forall(_.key.contains(Few.items.key)),
        )
      },
    ),
    suite("bounds")(
      test("a number is read up to 1100 characters, and past them is no number") {
        val ones = "1" * 1100
        assertTrue(
          cat(ones, "en") == "other",
          cat("0." + "0" * 1097 + "1", "da") == "one",
          cat("1" + "0" * 1100, "en") == "other",
          Operands.of("1" * 1101).isEmpty,
          Operands.of("1" * 1100).isDefined,
          cat("1" + "0" * 1100, "fr") == "other", // no number: not many
          cat(BigDecimal("1E+1000000000"), "fr") == "other",
          cat(BigDecimal("1E-1000000000"), "en") == "other",
          Operands.of(BigDecimal("1E+1000000000")).isEmpty,
          Operands.of(BigInt(2).pow(100000)).isEmpty,
          Operands.of(Double.MaxValue).isDefined,
          Operands.of(Double.MinPositiveValue).isDefined,
        )
      },
      test("selection over long and very long values stays within its time") {
        val long     = (1 to 200).toList.map(k => "9" * 1000 + "." + k.toString * 3)
        val tooLong  = List.fill(20)("1" * 1000000)
        val template = all
        TimeBudget.within("plural selection of 220 long values", 2000)(
          (long ++ tooLong).map(v => MessageFormat2.format(template, Map("n" -> v), "pl"))
        )(results => assertTrue(results.size == 220, results.forall(_ == "other")))
      },
    ),
  )

end PluralRulesSpec
