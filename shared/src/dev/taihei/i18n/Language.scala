package dev.taihei.i18n

import java.util.Locale

import zio.json.*

/**
 * A language, identified by its canonical BCP 47 tag (`en`, `nl-BE`, `de`, `zh-Hant-TW`).
 *
 * It used to be a closed enum of English, Dutch and French before 2.0.0. It is a class now so that
 * any language can be named, while the three built-ins stay `val`s in the companion: `case
 * English =>` still matches (a stable identifier compares with `==`), but a `match` on a
 * `Language` is no longer checked for exhaustiveness and needs a `case _`.
 *
 * Equality and hash are by tag, so `Language("en") == English` and a `Map[Language, _]` built
 * from either finds the other.
 */
final class Language private (val tag: String) extends Serializable:

  /**
   * The BCP 47 tag. The name predates regions: for `nl-BE` it is `"nl-BE"`, not `"nl"`; compare
   * `base.isoCode` to ask for the language alone.
   */
  def isoCode: String = tag

  def isDefault: Boolean = this == Language.defaultLanguage

  /** The language without script, region or variant: `nl-BE` gives `nl`, `nl` gives itself. */
  def base: Language =
    val primary = tag.takeWhile(_ != '-')
    if primary == tag then this else Language.fromCanonical(primary)

  /**
   * The language as a `java.util.Locale`. On the JVM each call makes a JDK Locale, and the JDK
   * keeps the Locale objects it makes. Call it on the languages an application supports, not on
   * every language a reader or a message may carry; this library's own formatting and naming never
   * call it.
   */
  def locale: Locale = Locale.forLanguageTag(tag)

  /**
   * The language's name. The built-ins carry hand translations; any other language is named by
   * the platform (the JDK's locale data, `Intl.DisplayNames` in JavaScript) in English, Dutch,
   * French and in itself, and falls back to its tag where the platform does not know it.
   */
  def label: Message = Language.builtInLabels.getOrElse(
    this, {
      val named = (Language.values.toList :+ this).distinct.map: in =>
        in -> PlatformLanguageNames.displayName(tag, in.tag).getOrElse(tag)
      Message.with0Args(named*)
    },
  )

  override def equals(other: Any): Boolean = other match
    case that: Language => tag == that.tag
    case _              => false

  override def hashCode: Int = tag.hashCode

  /**
   * The built-ins keep their enum names (`"English"`), which is also their JSON form; any other
   * language shows its tag.
   */
  override def toString: String = Language.builtInNames.getOrElse(tag, tag)

end Language

object Language:

  val English: Language = new Language("en")
  val Dutch: Language   = new Language("nl")
  val French: Language  = new Language("fr")

  private val builtIns: Vector[Language] = Vector(English, Dutch, French)

  private val builtInNames: Map[String, String] =
    Map("en" -> "English", "nl" -> "Dutch", "fr" -> "French")

  private val byName: Map[String, Language] =
    builtIns.map(l => builtInNames(l.tag) -> l).toMap

  private lazy val builtInLabels: Map[Language, Message] = Map(
    English -> Message.with0Args(
      English -> "English",
      Dutch -> "Engels",
      French -> "Anglais",
    ),
    Dutch -> Message.with0Args(
      English -> "Dutch",
      Dutch -> "Nederlands",
      French -> "Néerlandais",
    ),
    French -> Message.with0Args(
      English -> "French",
      Dutch -> "Frans",
      French -> "Français",
    ),
  )

  // Longer than any tag in practical use: RFC 5646 §4.4.1 asks implementations for at least 35
  // characters for language, extlang, script, region and variant, and tags with extensions stay
  // well under 100.
  private val MaxTagLength = 255

  /**
   * The language for a BCP 47 tag, canonicalized (`NL-be` gives `nl-BE`), or why it is not one. A
   * tag longer than 255 characters, after surrounding whitespace is trimmed, is refused.
   */
  def parse(tag: String): Either[String, Language] =
    val trimmed = tag.trim.replace('_', '-')
    if trimmed.length > MaxTagLength then
      Left(s"Not a BCP 47 language tag: longer than $MaxTagLength characters")
    else if isWellFormed(trimmed) then Right(fromCanonical(canonical(trimmed)))
    else Left(s"Not a BCP 47 language tag: '${printable(tag.trim)}'")

  // The input as it may safely be logged: anything but printable ASCII as a \uXXXX escape, so a
  // line break or a terminal control sequence in a caller's input does not reach a log as such.
  private def printable(s: String): String =
    s.flatMap(c => if c >= ' ' && c <= '~' then c.toString else "\\u%04x".format(c.toInt))

  private def isAlpha(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isAlnum(c: Char): Boolean = isAlpha(c) || isDigit(c)

  private def isLanguage(s: String): Boolean = s.length >= 2 && s.length <= 3 && s.forall(isAlpha)
  private def isExtlang(s: String): Boolean  = s.length == 3 && s.forall(isAlpha)
  private def isScript(s: String): Boolean   = s.length == 4 && s.forall(isAlpha)

  private def isRegion(s: String): Boolean =
    (s.length == 2 && s.forall(isAlpha)) || (s.length == 3 && s.forall(isDigit))

  private def isVariant(s: String): Boolean =
    s.forall(isAlnum) && ((s.length >= 5 && s.length <= 8) || (s.length == 4 && isDigit(s.head)))

  private def isExtension(s: String): Boolean =
    s.length == 1 && isAlnum(s.head) && s.head != 'x' && s.head != 'X'

  private def isExtensionPart(s: String): Boolean =
    s.length >= 2 && s.length <= 8 && s.forall(isAlnum)

  private def isPrivateUse(s: String): Boolean = s == "x" || s == "X"

  private def isPrivateUsePart(s: String): Boolean = s.length >= 1 && s.length <= 8 &&
    s.forall(isAlnum)

  // BCP 47 (RFC 5646) langtag: language[-extlang]{0,3}[-script][-region][-variant]*[-extension]*
  // [-privateuse], ASCII letters and digits in any case. The primary language is held to two or
  // three letters (ISO 639), so that a word such as "invalid" is refused rather than read as a
  // reserved 5-8 letter subtag. Each kind of subtag has a shape no other kind before the
  // extensions shares, so one pass in that order decides.
  private def isWellFormed(tag: String): Boolean =
    // A limit of -1 keeps empty subtags ("en-", "en--US"), which are refused.
    val subtags = tag.split("-", -1)

    // The index after at most `max` subtags from `from` that satisfy `p`.
    def skip(
      from: Int,
      max: Int,
      p: String => Boolean,
    ): Int =
      var i = from
      while i < subtags.length && i - from < max && p(subtags(i)) do i += 1
      i

    // A singleton followed by at least one subtag of its kind; -1 when it has none.
    def section(
      from: Int,
      singleton: String => Boolean,
      part: String => Boolean,
    ): Int =
      if from < subtags.length && singleton(subtags(from)) then
        val end = skip(from + 1, Int.MaxValue, part)
        if end == from + 1 then -1 else end
      else from

    val language = skip(0, 1, isLanguage)
    if language == 0 then false
    else
      val region = skip(skip(skip(language, 3, isExtlang), 1, isScript), 1, isRegion)
      var at     = skip(region, Int.MaxValue, isVariant)
      var next   = section(at, isExtension, isExtensionPart)
      while next > at do
        at = next
        next = section(at, isExtension, isExtensionPart)
      next >= 0 && section(at, isPrivateUse, isPrivateUsePart) == subtags.length
  end isWellFormed

  /** The language for a BCP 47 tag; throws `IllegalArgumentException` on anything else. */
  def apply(tag: String): Language =
    parse(tag).fold(e => throw new IllegalArgumentException(e), identity)

  /** The built-ins, in the enum's old order. Any other language is made with `Language(tag)`. */
  def values: Array[Language] = builtIns.toArray

  /** A built-in by its name (`"English"`), as the enum's `valueOf` did. */
  def valueOf(name: String): Language =
    byName.getOrElse(
      name,
      throw new IllegalArgumentException(
        s"enum dev.taihei.i18n.Language has no case with name: $name"
      ),
    )

  def defaultLanguage: Language = English

  def empty: Language = English

  def sample: Language =
    val vs = values
    vs(scala.util.Random.nextInt(vs.length))

  /** The language for a BCP 47 tag; English for anything that is not one. */
  def of(isoCode: String): Language = parse(isoCode).getOrElse(defaultLanguage)

  /** A built-in's name or any tag: the two forms the JSON codecs read. */
  private def decode(s: String): Either[String, Language] =
    byName.get(s).map(Right(_)).getOrElse(parse(s))

  // A built-in is written by its name, as the enum was, so stored data and older readers keep
  // working; any other language by its tag. Both forms are read.
  given JsonEncoder[Language] = JsonEncoder.string.contramap(_.toString)
  given JsonDecoder[Language] = JsonDecoder.string.mapOrFail(decode)

  // The enum derived a JsonCodec; code that summons one keeps compiling.
  given JsonCodec[Language] = JsonCodec(summon[JsonEncoder[Language]], summon[JsonDecoder[Language]])

  given JsonFieldEncoder[Language] = JsonFieldEncoder.string.contramap(_.toString)
  given JsonFieldDecoder[Language] = JsonFieldDecoder.string.mapOrFail(decode)

  private def fromCanonical(tag: String): Language =
    builtIns.find(_.tag == tag).getOrElse(new Language(tag))

  // A well-formed tag is ASCII, so its case is changed by hand: String.toLowerCase and
  // toUpperCase follow the default locale, and under a Turkish one "IT" would become "ıt".
  private def lower(s: String): String =
    s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

  private def upper(s: String): String =
    s.map(c => if c >= 'a' && c <= 'z' then (c - 32).toChar else c)

  // RFC 5646 §2.1.1: language lower case, script title case, region upper case, the rest lower.
  // Everything after a singleton (an extension or private use) stays lower case.
  private def canonical(tag: String): String =
    val parts    = tag.split('-').toVector
    val (_, out) = parts.zipWithIndex.foldLeft((false, Vector.empty[String])):
      case ((afterSingleton, acc), (p, i)) =>
        val singleton = p.length == 1
        val formatted =
          if i == 0 || afterSingleton || singleton then lower(p)
          else if p.length == 4 && p.head.isLetter then upper(p.take(1)) + lower(p.drop(1))
          else if p.length == 2 || (p.length == 3 && p.head.isDigit) then upper(p)
          else lower(p)
        (afterSingleton || singleton, acc :+ formatted)
    out.mkString("-")

end Language
