package dev.taihei.tsuji

import java.text.{DateFormat, NumberFormat}
import java.util.Locale

private[tsuji] object PlatformLocales:

  // The formatters only ever see locales from a fixed set: the ones the JDK has locale data for,
  // and those with a numbering system from NativeDigits. A tag is read here as text and looked
  // up in that set, so no JDK Locale is ever made from a tag itself: the JDK keeps the Locale
  // objects it makes. Which locale a tag gets is decided the same way on every JDK, rather than by
  // the JDK's lookup, which differs between versions for a script it has no data for.
  private val Available: Set[Locale] =
    (DateFormat.getAvailableLocales.iterator ++ NumberFormat.getAvailableLocales.iterator).toSet

  // The subtags of a locale as the lookup compares them: language in lower case, script in title
  // case, region in upper case, the variant as the JDK keeps it.
  final private case class Key(
    language: String,
    script: String,
    region: String,
    variant: String)

  private def keyOf(locale: Locale): Key =
    Key(locale.getLanguage, locale.getScript, locale.getCountry, locale.getVariant)

  private val ByKey: Map[Key, Locale] = Available.iterator.map(l => keyOf(l) -> l).toMap

  // The digits a reader may ask for with `-u-nu-`: Latin digits in every language, and the
  // language's own digits where CLDR gives it a native numbering system. A legacy JDK locale such
  // as th_TH_TH arrives as th-TH-u-nu-thai and keeps its Thai digits.
  private val NativeDigits: Map[String, String] = Map(
    "ar" -> "arab",
    "as" -> "beng",
    "bgc" -> "deva",
    "bho" -> "deva",
    "bn" -> "beng",
    "bo" -> "tibt",
    "brx" -> "deva",
    "ccp" -> "cakm",
    "ckb" -> "arab",
    "doi" -> "deva",
    "dz" -> "tibt",
    "fa" -> "arabext",
    "ff" -> "adlm",
    "gu" -> "gujr",
    "hi" -> "deva",
    "km" -> "khmr",
    "kn" -> "knda",
    "kok" -> "deva",
    "ks" -> "arabext",
    "lo" -> "laoo",
    "mai" -> "deva",
    "ml" -> "mlym",
    "mni" -> "beng",
    "mr" -> "deva",
    "my" -> "mymr",
    "ne" -> "deva",
    "nqo" -> "nkoo",
    "or" -> "orya",
    "pa" -> "guru",
    "ps" -> "arabext",
    "sa" -> "deva",
    "sat" -> "olck",
    "sd" -> "arab",
    "ta" -> "tamldec",
    "te" -> "telu",
    "th" -> "thai",
    "ur" -> "arabext",
    "yue" -> "hanidec",
    "zh" -> "hanidec",
  )

  // Every available locale with Latin digits and with its language's own digits, made once.
  private val WithDigits: Map[(Locale, String), Locale] =
    Available.iterator
      .filter(_ != Locale.ROOT)
      .flatMap { base =>
        ("latn" :: NativeDigits.get(base.getLanguage).toList)
          .map(d => (base, d) -> Locale.forLanguageTag(s"${base.toLanguageTag}-u-nu-$d"))
      }
      .toMap

  private val Netherlands = Locale.forLanguageTag("nl-NL")

  /**
   * The JDK locale a formatter uses for a language tag. A bare `en`, `nl` or `fr` keeps the
   * region it always had (US, NL, FR), so their output did not change when any language became
   * possible (2.0.0). Any other tag maps onto the most specific locale the JDK has data for:
   * with its script, then without it, dropping the variant and then the region, as the JDK's
   * lookup does; a region override (`-u-rg-`) stands for the region. So every locale the JDK has
   * data for, and a tag that only adds a region, a variant or private use to one, formats as it
   * did. A script the JDK has no data for in that language formats as the language without it.
   * Other extensions are dropped, apart from a numbering system that is Latin digits or the
   * language's own digits. A language the JDK does not know formats as the root locale, as it
   * always did; a tag that names no language falls back to the US format that English uses.
   */
  def javaLocale(tag: String): Locale = tag match
    case "en" => Locale.US
    case "nl" => Netherlands
    case "fr" => Locale.FRANCE
    case _    =>
      val parsed = LanguageSubtags.parse(tag)
      if parsed.language.isEmpty then Locale.US
      else
        val base = available(parsed).getOrElse(Locale.ROOT)
        parsed.digits
          .filter(d => base != Locale.ROOT && allowed(base.getLanguage, d))
          .flatMap(d => WithDigits.get((base, d)))
          .getOrElse(base)

  private def available(parsed: LanguageSubtags): Option[Locale] =
    import parsed.{language, script, variant}
    val region   = parsed.regionOverride.getOrElse(parsed.region)
    val plain    = List(
      Key(language, "", region, variant),
      Key(language, "", region, ""),
      Key(language, "", "", ""),
    )
    val scripted =
      if script.isEmpty then Nil
      else
        List(
          Key(language, script, region, variant),
          Key(language, script, region, ""),
          Key(language, script, "", ""),
        )
    (scripted ++ plain).iterator.flatMap(ByKey.get).nextOption()

  end available

  private[tsuji] def digitsAllowed(language: String, digits: String): Boolean =
    allowed(language, digits)

  private def allowed(language: String, digits: String): Boolean =
    digits == "latn" || NativeDigits.get(language).contains(digits)

  /**
   * Every locale [[javaLocale]] can return, for the tests that hold it to a finite set: the
   * available locales, each with Latin digits and with its language's own digits, and the three
   * fixed ones.
   */
  private[tsuji] def possible: Set[Locale] =
    Available ++ WithDigits.values ++ Set(Locale.US, Netherlands, Locale.FRANCE, Locale.ROOT)

end PlatformLocales

/**
 * A BCP 47 language tag read as text, without making a JDK Locale, the way the JDK reads it: the
 * language (in lower case, an extended language subtag such as the `yue` of `zh-yue` in its place,
 * legacy codes such as `iw` replaced, empty when the tag names none), the script, the region, the
 * variants joined by `_` with those of a private-use `lvariant`, and the region override,
 * numbering system and calendar of a `-u-` extension. It stops at the first subtag that is not
 * well formed.
 */
final private[tsuji] case class LanguageSubtags(
  language: String,
  script: String,
  region: String,
  variant: String,
  regionOverride: Option[String],
  digits: Option[String],
  calendar: Option[String])

private[tsuji] object LanguageSubtags:

  private val Legacy = Map("iw" -> "he", "ji" -> "yi", "in" -> "id", "und" -> "")

  // Grandfathered and other irregular tags the JDK reads as a language of its own.
  private val Grandfathered: Map[String, String] = Map(
    "art-lojban" -> "jbo",
    "cel-gaulish" -> "xtg",
    "en-gb-oed" -> "en-GB",
    "i-ami" -> "ami",
    "i-bnn" -> "bnn",
    "i-default" -> "en",
    "i-enochian" -> "",
    "i-hak" -> "hak",
    "i-klingon" -> "tlh",
    "i-lux" -> "lb",
    "i-mingo" -> "see",
    "i-navajo" -> "nv",
    "i-pwn" -> "pwn",
    "i-tao" -> "tao",
    "i-tay" -> "tay",
    "i-tsu" -> "tsu",
    "no-bok" -> "nb",
    "no-nyn" -> "nn",
    "sgn-be-fr" -> "sfb",
    "sgn-be-nl" -> "vgt",
    "sgn-ch-de" -> "sgg",
    "zh-guoyu" -> "cmn",
    "zh-hakka" -> "hak",
    "zh-min" -> "nan",
    "zh-min-nan" -> "nan",
    "zh-xiang" -> "hsn",
  )

  private def letters(s: String)  = s.nonEmpty && s.forall(c => c.isLetter && c < 128)
  private def alnum(s: String)    = s.nonEmpty && s.forall(c => c.isLetterOrDigit && c < 128)
  private def digitsOf(s: String) = s.nonEmpty && s.forall(c => c >= '0' && c <= '9')

  private[tsuji] def isVariant(s: String): Boolean =
    alnum(s) && (s.length >= 5 && s.length <= 8 || s.length == 4 && s.head >= '0' && s.head <= '9')

  /** The subtags of `tag`, never throwing, in time linear in its length. */
  def parse(tag: String): LanguageSubtags =
    val lower = tag.toLowerCase(Locale.ROOT)
    Grandfathered.get(lower) match
      case Some(known) => parse(known)
      case None        => regular(tag.split("-", -1).toList)

  private def regular(subtags: List[String]): LanguageSubtags =
    subtags match
      case first :: rest if letters(first) && first.length >= 2 && first.length <= 8 =>
        // Up to three extended language subtags may follow a two- or three-letter language; the
        // JDK takes the first as the language.
        val extended              = if first.length <= 3 then
          rest.take(3).takeWhile(s => s.length == 3 && letters(s))
        else Nil
        val primary               = first.toLowerCase(Locale.ROOT)
        // A legacy code is replaced, and `und` names no language; an extended subtag is kept.
        val language              = extended.headOption match
          case Some(ext) => ext.toLowerCase(Locale.ROOT)
          case None      => Legacy.getOrElse(primary, primary)
        val afterExt              = rest.drop(extended.size)
        val (script, afterScript) = afterExt match
          case s :: tail if s.length == 4 && letters(s) =>
            (s.head.toUpper.toString + s.tail.toLowerCase(Locale.ROOT), tail)
          case other                                    => ("", other)
        val (region, afterRegion) = afterScript match
          case r :: tail if r.length == 2 && letters(r)  => (r.toUpperCase(Locale.ROOT), tail)
          case r :: tail if r.length == 3 && digitsOf(r) => (r, tail)
          case other                                     => ("", other)
        val variants              = afterRegion.takeWhile(isVariant)
        val (rg, nu, ca, lvs)     = extensions(afterRegion.drop(variants.size))
        LanguageSubtags(language, script, region, (variants ++ lvs).mkString("_"), rg, nu, ca)
      case _ => LanguageSubtags("", "", "", "", None, None, None)

  // The `rg`, `nu` and `ca` keywords of a `-u-` extension, and the subtags of a private-use
  // `lvariant`, which the JDK makes part of the variant; other extensions are skipped. A local
  // loop over the subtags, which never leave this method.
  private def extensions(subtags: List[String])
    : (Option[String], Option[String], Option[String], List[String]) =
    var rg: Option[String] = None
    var nu: Option[String] = None
    var ca: Option[String] = None
    val lvariant           = scala.collection.mutable.ListBuffer.empty[String]
    var rest               = subtags
    var singleton          = ""
    var key                = ""
    var done               = false
    while !done && rest.nonEmpty do
      val written = rest.head
      val s       = written.toLowerCase(Locale.ROOT)
      rest = rest.tail
      if !alnum(s) || s.length > 8 then done = true
      else if singleton == "x" then
        if key == "lvariant" then lvariant += written
        else if s == "lvariant" then key = "lvariant"
      else if s.length == 1 then
        singleton = s
        key = ""
      else if singleton == "u" then
        if s.length == 2 then key = s
        else if key == "rg" && rg.isEmpty then rg = Some(s)
        else if key == "nu" && nu.isEmpty then nu = Some(s)
        else if key == "ca" && ca.isEmpty then ca = Some(s)
    end while
    val region             = rg.collect {
      case r if r.length == 6 && r.endsWith("zzzz") && letters(r.take(2)) =>
        r.take(2).toUpperCase(Locale.ROOT)
    }
    (region, nu, ca, lvariant.toList)

  end extensions

end LanguageSubtags
