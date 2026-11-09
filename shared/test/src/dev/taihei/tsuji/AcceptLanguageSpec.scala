package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.Language.*

/** Language.fromAcceptLanguage and Language.negotiate, on both platforms (since 6.1.0). */
object AcceptLanguageSpec extends ZIOSpecDefault:

  private def read(header: String): Vector[String] = fromAcceptLanguage(header).map(_.tag)

  private val supported = Seq(Dutch, French, English, Language("zh-Hant"), Language("pt-BR"))

  private def pick(header: String): Language =
    negotiate(fromAcceptLanguage(header), supported, English)

  def spec = suite("AcceptLanguageSpec")(
    suite("fromAcceptLanguage")(
      test("entries are ordered by weight, equal weights in the header's order") {
        assertTrue(
          read("da, en-GB;q=0.8, en;q=0.7") == Vector("da", "en-GB", "en"),
          read("en;q=0.5, nl, fr;q=0.9") == Vector("nl", "fr", "en"),
          read("de;q=0.5, fr;q=0.5, nl;q=0.500") == Vector("de", "fr", "nl"),
          read("fr-CH, fr;q=0.9, en;q=0.8, *;q=0.5") == Vector("fr-CH", "fr", "en"),
        )
      },
      test("tags are canonical and a repeat keeps its most preferred place") {
        assertTrue(
          read("NL-be,ZH-hant-tw") == Vector("nl-BE", "zh-Hant-TW"),
          read("nl;q=0.2, de, nl") == Vector("de", "nl"),
          read("nl, de;q=0.5, nl;q=0.1") == Vector("nl", "de"),
        )
      },
      test("an entry with q=0 is left out") {
        assertTrue(
          read("nl;q=0, fr") == Vector("fr"),
          read("nl;q=0.000, fr;Q=0.001") == Vector("fr"),
          read("nl;q=0") == Vector.empty,
        )
      },
      test("a * stands for the default, so what is weighted below it is left out") {
        assertTrue(
          read("*") == Vector.empty,
          read("nl, *;q=0.5, fr;q=0.3") == Vector("nl"),
          read("*;q=0, fr") == Vector("fr"),
          read("*, nl") == Vector("nl"),
          read("nl, *, fr") == Vector("nl", "fr"),
          read("*;q=0.5, nl;q=0.5, fr;q=0.4") == Vector("nl"),
        )
      },
      test("malformed entries are skipped and the rest are read") {
        assertTrue(
          read("nl;q=1.5, fr") == Vector("fr"),
          read("nl;q=abc, fr;q=.5, de;q=0.1234, it;q=-1, es") == Vector("es"),
          read("nl;level=1, fr;q=0.5;q=0.4, de;, it") == Vector("it"),
          read("en-, 12, invalid, und, und-US, x-private, en") == Vector("en"),
          read("n\u0000l, \u202efr, de") == Vector("de"),
          read("1;q=1.000, nl;q=1.0, fr ; q = 0.5") == Vector("nl", "fr"),
          read(" , ,, nl ,") == Vector("nl"),
        )
      },
      test("no header, an empty one and null read as no languages") {
        assertTrue(
          read("") == Vector.empty,
          read("   ") == Vector.empty,
          read(null) == Vector.empty,
        )
      },
      test("a long value is cut at the last whole entry within 4096 characters") {
        // 4095 characters of padding and a comma: the next entry starts past the limit.
        val padding = "x" * 4095
        val exact   = "nl," + "a" * 4092 + "," + "en-GB"
        assertTrue(
          exact.length == 4101,
          exact.lastIndexOf(',') == 4095,
          read(exact) == Vector("nl"),
          read("nl," + "a" * 4092 + ",fr") == Vector("nl"),
          read("nl," + padding + ",fr") == Vector("nl"),
          read(padding + "x,nl") == Vector.empty,
          read("nl," + "a" * 4090 + ",fr") == Vector("nl", "fr"),
          read("nl," + "a" * 4090 + ",fr,de") == Vector("nl", "fr"),
        )
      },
      test("only the first 32 entries are read") {
        val many = (0 until 40).map(i => s"de-${"%03d".format(i)}").mkString(",")
        assertTrue(
          read(many).size == 32,
          read(many).last == "de-031",
          read(("b4d," * 32) + "nl") == Vector.empty,
        )
      },
      test("an 8 KB header of many entries is read within the budget") {
        val entry  = "zh-Hant-TW-u-nu-hanidec-x-abcdefgh;q=0.5"
        val header = Iterator.continually(entry).take(8192 / (entry.length + 1) + 1).mkString(",")
        TimeBudget.within("8 KB Accept-Language", budgetMillis = 200) {
          (1 to 10).map(_ => fromAcceptLanguage(header)).last
        }(langs => assertTrue(header.length > 8192, langs == Vector(Language("zh-Hant-TW-u-nu-hanidec-x-abcdefgh"))))
      },
      test("an 8 KB header whose first entry fills the limit is read within the budget") {
        val header = ("a-" * 2047) + "a," + ("b" * 4096)
        TimeBudget.within("8 KB Accept-Language entry", budgetMillis = 200) {
          (1 to 10).map(_ => fromAcceptLanguage(header)).last
        }(langs => assertTrue(header.length == 8192, langs.isEmpty))
      },
    ),
    suite("negotiate")(
      test("the reader's first supported language wins") {
        assertTrue(
          pick("fr, nl;q=0.9") == French,
          pick("de, nl;q=0.9, fr;q=0.8") == Dutch,
          pick("nl;q=0.1, fr;q=0.2") == French,
        )
      },
      test("a region or a script falls back to the supported language it narrows") {
        assertTrue(
          pick("nl-BE") == Dutch,
          pick("fr-CA, en;q=0.9") == French,
          pick("zh-Hant-TW") == Language("zh-Hant"),
          pick("zh-Hant-HK-u-nu-hanidec") == Language("zh-Hant"),
          pick("pt-BR-x-custom") == Language("pt-BR"),
          pick("en-US-u-ca-buddhist") == English,
          // q=0 only drops `nl` from the requested list; `nl-BE` still walks down to it.
          pick("nl-BE, nl;q=0") == Dutch,
        )
      },
      test("a broader request does not choose a narrower supported language") {
        assertTrue(
          pick("pt, nl;q=0.5") == Dutch,
          pick("zh") == English,
          pick("zh-Hans-CN") == English,
        )
      },
      test("unsupported everything, nothing at all, or a * gives the default") {
        assertTrue(
          pick("de, it;q=0.9") == English,
          pick("") == English,
          pick("*") == English,
          pick("de, *;q=0.5, fr;q=0.4") == English,
          negotiate(Seq(Dutch), Nil, French) == French,
          negotiate(Nil, supported, Dutch) == Dutch,
        )
      },
      test("32 requested tags of the greatest length are negotiated within the budget") {
        // The costliest request a header can make: 32 distinct tags of 50 variants each, every
        // one walked down a subtag at a time, none supported.
        val requested = (0 until 32).map(i => Language("en" + "-1234" * 49 + f"-9$i%03d"))
        TimeBudget.within("negotiate 32 long tags", budgetMillis = 200) {
          (1 to 10).map(_ => negotiate(requested, Seq(Dutch), French)).last
        }(chosen => assertTrue(requested.forall(_.tag.length == 252), chosen == French))
      },
      test("the result agrees with the chain a registry walks to find a message") {
        val catalogues = Catalogues(Nil)
        val requested  = Seq(Language("zh-Hant-TW-x-a"), Language("nl-BE"), Language("fr-CA"))
        assertTrue(requested.forall(r =>
          catalogues.fallbackChain(
            r,
            English,
          ).find(supported.contains).contains(negotiate(Seq(r), supported, Language("de")))
        ))
      },
    ),
  )

end AcceptLanguageSpec
