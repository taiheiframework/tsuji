package dev.taihei.tsuji

import scala.util.control.NonFatal

import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * The MF2 templates of one language, by message key: one catalogue file, written by a translator
 * or by `taihei-tsuji-tools`.
 *
 * {{{
 * {"format":1,"language":"de","messages":{"order.shipped":"...","order.cancelled":"..."}}
 * }}}
 *
 * [[Catalogue.parse]] reads a file and keeps what it can: an entry that is not a key and a
 * template is left out, and named in [[problems]].
 */
final class Catalogue private (
  val language: Language,
  val messages: Map[MessageKey, String],
  val problems: List[CatalogueProblem]):

  /** The catalogue as a format 1 file, its keys in order. */
  def toJson: String = JsonTreeWriter.compact(toTree)

  // The layout of the files taihei-tsuji-tools writes: a field a line, indented.
  private[tsuji] def toJsonPretty: String = JsonTreeWriter.pretty(toTree)

  private def toTree: JsonTree =
    JsonTree.Obj(
      "format" -> JsonTree.Num(java.math.BigDecimal.valueOf(Catalogue.Format.toLong)),
      "language" -> JsonTree.Str(language.tag),
      "messages" ->
      JsonTree.Obj(messages.toVector.sortBy(_._1.value).map((k, v) => k.value -> JsonTree.Str(v))),
    )

  // The entries of keys that start with `prefix` and a dot; the others become problems.
  private[tsuji] def ofFamily(prefix: String): Catalogue =
    val (own, others) = messages.partition((k, _) => k.value.startsWith(prefix + "."))
    val foreign       = others.keys.toList.sortBy(_.value).map(k =>
      CatalogueProblem(s"not a key of $prefix", Some(language), Some(k))
    )
    new Catalogue(language, own, problems ++ foreign)

  override def equals(other: Any): Boolean = other match
    case that: Catalogue => language == that.language && messages == that.messages
    case _               => false

  override def hashCode: Int = (language, messages).hashCode

  override def toString: String = s"Catalogue(${language.tag}, ${messages.size} messages)"

end Catalogue

object Catalogue:

  /** The catalogue file format this version reads and writes. */
  val Format = 1

  // A catalogue is input the application did not compile. Larger files, more messages or deeper
  // nesting than these are refused before the JSON is parsed.
  private[tsuji] val MaxLength   = 4 * 1024 * 1024
  private[tsuji] val MaxMessages = 100000
  private val MaxDepth           = 16

  /** A catalogue of these templates, which are checked when they are first used. */
  def apply(language: Language, messages: Map[MessageKey, String]): Catalogue =
    new Catalogue(language, messages, Nil)

  /**
   * The catalogue in a format 1 file, or why the file is not one. It never throws: a file that is
   * too large, too deeply nested or not JSON is refused, an entry that is not a key and a template
   * is left out and named in the catalogue's problems.
   */
  def parse(json: String): Either[CatalogueProblem, Catalogue] =
    def problem(message: String) = CatalogueProblem(message)
    if json.length > MaxLength then Left(problem(s"a catalogue is at most $MaxLength characters"))
    else if JsonDepth.exceeds(json, MaxDepth) then
      Left(problem(s"a catalogue nests deeper than $MaxDepth"))
    else
      // The reader of messages, so its bounds hold for a catalogue too. Reentrant: a catalogue is
      // loaded lazily, when a message is first shown, which may happen inside a caller's own
      // jsoniter-scala decode on the same thread, whose pooled reader readFromString would reuse.
      val read =
        try
          Right(readFromStringReentrant(json, ParseConfig)(
            using TreeCodec.tree
          ))
        catch
          case e: JsonReaderException => Left(problem(s"not JSON: ${Printable(e.getMessage)}"))
          case NonFatal(_)            => Left(problem("not JSON"))
      read.flatMap:
        case obj: JsonTree.Obj => fromTree(obj)
        case _                 => Left(problem("a catalogue is a JSON object"))
    end if

  end parse

  // No hex dump of the input in an error, the end of the input checked, and room for the longest
  // string a catalogue of MaxLength can hold, so that a template that is too long is a problem
  // of its entry, as it was in 5.x, not a refusal of the file.
  private val ParseConfig = ReaderConfig
    .withAppendHexDumpToParseException(false)
    .withCheckForEndOfInput(true)
    .withMaxCharBufSize(MaxLength)

  private def fromTree(obj: JsonTree.Obj): Either[CatalogueProblem, Catalogue] =
    for
      _        <- obj.get("format") match
        case Some(JsonTree.Num(n)) if BigDecimal(n) == BigDecimal(Format) => Right(())
        case Some(other)                                                  =>
          Left(CatalogueProblem(
            s"format ${Printable(JsonTreeWriter.compact(other))} is not format $Format"
          ))
        case None => Left(CatalogueProblem("a catalogue needs \"format\""))
      language <- obj.get("language") match
        case Some(JsonTree.Str(tag)) => Language.parse(tag).left.map(CatalogueProblem(_))
        case _ => Left(CatalogueProblem("a catalogue needs \"language\", a language tag"))
      entries  <- obj.get("messages") match
        case Some(JsonTree.Obj(fields)) if fields.size > MaxMessages             =>
          Left(CatalogueProblem(s"a catalogue holds at most $MaxMessages messages", Some(language)))
        case Some(JsonTree.Obj(fields)) if SharedHashes.exceed(fields.map(_._1)) =>
          Left(CatalogueProblem(
            s"more than ${SharedHashes.Max} keys of a catalogue share a hash code",
            Some(language),
          ))
        case Some(JsonTree.Obj(fields))                                          => Right(fields)
        case _                                                                   =>
          Left(CatalogueProblem("a catalogue needs \"messages\", an object", Some(language)))
    yield
      val messages = Map.newBuilder[MessageKey, String]
      // An immutable set: its cost per key does not depend on how many keys share low bits of
      // their hash codes, as a hash table's does.
      var seen     = Set.empty[String]
      val problems = List.newBuilder[CatalogueProblem]
      entries.foreach: (k, v) =>
        MessageKey.parse(k) match
          case Left(e)    => problems += CatalogueProblem(e, Some(language))
          case Right(key) =>
            if seen.contains(k) then
              problems +=
                CatalogueProblem("given twice; the first is kept", Some(language), Some(key))
            else
              seen += k
              v match
                case JsonTree.Str(t) if t.length <= MessageFormat2.MaxTemplateLength =>
                  messages += key -> t
                case JsonTree.Str(_)                                                 =>
                  problems += CatalogueProblem(
                    s"a template is at most ${MessageFormat2.MaxTemplateLength} characters",
                    Some(language),
                    Some(key),
                  )
                case _                                                               =>
                  problems += CatalogueProblem("a template is a string", Some(language), Some(key))
      new Catalogue(language, messages.result(), problems.result())

end Catalogue

/**
 * Something wrong with a catalogue or a translation: what, and where when it is known. Text from
 * the input is escaped and cut short, so a problem can be logged as it is.
 */
final case class CatalogueProblem private[tsuji] (
  message: String,
  language: Option[Language] = None,
  key: Option[MessageKey] = None):

  override def toString: String =
    (language.map(_.tag).toList ++ key.map(_.value).toList ++ List(message)).mkString(": ")

// Whether a JSON text nests arrays and objects deeper than a limit, found in one pass without
// recursion, before a recursive parser sees it.
private[tsuji] object JsonDepth:

  def exceeds(json: String, max: Int): Boolean =
    var depth    = 0
    var inString = false
    var i        = 0
    var deeper   = false
    while i < json.length && !deeper do
      val c = json(i)
      if inString then
        if c == '\\' then i += 1
        else if c == '"' then inString = false
      else if c == '"' then inString = true
      else if c == '[' || c == '{' then
        depth += 1
        if depth > max then deeper = true
      else if c == ']' || c == '}' then depth -= 1
      i += 1
    deeper

  end exceeds

end JsonDepth

// Whether more keys than a limit share one hash code, found by sorting the hash codes, without
// hashing a key into a map or a set. Building a map or a set, or comparing two JSON objects, costs
// time quadratic in the number of keys that share a hash code; keys that come from input the
// application did not compile are checked with this before any of that happens. Real keys share
// one rarely: among 100000 of them, two or three at most.
private[tsuji] object SharedHashes:

  val Max = 16

  def exceed(keys: Iterable[String]): Boolean =
    val hashes  = keys.iterator.map(_.hashCode).toArray
    scala.util.Sorting.quickSort(hashes)
    var run     = 1
    var i       = 1
    var exceeds = false
    while i < hashes.length && !exceeds do
      run = if hashes(i) == hashes(i - 1) then run + 1 else 1
      if run > Max then exceeds = true
      i += 1
    exceeds

end SharedHashes
