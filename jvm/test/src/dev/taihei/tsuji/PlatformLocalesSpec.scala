package dev.taihei.tsuji

import java.text.{DateFormat, NumberFormat}
import java.time.{LocalDate, LocalDateTime}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.util.Locale

import scala.util.Random

import zio.test.*

// java.util.Locale is the JVM side only; Scala.js maps tags to Intl locales instead.
object PlatformLocalesSpec extends ZIOSpecDefault:

  private val Letters = "abcdefghijklmnopqrstuvwxyz"

  private def word(
    random: Random,
    min: Int,
    max: Int,
  ): String =
    List.fill(min + random.nextInt(max - min + 1))(Letters(random.nextInt(Letters.length))).mkString

  // Well-formed tags of every shape a reader could send: known and unknown languages, scripts,
  // regions, variants, private use and Unicode extensions, numbering systems among them.
  private[tsuji] def tags(count: Int, seed: Long): List[String] =
    val random = Random(seed)
    val known  = Locale.getISOLanguages.toVector
    List.fill(count) {
      val language = if random.nextBoolean() then known(random.nextInt(known.size)) else word(random, 2, 3)
      val parts    = List(
        Option.when(random.nextInt(3) == 0)(word(random, 4, 4).capitalize),
        Option.when(random.nextBoolean())(word(random, 2, 2).toUpperCase),
        Option.when(random.nextInt(4) == 0)(word(random, 5, 8)),
        Option.when(random.nextInt(4) == 0)("u-nu-" + word(random, 3, 8)),
        Option.when(random.nextInt(4) == 0)("x-" + word(random, 1, 8)),
      ).flatten
      (language :: parts).mkString("-")
    }

  // The name the JDK gives the tag as written, kept as displayName keeps it.
  private def byTheJdk(tag: String, reader: String): Option[String] =
    val locale = Locale.forLanguageTag(tag)
    Option(locale.getDisplayName(PlatformLocales.javaLocale(reader)))
      .filter(n => n.nonEmpty && n != tag && n != locale.getLanguage)

  // The tags whose format differs from the JDK's for the tag as written.
  private def sameAsWritten(written: List[String]): List[String] =
    written.filter(t => output(PlatformLocales.javaLocale(t)) != output(Locale.forLanguageTag(t)))

  private def output(locale: Locale): List[String] =
    val date = LocalDate.of(2026, 3, 29)
    val time = LocalDateTime.of(2026, 3, 29, 2, 30)
    List(
      NumberFormat.getInstance(locale).format(1234567.25),
      NumberFormat.getPercentInstance(locale).format(0.25),
      DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date),
      DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date),
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(time),
    )

  def spec = suite("PlatformLocales on the JVM")(
    test("a bare nl is Dutch as written in the Netherlands, built from its tag") {
      val nl = PlatformLocales.javaLocale("nl")
      assertTrue(
        nl.getLanguage == "nl",
        nl.getCountry == "NL",
        nl.toLanguageTag == "nl-NL",
        nl == Locale.forLanguageTag("nl-NL"),
      )
    },
    test("a tag with a region the JDK knows keeps it") {
      val be = PlatformLocales.javaLocale("nl-BE")
      assertTrue(be.getLanguage == "nl", be.getCountry == "BE", be == Locale.forLanguageTag("nl-BE"))
    },
    test("the bare tags keep the regions they always had") {
      assertTrue(
        PlatformLocales.javaLocale("en") == Locale.US,
        PlatformLocales.javaLocale("fr") == Locale.FRANCE,
      )
    },
    test("whatever tag a reader sends, the locale is one of a fixed set") {
      val possible = PlatformLocales.possible
      val mapped   = tags(100000, 204L).map(PlatformLocales.javaLocale)
      val outside  = mapped.filterNot(possible.contains)
      assertTrue(possible.size < 4000, mapped.size == 100000, outside.isEmpty)
    },
    test("a tag maps to the most specific locale the JDK has data for") {
      // Belarusian in the Taraškievica orthography has its own data from JDK 21 on.
      val tarask    = Locale.forLanguageTag("be-TARASK")
      val available = (DateFormat.getAvailableLocales.iterator ++ NumberFormat.getAvailableLocales.iterator).toSet
      assertTrue(
        PlatformLocales.javaLocale("de-XY") == Locale.forLanguageTag("de"),
        PlatformLocales.javaLocale("sr-Latn-XY") == Locale.forLanguageTag("sr-Latn"),
        PlatformLocales.javaLocale("be-TARASK") == (if available(tarask) then tarask else Locale.forLanguageTag("be")),
        PlatformLocales.javaLocale("nl-BE-x-private") == Locale.forLanguageTag("nl-BE"),
        PlatformLocales.javaLocale("de-CH-u-rg-atzzzz") == Locale.forLanguageTag("de-AT"),
        PlatformLocales.javaLocale("xyz-AB") == Locale.ROOT,
        PlatformLocales.javaLocale("x-only-private") == Locale.US,
      )
    },
    test("a script the JDK has no data for in that language gives the language without it, on every JDK") {
      assertTrue(
        PlatformLocales.javaLocale("de-Cyrl") == Locale.forLanguageTag("de"),
        PlatformLocales.javaLocale("de-Zzzz-AT") == Locale.forLanguageTag("de-AT"),
        PlatformLocales.javaLocale("en-Latn-GB") == Locale.forLanguageTag("en-GB"),
        PlatformLocales.javaLocale("hi-Deva-XY") == Locale.forLanguageTag("hi"),
        PlatformLocales.javaLocale("pt-Zzzz-BR") == Locale.forLanguageTag("pt-BR"),
      )
    },
    test("the format is the same as from the tag as written, for every locale the JDK has data for") {
      val available = (DateFormat.getAvailableLocales.iterator ++ NumberFormat.getAvailableLocales.iterator).toSet
      val written   = available.toList.map(_.toLanguageTag).filterNot(Set("en", "nl", "fr", "und"))
      val suffixes  = List("", "-x-private", "-u-ca-gregory")
      val different = sameAsWritten(for
        tag    <- written
        suffix <- suffixes
      yield s"$tag$suffix")
      assertTrue(written.size > 1000, different.isEmpty)
    },
    test("every script the JDK has for a language, with and without a region, formats as it did") {
      val available = (DateFormat.getAvailableLocales.iterator ++ NumberFormat.getAvailableLocales.iterator).toList
      val languages = available.map(_.getLanguage).filter(_.nonEmpty).distinct
      val regions   = available.groupMap(_.getLanguage)(_.getCountry).view.mapValues(_.filter(_.nonEmpty).take(2)).toMap
      val scripts   = available.groupMap(_.getLanguage)(_.getScript).view.mapValues(_.filter(_.nonEmpty).distinct).toMap
      val written   =
        for
          language <- languages
          script   <- "" :: scripts.getOrElse(language, Nil)
          region   <- "" :: "XY" :: regions.getOrElse(language, Nil)
          tag = List(language, script, region).filter(_.nonEmpty).mkString("-")
          if !Set("en", "nl", "fr").contains(tag)
        yield tag
      val different = sameAsWritten(written.distinct)
      assertTrue(written.size > 1000, different.isEmpty)
    },
    test("tags a reader's browser or platform sends format as they did") {
      val different = sameAsWritten(List(
        "de-XY",
        "en-XY",
        "en-GB",
        "en-IN",
        "zh-TW",
        "zh-HK",
        "zh-Hans-CN",
        "zh-Hant-TW",
        "sr-Latn-RS",
        "az-Latn-AZ",
        "ca-ES-valencia",
        "pt-BR",
        "es-419",
        "xyz",
        "xyz-AB",
        "fil-PH-x-a",
        "be-tarask",
        "th-TH-u-nu-thai-x-lvariant-th",
        "ja-JP-u-ca-japanese-x-lvariant-jp",
        "de-CH-u-rg-dezzzz",
      ))
      assertTrue(different.isEmpty)
    },
    test("random tags without a script format as they did") {
      val random    = Random(2042L)
      val known     = (DateFormat.getAvailableLocales.iterator ++ NumberFormat.getAvailableLocales.iterator)
        .map(_.getLanguage)
        .filter(_.nonEmpty)
        .toVector
        .distinct
      val regions   = Locale.getISOCountries.toVector
      val written   = List.fill(3000) {
        val language = known(random.nextInt(known.size))
        val parts    = List(
          Option.when(random.nextBoolean())(regions(random.nextInt(regions.size))),
          Option.when(random.nextInt(4) == 0)(word(random, 5, 8)),
          Option.when(random.nextInt(4) == 0)("x-" + word(random, 1, 8)),
        ).flatten
        (language :: parts).mkString("-")
      }
      val different = sameAsWritten(written.filterNot(Set("en", "nl", "fr")))
      assertTrue(different.isEmpty)
    },
    test("Latin digits and a language's own digits are kept; other numbering systems are not") {
      val thai = PlatformLocales.javaLocale("th-TH-u-nu-thai-x-lvariant-th")
      assertTrue(
        thai == Locale.forLanguageTag("th-TH-u-nu-thai"),
        NumberFormat.getInstance(thai).format(12) == "๑๒",
        PlatformLocales.javaLocale("hi-IN-u-nu-deva") == Locale.forLanguageTag("hi-IN-u-nu-deva"),
        PlatformLocales.javaLocale("fa-IR-u-nu-latn") == Locale.forLanguageTag("fa-IR-u-nu-latn"),
        NumberFormat.getInstance(PlatformLocales.javaLocale("fa-IR-u-nu-latn")).format(12) == "12",
        PlatformLocales.javaLocale("de-DE-u-nu-thai") == Locale.forLanguageTag("de-DE"),
        PlatformLocales.javaLocale("xyz-u-nu-latn") == Locale.ROOT,
      )
    },
    test("a tag reads into the subtags the JDK reads from it") {
      val edge      = List(
        "en-US",
        "iw-IL",
        "in",
        "ji",
        "und",
        "und-US",
        "zh-yue-HK",
        "zh-cmn-Hans-CN",
        "sr-Latn-RS",
        "es-419",
        "ca-ES-valencia",
        "de-DE-1996",
        "sl-rozaj-biske",
        "be-TARASK",
        "en-US-u-rg-gbzzzz-nu-arab",
        "en-u-ca-gregory-nu-thai",
        "th-TH-u-nu-thai-x-lvariant-th",
        "ja-JP-u-ca-japanese-x-lvariant-jp",
        "de-x-private-u-nu-thai",
        "de-a-foo-u-nu-thai",
        "de--DE",
        "de_DE",
        "x-whole",
        "123",
        "abcd-DE",
        "abcde-DE",
        "EN-latn-gb",
        "",
      )
      val generated = tags(20000, 205L) ++
        List.tabulate(200)(i => "de-DE-" + List.fill(i % 30)("varia" + i).mkString("-"))
      val different = (edge ++ generated).filter { t =>
        val jdk    = Locale.forLanguageTag(t)
        val parsed = LanguageSubtags.parse(t)
        val ours   = (parsed.language, parsed.script, parsed.region, parsed.variant, parsed.digits)
        ours != (jdk.getLanguage, jdk.getScript, jdk.getCountry, jdk.getVariant, Option(jdk.getUnicodeLocaleType("nu")))
      }
      assertTrue(different.isEmpty)
    },
    test("the grandfathered tags read as the language the JDK gives them") {
      val grandfathered =
        List("art-lojban", "i-klingon", "no-bok", "no-nyn", "zh-min-nan", "zh-guoyu", "i-default", "en-GB-oed")
      val different     =
        grandfathered.filter(t => LanguageSubtags.parse(t).language != Locale.forLanguageTag(t).getLanguage)
      assertTrue(different.isEmpty)
    },
    test("a language is named as the JDK names it, in every language the JDK has data for") {
      val available = (DateFormat.getAvailableLocales.iterator ++ NumberFormat.getAvailableLocales.iterator).toList
      val edge      = List(
        "de-CH",
        "pt-BR",
        "zh-Hant-TW",
        "sr-Latn",
        "de-1996",
        "de-Cher-DE-1901-1996",
        "el-POLYTON",
        "ca-ES-valencia",
        "be-TARASK",
        "en-US-POSIX",
        "de-Zzzz",
        "de-Abcd",
        "de-XY",
        "es-419",
        "en-QO",
        "th-TH-u-nu-thai",
        "ja-JP-u-ca-japanese",
        "th-TH-u-nu-thai-x-lvariant-th",
        "ja-JP-u-ca-japanese-x-lvariant-jp",
        "de-DE-x-lvariant-abc-def",
        "ang",
        "ace-ID",
        "arc",
        "xyz-AB",
        "abcde",
      )
      val named     = available.map(_.toLanguageTag).filterNot(_ == "und") ++ edge
      val readers   = List("en", "nl", "fr", "de", "ja", "ar", "zh-Hant", "ru", "th", "fa")
      val different =
        for
          tag    <- named
          reader <- readers
          if PlatformLanguageNames.displayName(tag, reader) != byTheJdk(tag, reader)
        yield s"$tag in $reader"
      assertTrue(named.size > 1000, different.isEmpty)
    },
    test("every language of two or three letters is named as the JDK names it") {
      val alphabet  = 'a' to 'z'
      val languages =
        (for a <- alphabet; b <- alphabet yield s"$a$b") ++
        (for a <- alphabet; b <- alphabet; c <- alphabet yield s"$a$b$c")
      val different = languages.filter(l => PlatformLanguageNames.displayName(l, "en") != byTheJdk(l, "en"))
      assertTrue(languages.size == 18252, different.isEmpty)
    },
    test("tags of a megabyte read in time linear in their length") {
      val legacy  = "de-DE-x-lvariant-" + List.fill(250000)("abc").mkString("-")
      val plain   = "de-DE-" + List.fill(170000)("abcde").mkString("-")
      val pattern = "{$a :number} {$b :number} {$c :number} {$d :number} {$e :number}"
      val args    = Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
      TimeBudget.within("five numbers in two locales of a megabyte", 2000)(
        List(legacy, plain).map(t => MessageFormat2.formatIn(pattern, args, t))
      ): out =>
        assertTrue(legacy.length > 1000000, plain.length > 1000000, out == List("1 2 3 4 5", "1 2 3 4 5"))
    },
    test("formatting and naming for distinct reader languages needs no more than a small heap") {
      // A small heap shows that what the JDK keeps does not grow with the number of distinct tags
      // or names: 30000 long tags through a template's locale and a language name, and 400000
      // distinct names of language, script and region.
      val javaBin = ProcessHandle.current.info.command.orElse("java")
      val log     = java.io.File.createTempFile("many-languages", ".log")
      val command = List(
        javaBin,
        "-Xmx96m",
        "-cp",
        System.getProperty("java.class.path"),
        ManyLanguages.getClass.getName.stripSuffix("$"),
      )
      val process = new ProcessBuilder(command*).redirectErrorStream(true).redirectOutput(log).start()
      val ended   = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
      if !ended then process.destroyForcibly().waitFor()
      val out     = String(java.nio.file.Files.readAllBytes(log.toPath), "UTF-8")
      log.delete()
      assertTrue(ended, process.exitValue == 0, out.contains("formatted 30000, named 400000"))
    },
  )

end PlatformLocalesSpec

// Run in its own JVM by PlatformLocalesSpec, with a small heap: formats and names for 30000
// distinct long tags, names 400000 distinct combinations of language, script and region, and
// prints how many it did.
object ManyLanguages:

  def main(args: Array[String]): Unit =
    val day       = LocalDate.of(2026, 3, 29)
    val count     = 30000
    // A tag of some 8 KB through a template's locale, and one within a Language's 255 characters
    // for a language name.
    val variants  = (0 until count).map(i => f"v$i%07d")
    variants.zipWithIndex.foreach { (v, i) =>
      val long  = s"de-DE-$v-" + List.fill(900)(v).mkString("-")
      val short = s"de-DE-$v-" + List.fill(27)(v).mkString("-")
      MessageFormat2.formatIn("{$d :date}", Map("d" -> day), long)
      PlatformLanguageNames.displayName(short, short.take(255))
      PlatformLanguageNames.displayName("de", PlatformLocalesSpec.tags(1, i.toLong).head)
    }
    val available =
      (java.text.DateFormat.getAvailableLocales.iterator ++ java.text.NumberFormat.getAvailableLocales.iterator).toList
    val languages = available.map(_.getLanguage).filter(_.nonEmpty).distinct
    val scripts   = available.map(_.getScript).filter(_.nonEmpty).distinct
    val regions   = java.util.Locale.getISOCountries.toList
    val names     = (for
      l <- languages.iterator
      s <- scripts.iterator
      r <- regions.iterator
    yield s"$l-$s-$r").take(400000)
    var named     = 0
    names.foreach { t =>
      PlatformLanguageNames.displayName(t, "en")
      named += 1
    }
    println(s"formatted $count, named $named")

  end main

end ManyLanguages
