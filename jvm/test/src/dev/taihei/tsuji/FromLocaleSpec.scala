package dev.taihei.tsuji

import java.util.Locale

import zio.test.*

import dev.taihei.tsuji.TestJson.{*, given}

// java.util.Locale exists on the JVM only. The locales are built with the Locale constructors,
// because Locale.of needs Java 19 and the jar targets Java 17. The tests compile against the Java
// 17 API too, where the constructors are not yet deprecated, so there is no warning to silence
// (a @nowarn here is itself reported as suppressing nothing).
object FromLocaleSpec extends ZIOSpecDefault:

  private val Thai     = new Locale("th", "TH", "TH")
  private val Japanese = new Locale("ja", "JP", "JP")

  // Every locale the acceptance criteria name.
  private val locales = Vector(
    new Locale("nl", "BE"),
    Locale.forLanguageTag("zh-Hant-TW"),
    Locale.ROOT,
    Locale.forLanguageTag("und"),
    new Locale("iw"),
    Thai,
    Japanese,
    Locale.ENGLISH,
  )

  private object Greetings extends MessageFamily("fromlocale"):
    val hello = message("Hello", Language("th") -> "Sawasdee", Language("ja") -> "Konnichiwa")

  def spec = suite("Language.fromLocale")(
    test("a locale with a region or a script gives its canonical tag") {
      assertTrue(
        Language.fromLocale(new Locale("nl", "BE")).map(_.tag) == Right("nl-BE"),
        Language.fromLocale(Locale.forLanguageTag("zh-Hant-TW")).map(_.tag) == Right("zh-Hant-TW"),
      )
    },
    test("a locale without a language is refused") {
      assertTrue(
        Language.fromLocale(Locale.ROOT).isLeft,
        Language.fromLocale(Locale.forLanguageTag("und")).isLeft,
        Language.fromLocale(new Locale("", "US")).isLeft,
      )
    },
    test("the JDK's legacy forms arrive as the JDK maps them, extensions kept") {
      assertTrue(
        Language.fromLocale(new Locale("iw")).map(_.tag) == Right("he"),
        Language.fromLocale(Thai).map(_.tag) == Right("th-TH-u-nu-thai-x-lvariant-th"),
        Language.fromLocale(Japanese).map(_.tag) == Right("ja-JP-u-ca-japanese-x-lvariant-jp"),
      )
    },
    test("the Thai and Japanese legacy locales keep their region and find the bare language's messages") {
      given Catalogues = Catalogues()
      val thai     = Language.fromLocale(Thai).toOption.get
      val japanese = Language.fromLocale(Japanese).toOption.get
      val chain    = summon[Catalogues].fallbackChain(thai, Language.English).map(_.tag)
      assertTrue(
        thai.region.contains("TH"),
        japanese.region.contains("JP"),
        chain == List("th-TH-u-nu-thai-x-lvariant-th", "th-TH", "th", "en"),
        Greetings.hello.localize(thai) == "Sawasdee",
        Greetings.hello.localize(japanese) == "Konnichiwa",
        Greetings.hello.localize(thai) == Greetings.hello.localize(Language("th")),
      )
    },
    test("a built-in is the built-in itself") {
      assertTrue(Language.fromLocale(Locale.ENGLISH).exists(_ eq Language.English))
    },
    test("agrees with parse on the tag of every locale, except that a locale without a language is refused") {
      val (none, withLanguage) = locales.partition(l => l.toLanguageTag == "und")
      assertTrue(
        withLanguage.size == 6,
        none.size == 2,
        none.forall(l => Language.fromLocale(l).isLeft && Language.parse(l.toLanguageTag).isRight),
        withLanguage.forall(l => Language.fromLocale(l) == Language.parse(l.toLanguageTag)),
      )
    },
    test("a language made from a locale keeps the JSON and field-key forms of its tag") {
      val flemish = Language.fromLocale(new Locale("nl", "BE")).toOption.get
      val english = Language.fromLocale(Locale.ENGLISH).toOption.get
      val map     = Map(flemish -> 1, english -> 2)
      assertTrue(
        flemish.toJson == "\"nl-BE\"",
        english.toJson == "\"English\"",
        flemish.toJson.fromJson[Language] == Right(flemish),
        map.toJson.fromJson[Map[Language, Int]] == Right(map),
      )
    },
    test("preferred is the default display locale on the JVM") {
      val saved = Locale.getDefault(Locale.Category.DISPLAY)
      try
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("nl-BE"))
        val nlBE = Language.preferred
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ROOT)
        val root = Language.preferred
        assertTrue(nlBE == Vector(Language("nl-BE")), root.isEmpty)
      finally Locale.setDefault(Locale.Category.DISPLAY, saved)
    },
    // One test sets the JVM-wide default display locale: the tests do not run side by side.
  ) @@ TestAspect.sequential

end FromLocaleSpec
