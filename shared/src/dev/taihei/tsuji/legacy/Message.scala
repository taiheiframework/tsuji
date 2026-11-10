package dev.taihei.tsuji.legacy

import java.time.{LocalDate, LocalDateTime, LocalTime}

import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*

import com.github.plokhotnyuk.jsoniter_scala.core.*

/** The argument values a 4.x message takes. */
@deprecated("Use the typed arguments of a MessageFamily; legacy is removed in 7.0.0", "5.0.0")
type MessageArg = String | Int | Long | Double | Float | Boolean | LocalDate | LocalDateTime |
  LocalTime

/**
 * The message of taihei-i18n 4.x, unchanged: arguments and every translation in one value, with
 * the 4.x JSON codec. It stays in 5.x so that moving to 5.0.0 is a change of imports
 * (`import dev.taihei.tsuji.legacy.Message`); define new messages in a `MessageFamily`. It stays,
 * deprecated, in 6.x, and is removed in 7.0.0.
 */
@deprecated(
  "Use a MessageFamily: typed messages with per-language catalogues; legacy is removed in 7.0.0",
  "5.0.0",
)
case class Message(
  args: List[MessageArg],
  namedArgs: Map[String, MessageArg] = Map.empty,
  translationTemplates: Map[Language, String])
  extends Displayable:

  /** The 4.x translation; a legacy message carries its own templates and needs no catalogue. */
  def localize(
    language: Language
  )(
    using catalogues: Catalogues
  ): String = translate(language)

  private def formatArgs: Map[String, Any] =
    val positionalArgs = args.zipWithIndex.map:
      case (value, idx) => idx.toString -> value
    val mf2Args        = namedArgs ++ positionalArgs
    mf2Args.map:
      case (k, v) => k -> (v: Any)

  // Exact, then the base language (a Belgian Dutch reader gets the Dutch text when no nl-BE one
  // was written), then English; with the language whose plural rules choose the variant. That is
  // the template's (an English fallback says "21 items" to a Russian reader), or the reader's own
  // when the template is in the reader's base language: a pt-PT reader of the pt text keeps
  // pt-PT's rules, as the registry does.
  private def template(language: Language): (Option[Language], String) =
    List(
      language -> language,
      language.base -> language,
      Language.defaultLanguage -> Language.defaultLanguage,
    )
      .collectFirst(Function.unlift((l, rules) =>
        translationTemplates.get(l).map(t => Some(rules) -> t)
      ))
      .getOrElse(None -> "!!unknown message!!")

  def translate(language: Language): String = translateWithin(language, Budget.fresh())

  // As translate, within the budget of the 5.x message this one is an argument of.
  private[tsuji] def translateWithin(language: Language, budget: Budget): String =
    val (written, t) = template(language)
    MessageFormat2
      .formatted(
        MessageFormat2.parse(t).message,
        formatArgs,
        language.isoCode,
        budget,
        written.map(_.tag),
      )
      .getOrElse(t)

  def render(language: Language): String = translate(language)

  override lazy val toString: String = translate(English)

  lazy val nonEmpty: Boolean = toString.nonEmpty

end Message

@deprecated(
  "Use a MessageFamily: typed messages with per-language catalogues; legacy is removed in 7.0.0",
  "5.0.0",
)
object Message:

  /**
   * Writes and reads a message as `{"args": [...], "namedArgs": {...}, "translationTemplates":
   * {...}}`, as taihei-i18n 4.0.x does: the arguments as strings, the templates by language. As in
   * taihei-i18n 4.0.2 (4.0.0 and 4.0.1 had no bounds), a message read from JSON holds at most 1000
   * templates and 1000 arguments (positional and named together), and no template, argument or
   * argument name longer than 65536 characters; a larger one is a decoding error, a
   * `JsonReaderException`, as anything else wrong with the input is.
   * The value is written compactly, whatever the `WriterConfig`'s indentation or `escapeUnicode`.
   */
  given JsonValueCodec[Message] = TreeCodec(fromTree, toTree)

  // JSON is where a message comes from outside the program. These bounds hold the size of what
  // is read, and with it the text formatting it makes, to what a real message needs.
  private val MaxTemplates    = 1000
  private val MaxArgs         = 1000
  private val MaxStringLength = 65536

  private def tooLong(s: String): Boolean = s.length > MaxStringLength

  private def argTooLong(arg: MessageArg): Boolean = arg match
    case s: String => tooLong(s)
    case _         => false

  private def withinBounds(m: Message): Either[String, Message] =
    if m.translationTemplates.size > MaxTemplates then
      Left(s"a message holds more than $MaxTemplates templates")
    else if m.translationTemplates.values.exists(tooLong) then
      Left(s"a template is longer than $MaxStringLength characters")
    else if m.args.size + m.namedArgs.size > MaxArgs then
      Left(s"a message holds more than $MaxArgs arguments")
    else if m.args.exists(argTooLong) || m.namedArgs.values.exists(argTooLong) then
      Left(s"an argument is longer than $MaxStringLength characters")
    else if m.namedArgs.keys.exists(tooLong) then
      Left(s"an argument name is longer than $MaxStringLength characters")
    else Right(m)

  // The 4.x shape, as the codec 4.x derived wrote it: every field, the arguments as their text, a
  // built-in language by its name.
  private[tsuji] def toTree(m: Message): JsonTree =
    JsonTree.Obj(
      "args" -> JsonTree.Arr(m.args.iterator.map(a => JsonTree.Str(a.toString)).toVector),
      "namedArgs" ->
      JsonTree.Obj(m.namedArgs.iterator.map((k, v) => k -> JsonTree.Str(v.toString)).toVector),
      "translationTemplates" ->
      JsonTree.Obj(m.translationTemplates.iterator.map((l, t) =>
        l.toString -> JsonTree.Str(t)
      ).toVector),
    )

  // The 4.x shape, read as the codec 4.x derived read it: "args" and "translationTemplates" are
  // required, "namedArgs" may be missing or null, a field given twice is an error, other fields
  // are ignored, and within the two objects a name given twice keeps its last value.
  private[tsuji] def fromTree(json: JsonTree): Either[String, Message] = json match
    case JsonTree.Obj(fields) =>
      val names   = Set("args", "namedArgs", "translationTemplates")
      val present = fields.collect { case (n, v) if names.contains(n) => n -> v }
      if present.map(_._1).distinct.size != present.size then Left("a field is given twice")
      else
        val field = present.toMap
        for
          args      <- field.get("args").toRight("a 4.x message needs \"args\"").flatMap(strings)
          named     <- field.get("namedArgs") match
            case None | Some(JsonTree.Null) => Right(Map.empty[String, MessageArg])
            case Some(o)                    => pairs(o, MaxArgs, "arguments").flatMap(named)
          templates <-
            field.get("translationTemplates").toRight("a 4.x message needs \"translationTemplates\"")
              .flatMap(pairs(_, MaxTemplates, "templates"))
              .flatMap(languages)
          message   <- withinBounds(Message(args, named, templates))
        yield message
    case _                    => Left("expected a 4.x message as an object")

  private def strings(json: JsonTree): Either[String, List[MessageArg]] = json match
    case JsonTree.Arr(elements) =>
      val read = elements.collect { case JsonTree.Str(s) => s }
      if read.size == elements.size then Right(read.toList) else Left("an argument is not a string")
    case _                      => Left("\"args\" is not an array")

  // The pairs of an object of strings, counted before anything is hashed: inserting many keys with
  // the same hash into a map costs time quadratic in their number.
  private def pairs(
    json: JsonTree,
    max: Int,
    what: String,
  ): Either[String, Vector[(String, String)]] =
    json match
      case JsonTree.Obj(fields) if fields.size > max => Left(s"a message holds more than $max $what")
      case JsonTree.Obj(fields) =>
        val read = fields.collect { case (n, JsonTree.Str(s)) => n -> s }
        if read.size == fields.size then Right(read) else Left(s"one of the $what is not a string")
      case _                    => Left(s"the $what are not an object")

  // The names come from the wire and are about to be the keys of a map. A thousand long names
  // with one hash code and a common start cost a map half a million comparisons of long strings,
  // so their length and their hash codes are checked first. A name given more than 16 times
  // shares its hash code with itself and is refused with them; 4.x never wrote a name twice.
  private def named(pairs: Vector[(String, String)]): Either[String, Map[String, MessageArg]] =
    if pairs.exists((name, _) => tooLong(name)) then
      Left(s"an argument name is longer than $MaxStringLength characters")
    else if SharedHashes.exceed(pairs.map(_._1)) then
      Left(s"more than ${SharedHashes.Max} argument names of a message share a hash code")
    else Right(pairs.toMap)

  // The keys are read as the codec of Language reads them; the error leaves the key out.
  private def languages(pairs: Vector[(String, String)]): Either[String, Map[Language, String]] =
    val read =
      pairs.map((tag, template) => Language.fromTree(JsonTree.Str(tag)).toOption.map(_ -> template))
    if read.exists(_.isEmpty) then Left("a template key is not a language")
    // The tags become the keys of a map, as the argument names do: see `named`.
    else if SharedHashes.exceed(read.flatten.map(_._1.tag)) then
      Left(s"more than ${SharedHashes.Max} template languages of a message share a hash code")
    else Right(read.flatten.toMap)

  /**
   * The throwable's message, in English. A throwable without one (`new RuntimeException()`) gives
   * its `toString`, the class name, rather than a message that cannot be translated.
   */
  def of(throwable: Throwable): Message =
    Message(Nil, Map.empty, Map(English -> Option(throwable.getMessage).getOrElse(throwable.toString)))

  def of(value: String): Message = Message(Nil, Map.empty, Map(English -> value))

  def withNamedArgs(
    translations: (Language, String)*
  )(
    arguments: (String, MessageArg)*
  ): Message = Message(Nil, arguments.toMap, translations.toMap)

  def with0Args(translations: (Language, String)*): Message =
    Message(Nil, Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with1Arg[A <: MessageArg](translations: (Language, String)*): A => Message = arg1 =>
    Message(List(arg1), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with2Args[A <: MessageArg, B <: MessageArg](
    translations: (Language, String)*
  ): (A, B) => Message =
    (arg1, arg2) => Message(List(arg1, arg2), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with3Args[A <: MessageArg, B <: MessageArg, C <: MessageArg](
    translations: (Language, String)*
  ): (A, B, C) => Message =
    (
      arg1,
      arg2,
      arg3,
    ) => Message(List(arg1, arg2, arg3), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with4Args[A <: MessageArg, B <: MessageArg, C <: MessageArg, D <: MessageArg](
    translations: (Language, String)*
  ): (A, B, C, D) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
    ) => Message(List(arg1, arg2, arg3, arg4), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with5Args[
    A <: MessageArg,
    B <: MessageArg,
    C <: MessageArg,
    D <: MessageArg,
    E <: MessageArg,
  ](
    translations: (Language, String)*
  ): (A, B, C, D, E) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
      arg5,
    ) =>
      Message(List(arg1, arg2, arg3, arg4, arg5), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with6Args[
    A <: MessageArg,
    B <: MessageArg,
    C <: MessageArg,
    D <: MessageArg,
    E <: MessageArg,
    F <: MessageArg,
  ](
    translations: (Language, String)*
  ): (A, B, C, D, E, F) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
      arg5,
      arg6,
    ) =>
      Message(List(arg1, arg2, arg3, arg4, arg5, arg6), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with7Args[
    A <: MessageArg,
    B <: MessageArg,
    C <: MessageArg,
    D <: MessageArg,
    E <: MessageArg,
    F <: MessageArg,
    G <: MessageArg,
  ](
    translations: (Language, String)*
  ): (A, B, C, D, E, F, G) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
      arg5,
      arg6,
      arg7,
    ) =>
      Message(List(arg1, arg2, arg3, arg4, arg5, arg6, arg7), Map.empty, translations.toMap)

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with8Args[
    A <: MessageArg,
    B <: MessageArg,
    C <: MessageArg,
    D <: MessageArg,
    E <: MessageArg,
    F <: MessageArg,
    G <: MessageArg,
    H <: MessageArg,
  ](
    translations: (Language, String)*
  ): (A, B, C, D, E, F, G, H) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
      arg5,
      arg6,
      arg7,
      arg8,
    ) =>
      Message(
        List(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8),
        Map.empty,
        translations.toMap,
      )

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with9Args[
    A <: MessageArg,
    B <: MessageArg,
    C <: MessageArg,
    D <: MessageArg,
    E <: MessageArg,
    F <: MessageArg,
    G <: MessageArg,
    H <: MessageArg,
    I <: MessageArg,
  ](
    translations: (Language, String)*
  ): (A, B, C, D, E, F, G, H, I) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
      arg5,
      arg6,
      arg7,
      arg8,
      arg9,
    ) =>
      Message(
        List(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9),
        Map.empty,
        translations.toMap,
      )

  @deprecated("Use withNamedArgs with named placeholders instead of positional {$0}", "0.42.0")
  def with10Args[
    A <: MessageArg,
    B <: MessageArg,
    C <: MessageArg,
    D <: MessageArg,
    E <: MessageArg,
    F <: MessageArg,
    G <: MessageArg,
    H <: MessageArg,
    I <: MessageArg,
    J <: MessageArg,
  ](
    translations: (Language, String)*
  ): (A, B, C, D, E, F, G, H, I, J) => Message =
    (
      arg1,
      arg2,
      arg3,
      arg4,
      arg5,
      arg6,
      arg7,
      arg8,
      arg9,
      arg10,
    ) =>
      Message(
        List(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10),
        Map.empty,
        translations.toMap,
      )

end Message
