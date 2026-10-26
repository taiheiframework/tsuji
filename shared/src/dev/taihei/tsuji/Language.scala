package dev.taihei.tsuji

import com.github.plokhotnyuk.jsoniter_scala.core.*

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
   * The script subtag, if the tag has one: `zh-Hant-TW` gives `Some("Hant")`, `nl-BE` gives
   * `None`. Only the subtags before the variants count; nothing inside an extension or private
   * use does.
   */
  def script: Option[String] = Language.scriptAndRegion(tag)._1

  /**
   * The region subtag, if the tag has one: `nl-BE` gives `Some("BE")`, `es-419` gives
   * `Some("419")`, `de-1996` gives `None`. An extlang is skipped (`zh-yue-HK` gives `Some("HK")`)
   * and nothing inside an extension or private use counts (`en-x-us` gives `None`).
   */
  def region: Option[String] = Language.scriptAndRegion(tag)._2

  /**
   * The language's name, as a message in the reader's language: `Dutch.label` shows as "Dutch" to
   * an English reader and "Néerlandais" to a French one. The built-ins carry hand translations;
   * any other language is named by the platform (the JDK's locale data, `Intl.DisplayNames` in
   * JavaScript), and by its tag where the platform does not know it.
   */
  def label: Message = CoreMessages.Languages.name(language = this)

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

/**
 * The companion. Beside the factories here, the JVM adds `Language.fromLocale(java.util.Locale)`
 * and its inverse, the extension `language.locale`, which needs no import because it is in the
 * companion. A platform locale type never appears in the shared class, so `Language` and its JSON
 * form stay the same on every platform.
 */
object Language extends LanguagePlatform:

  val English: Language = new Language("en")
  val Dutch: Language   = new Language("nl")
  val French: Language  = new Language("fr")

  private val builtIns: Vector[Language] = Vector(English, Dutch, French)

  private val builtInNames: Map[String, String] =
    Map("en" -> "English", "nl" -> "Dutch", "fr" -> "French")

  private val byName: Map[String, Language] =
    builtIns.map(l => builtInNames(l.tag) -> l).toMap

  // The built-ins' names in each built-in, by hand, as earlier versions shipped them.
  private val builtInNameTable: Map[String, Map[String, String]] = Map(
    "en" -> Map("en" -> "English", "nl" -> "Engels", "fr" -> "Anglais"),
    "nl" -> Map("en" -> "Dutch", "nl" -> "Nederlands", "fr" -> "Néerlandais"),
    "fr" -> Map("en" -> "French", "nl" -> "Frans", "fr" -> "Français"),
  )

  /**
   * The name of `language` for a reader of the language tagged `reader`: a built-in's hand
   * translation, else the platform's name, else the tag.
   */
  private[tsuji] def nameIn(language: Language, reader: String): String =
    val readerBase = reader.takeWhile(_ != '-')
    builtInNameTable
      .get(language.tag)
      .flatMap(names => names.get(reader).orElse(names.get(readerBase)))
      .orElse(PlatformLanguageNames.displayName(language.tag, reader))
      .getOrElse(language.tag)

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
        s"enum dev.taihei.tsuji.Language has no case with name: $name"
      ),
    )

  def defaultLanguage: Language = English

  def empty: Language = English

  def sample: Language =
    val vs = values
    vs(scala.util.Random.nextInt(vs.length))

  /** The language for a BCP 47 tag; English for anything that is not one. */
  def of(isoCode: String): Language = parse(isoCode).getOrElse(defaultLanguage)

  /**
   * The languages the platform prefers, most preferred first: on the JVM the default display
   * locale (`Locale.getDefault(Locale.Category.DISPLAY)`); in JavaScript `navigator.languages`,
   * else `navigator.language`, else the locale of `Intl.DateTimeFormat`. A tag that is not a
   * language (`und`) or not well formed is dropped, as is a repeat. The result may be empty and
   * holds no fallback, so a caller picks one:
   *
   * {{{
   * val language = Language.preferred.headOption.getOrElse(Language.defaultLanguage)
   * }}}
   *
   * It reads the platform on every call and never throws.
   */
  def preferred: Vector[Language] = fromPlatformTags(PlatformPreferredLanguages.tags)

  /**
   * The language of a tag a platform locale gave: `parse`, except that a tag without a language
   * (`und`, `und-US`, the tag of `Locale.ROOT`) is refused instead of read as a language.
   */
  private[tsuji] def fromPlatformTag(tag: String): Either[String, Language] =
    parse(tag).flatMap: language =>
      if language.tag == "und" || language.tag.startsWith("und-") then
        Left(s"Not a language: '${language.tag}' names none")
      else Right(language)

  /** The languages of the platform's tags, in order, without the ones refused and without repeats. */
  private[tsuji] def fromPlatformTags(tags: Seq[String]): Vector[Language] =
    tags.iterator.flatMap(t => fromPlatformTag(t).toOption).distinct.toVector

  // The script and the region of a well-formed, canonical tag. The subtags before them have
  // shapes neither shares (a language, up to three three-letter extlangs), and a singleton ends
  // the search, so nothing in an extension or private use is taken for either.
  private def scriptAndRegion(tag: String): (Option[String], Option[String]) =
    val subtags = tag.split('-').toVector.drop(1).takeWhile(_.length > 1).dropWhile(isExtlang)
    val script  = subtags.headOption.filter(isScript)
    val next    = if script.isDefined then subtags.drop(1) else subtags
    (script, next.headOption.filter(isRegion))

  /** A built-in's name or any tag: the two forms the JSON codecs read. */
  private def decode(s: String): Either[String, Language] =
    byName.get(s).map(Right(_)).getOrElse(parse(s))

  // The language in a JSON tree: a string, in either of the forms the codecs read.
  private[tsuji] def fromTree(json: JsonTree): Either[String, Language] = json match
    case JsonTree.Str(s) => decode(s)
    case _               => Left("a language is a string")

  /**
   * A language as a JSON string. A built-in is written by its name (`"English"`), as the enum of
   * 1.x was, so stored data and older readers keep working; any other language by its tag
   * (`"nl-BE"`). Both forms are read; anything else is a `JsonReaderException`.
   */
  given JsonValueCodec[Language] = new JsonValueCodec[Language]:
    def decodeValue(in: JsonReader, default: Language): Language =
      TreeCodec.guarded(in)(decode(in.readString(null)).fold(in.decodeError, identity))
    def encodeValue(x: Language, out: JsonWriter): Unit          = out.writeVal(x.toString)
    def nullValue: Language                                      = null

  /** A language as the name of a JSON object's field, in the forms of its value codec. */
  given JsonKeyCodec[Language] = new JsonKeyCodec[Language]:
    def decodeKey(in: JsonReader): Language           =
      TreeCodec.guarded(in)(decode(in.readKeyAsString()).fold(in.decodeError, identity))
    def encodeKey(x: Language, out: JsonWriter): Unit = out.writeKey(x.toString)

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
