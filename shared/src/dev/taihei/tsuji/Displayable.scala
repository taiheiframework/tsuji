package dev.taihei.tsuji

import scala.annotation.nowarn

import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * Something that can be shown to a reader: a [[Message]] of a family, a [[Text]] in one language,
 * or a 4.x `legacy.Message`. Shown with [[localize]], in a language and with the application's
 * [[Catalogues]] in scope.
 */
abstract class Displayable private[tsuji] ():

  /** The text for a reader of `language`, through the fallback chain of `catalogues`. */
  def localize(
    language: Language
  )(
    using catalogues: Catalogues
  ): String

object Displayable:

  @nowarn("cat=deprecation")
  // One budget for the texts of a whole message tree, nested messages included.
  private[tsuji] def toTree(d: Displayable, budget: Budget = Budget.fresh()): JsonTree = d match
    case m: Message        => Message.toTree(m, withText = true, budget)
    case t: Text           => Text.toTree(t)
    case l: legacy.Message => legacy.Message.toTree(l)
    case other             => JsonTree.Str(other.toString)

  // A message has a key (or the 4.x translationTemplates), a text has a text and a language.
  private[tsuji] def decode(json: JsonTree, context: MessageArg.Context)
    : Either[String, Displayable] =
    json match
      case o: JsonTree.Obj if o.get("key").isDefined || o.get("translationTemplates").isDefined =>
        Message.decode(o, context)
      case o: JsonTree.Obj => Text.decode(o)
      case _               => Left("expected a message or a text as an object")

  /**
   * Writes a message, with its source-language text, or a text; reads a message, a text or a 4.x
   * message, a message's key looked up in `catalogues`. Encoding needs no catalogues, but the
   * codec is one type for both directions: a program that only writes passes `Catalogues()`.
   *
   * JSON nested deeper than 64 levels, or a number longer than 1100 characters or with an exponent
   * of more than 9 digits, is a decoding error. Every decoding error is a `JsonReaderException`.
   * The value is written compactly, whatever the `WriterConfig`'s indentation or `escapeUnicode`.
   */
  given (
    using catalogues: Catalogues
  ): JsonValueCodec[Displayable] =
    TreeCodec(decode(_, MessageArg.Context(catalogues, 0)), toTree(_))

end Displayable

/**
 * Text in one language that is not a message of a family: an exception's message, or a text a
 * server already translated. It travels as `{"text":"Disk full","language":"en"}` and shows as it
 * is, whatever the reader's language.
 */
final case class Text(value: String, language: Language) extends Displayable:

  /** The text itself, in whatever language it was written. */
  def localize(
    language: Language
  )(
    using catalogues: Catalogues
  ): String = value

  override def toString: String = value

object Text:

  /**
   * The throwable's message, in English. A throwable without one (`new RuntimeException()`) gives
   * its `toString`, the class name.
   */
  def of(throwable: Throwable): Text =
    Text(Option(throwable.getMessage).getOrElse(throwable.toString), Language.English)

  private[tsuji] def toTree(t: Text): JsonTree =
    JsonTree.Obj("text" -> JsonTree.Str(t.value), "language" -> JsonTree.Str(t.language.tag))

  private[tsuji] def decode(json: JsonTree): Either[String, Text] = json match
    case o: JsonTree.Obj =>
      for
        value <- o.get("text").toRight("a text needs \"text\"").flatMap {
          case JsonTree.Str(s) => Right(s)
          case _               => Left("\"text\" is not a string")
        }
        language <- o.get("language").toRight("a text needs \"language\"").flatMap(Language.fromTree)
      yield Text(value, language)
    case _               => Left("expected a text as an object")

  /**
   * `{"text":...,"language":...}`, the language as its tag; a built-in's name is read too. Reading
   * needs no registry, and is bounded as the codec of a [[Displayable]] is. The value is written
   * compactly, whatever the `WriterConfig`'s indentation or `escapeUnicode`.
   */
  given JsonValueCodec[Text] = TreeCodec(decode, toTree)

end Text

/**
 * A message of a [[MessageFamily]]: its key and its argument values, and nothing else. Its
 * translations live in the family and in [[Catalogues]]; [[localize]] picks the reader's.
 *
 * {{{
 * given Catalogues = Catalogues(Seq(Orders))
 * Orders.shipped(count = 3, on = today).localize(Language.Dutch)
 * }}}
 *
 * On the wire: `{"key":"order.shipped","args":{"count":3,"on":"2026-10-23"},"text":"..."}`, the
 * arguments as typed JSON values and `text` the message in its source language, for a reader that
 * does not know the key. A message read from 4.x JSON has the key `legacy` and renders as 4.x did.
 */
final class Message private[tsuji] (val key: MessageKey, private[tsuji] val body: Message.Body)
  extends Displayable:

  def localize(
    language: Language
  )(
    using catalogues: Catalogues
  ): String =
    catalogues.render(this, language)

  /** The message in its family's source language. */
  override def toString: String = Catalogues.sourceText(this)

  override def equals(other: Any): Boolean = other match
    case that: Message => key == that.key && body == that.body
    case _             => false

  override def hashCode: Int = (key, body).hashCode

end Message

object Message:

  @nowarn("cat=deprecation")
  private[tsuji] enum Body:
    // A message of a definition the program has, with its values in the order of its arguments.
    case Defined(definition: MessageDefinition[?], args: Vector[Any])
    // Read from JSON with a key this program does not know: shown as the writer's text.
    case Unresolved(args: JsonTree.Obj, text: String)
    // Read from the 4.x JSON shape.
    case Legacy(message: legacy.Message)

  private[tsuji] val LegacyKey: MessageKey = MessageKey.trusted("legacy")

  @nowarn("cat=deprecation")
  private[tsuji] def toTree(
    m: Message,
    withText: Boolean,
    budget: Budget = Budget.fresh(),
  ): JsonTree = m.body match
    case Body.Defined(definition, args) =>
      val fields = definition.arguments.zip(args).flatMap:
        case ((name, arg), value) =>
          arg.encode(value, budget) match
            case JsonTree.Null => None
            case json          => Some(name -> json)
      JsonTree.Obj(
        Vector("key" -> JsonTree.Str(m.key.value)) ++
        Option.when(fields.nonEmpty)("args" -> JsonTree.Obj(fields.toVector)) ++
        Option.when(withText)("text" -> JsonTree.Str(Catalogues.sourceText(m, budget)))
      )
    case Body.Unresolved(args, text)    =>
      JsonTree.Obj(
        Vector("key" -> JsonTree.Str(m.key.value)) ++
        Option.when(args.fields.nonEmpty)("args" -> args) ++
        Vector("text" -> JsonTree.Str(text))
      )
    case Body.Legacy(message)           => legacy.Message.toTree(message)

  @nowarn("cat=deprecation")
  private[tsuji] def decode(json: JsonTree, context: MessageArg.Context): Either[String, Message] =
    json match
      case o: JsonTree.Obj if o.get("translationTemplates").isDefined && o.get("key").isEmpty =>
        legacyWithinBounds(o).flatMap: _ =>
          legacy.Message.fromTree(o).map(l => new Message(LegacyKey, Body.Legacy(l)))
      case o: JsonTree.Obj                                                                    =>
        for
          key  <- o.get("key").toRight("a message needs \"key\"").flatMap(MessageKey.fromTree)
          args <- o.get("args") match
            case None                  => Right(JsonTree.Obj.empty)
            case Some(JsonTree.Null)   => Right(JsonTree.Obj.empty)
            case Some(a: JsonTree.Obj) => Right(a)
            case Some(_)               => Left("\"args\" is not an object")
          text <- o.get("text") match
            case None                  => Right(None)
            case Some(JsonTree.Str(t)) => Right(Some(t))
            case Some(_)               => Left("\"text\" is not a string")
          msg  <- context.catalogues.definition(key) match
            case Some(definition) => definition.decodeArgs(args, context).map(v =>
                new Message(key, Body.Defined(definition, v))
              )
            case None             =>
              text
                .toRight(s"unknown message key '${key.value}' and no \"text\" to show instead")
                .flatMap(t =>
                  keptArgsWithinBounds(args).map(_ => new Message(key, Body.Unresolved(args, t)))
                )
        yield msg
      case _ => Left("expected a message as an object")

  // The arguments of a message whose key this program does not know are kept as they came, to be
  // written out again. Hashing or writing JSON recurses per level, so their depth and size are
  // checked here, without recursion, and so are the names of an object, which comparing two
  // messages puts in a map.
  private val MaxKeptDepth = 16
  private val MaxKeptNodes = 100000

  private def keptArgsWithinBounds(args: JsonTree.Obj): Either[String, Unit] =
    val pending = scala.collection.mutable.Stack[(JsonTree, Int)](args -> 1)
    var nodes   = 0
    var problem = Option.empty[String]
    while pending.nonEmpty && problem.isEmpty do
      val (json, depth) = pending.pop()
      nodes += 1
      if depth > MaxKeptDepth then
        problem = Some(s"the arguments of an unknown message nest deeper than $MaxKeptDepth")
      else if nodes > MaxKeptNodes then
        problem = Some(s"the arguments of an unknown message hold more than $MaxKeptNodes values")
      else
        json match
          case JsonTree.Obj(fields)  =>
            // Comparing two objects builds a map of one of them.
            if fields.size > SharedHashes.Max && SharedHashes.exceed(fields.map(_._1)) then
              problem = Some(
                s"more than ${SharedHashes.Max} names in the arguments of an unknown message share a hash code"
              )
            else fields.foreach((_, v) => pending.push(v -> (depth + 1)))
          case JsonTree.Arr(element) => element.foreach(v => pending.push(v -> (depth + 1)))
          case _                     => ()
      end if
    end while
    problem.toLeft(())

  end keptArgsWithinBounds

  // A 4.x message brings its own templates: held to what a catalogue may hold.
  private val MaxLegacyTemplates = 1000

  private def legacyWithinBounds(o: JsonTree.Obj): Either[String, Unit] =
    o.get("translationTemplates") match
      case Some(JsonTree.Obj(templates)) =>
        val tooLong = templates.exists:
          case (_, JsonTree.Str(t)) => t.length > MessageFormat2.MaxTemplateLength
          case _                    => false
        if templates.size > MaxLegacyTemplates then
          Left(s"a 4.x message with more than $MaxLegacyTemplates templates")
        else if tooLong then
          Left(s"a 4.x template longer than ${MessageFormat2.MaxTemplateLength} characters")
        else Right(())
      case _                             => Left("\"translationTemplates\" is not an object")

  /**
   * Writes and reads a message. Writing gives its key and arguments and, with `withText`, the
   * default, also its text in the source language; `withText` applies to this message only:
   * messages nested in its arguments are always written with their text, so that a reader that
   * knows the outer key but not a nested one can still show it. The value is written compactly,
   * whatever the `WriterConfig`'s indentation or `escapeUnicode`.
   *
   * Reading looks the key up in `catalogues` to read the arguments with their types. A key it does
   * not know reads as the message's `text`, and is an error without one. The 4.x shape is read
   * too. Bounded as the codec of a [[Displayable]] is; every decoding error is a
   * `JsonReaderException`. Writing needs no catalogues, but the codec is one type for both
   * directions: a program that only writes passes `Catalogues()`.
   *
   * {{{
   * given Catalogues = Catalogues(Seq(Orders))
   * writeToString(Orders.cancelled)(using Message.codec(withText = false))
   * }}}
   */
  def codec(
    withText: Boolean = true
  )(
    using catalogues: Catalogues
  ): JsonValueCodec[Message] =
    TreeCodec(decode(_, MessageArg.Context(catalogues, 0)), toTree(_, withText))

  /** Writes a message with its text in the source language, and reads one: `codec()`. */
  given (
    using catalogues: Catalogues
  ): JsonValueCodec[Message] = codec()

end Message
