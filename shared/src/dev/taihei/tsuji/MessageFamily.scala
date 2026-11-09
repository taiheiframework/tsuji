package dev.taihei.tsuji

import scala.NamedTuple.AnyNamedTuple
import scala.annotation.publicInBinary
import scala.collection.mutable

/**
 * A group of related messages under one key prefix, written in one source language (English
 * unless the family says otherwise). Each message is a `val` defined with [[message]]; its key is
 * the prefix, a dot, and the val's name.
 *
 * {{{
 * object Orders extends MessageFamily("order"):
 *   val cancelled = message("Your order was cancelled")            // a Message
 *   val shipped   = message[(count: Int, on: LocalDate)](            // a MessageDefinition
 *     ".match {$count :plural} when one {One item shipped on {$on :date dateStyle=long}} " +
 *       "when * {{$count} items shipped on {$on :date dateStyle=long}}",
 *     Language.Dutch -> ".match {$count :plural} when one {Eén artikel verzonden op {$on :date dateStyle=long}} " +
 *       "when * {{$count} artikels verzonden op {$on :date dateStyle=long}}",
 *   )
 *
 * Orders.shipped(count = 3, on = today)   // a Message: key "order.shipped" and two arguments
 * }}}
 *
 * The prefix is one or more parts joined by dots, each an ASCII letter followed by letters,
 * digits and `_`; another prefix is a programming error and throws `IllegalArgumentException`
 * when the object is first used.
 */
abstract class MessageFamily(val prefix: String, val sourceLanguage: Language = Language.English):

  require(MessageKey.isPrefix(prefix), s"Not a message family prefix: '${Printable(prefix)}'")

  // Filled while the object initializes, one entry per message val. A lazy val, which the macro
  // cannot tell from a val, adds its entry when first used, from any thread: hence the lock.
  private val defined = mutable.ArrayBuffer.empty[MessageDefinition[?]]

  /** The family's messages, in the order they are defined. */
  final def definitions: List[MessageDefinition[?]] = defined.synchronized(defined.toList)

  // Called from the expansion of `message` in user code: its name and signature are binary API
  // within a major line, kept stable by @publicInBinary and checked by MiMa.
  @publicInBinary
  final private[tsuji] def define[A <: AnyNamedTuple](
    name: String,
    template: String,
    translations: Seq[(Language, String)],
    arguments: Vector[(String, MessageArg[?])],
  ): MessageDefinition[A] =
    val definition = new MessageDefinition[A](
      this,
      MessageKey.trusted(s"$prefix.$name"),
      template,
      translations.toList,
      arguments.map((n, a) => n -> a.asInstanceOf[MessageArg[Any]]),
    )
    defined.synchronized(defined += definition)
    definition

  end define

  /**
   * Defines a message of this family, as the right-hand side of a `val` in the family's body.
   * In a `def`, a nested object or a class it does not compile. Use a plain `val`: a `lazy val`
   * joins [[definitions]] only when first used, so `Catalogues.verify`, the export and a
   * registry that reads its messages from JSON do not see it before.
   *
   * Without a type argument it is a message without arguments, and the result is a [[Message]].
   * With a named tuple of argument types (`message[(count: Int, on: LocalDate)](...)`) the result
   * is a [[MessageDefinition]], applied to its arguments by name or by position:
   * `shipped(count = 3, on = today)`.
   *
   * The template and each inline translation (`Language.Dutch -> "..."`) that is a literal are
   * checked when the code compiles: valid MF2, every variable an argument, every function fitting
   * its argument's type, a `.match` with a `*` variant. An unused argument is a warning. A
   * translation that is not a literal is checked by `Catalogues.verify`.
   *
   * Inline translations are code: in Scala.js they are part of the bundle. A browser application
   * with many languages keeps them in catalogue files instead.
   */
  protected transparent inline def message[A <: AnyNamedTuple](
    inline template: String,
    inline translations: (Language, String)*
  ): Any = ${ MessageMacros.message[A]('this, 'template, 'translations) }

  override def toString: String = s"MessageFamily($prefix)"

end MessageFamily

/**
 * A message of a family with arguments: its key, its source-language template, its inline
 * translations and its typed arguments. Applied to its arguments it gives a [[Message]]; the
 * compiler checks their names and types: `shipped(count = 3, on = today)` or `shipped(3, today)`.
 *
 * The `apply` with the named, typed parameters is a refinement of the val's type that
 * [[MessageFamily.message]] writes; it reaches [[applyDynamic]], which is not called directly.
 */
final class MessageDefinition[A <: AnyNamedTuple] private[tsuji] (
  val family: MessageFamily,
  val key: MessageKey,
  val template: String,
  translationList: List[(Language, String)],
  private[tsuji] val arguments: Vector[(String, MessageArg[Any])])
  extends Selectable:

  /** The inline translations by language. */
  val translations: Map[Language, String] = translationList.toMap

  // As written, so that verify can see a language given twice.
  private[tsuji] def translationPairs: List[(Language, String)] = translationList

  /** The names of the arguments, in order. */
  def argumentNames: List[String] = arguments.map(_._1).toList

  /**
   * The message with these arguments, in the order of the definition. The compiler calls it for
   * the typed `apply` of the definition's val; called with anything else it is a programming
   * error and throws `IllegalArgumentException`.
   */
  def applyDynamic(name: String)(args: Any*): Message =
    require(
      name == "apply" && args.size == arguments.size,
      s"${key.value} takes ${argumentNames.mkString("(", ", ", ")")}",
    )
    new Message(key, Message.Body.Defined(this, args.toVector))

  // The message of a definition without arguments.
  // Binary API within a major line, as MessageFamily.define is.
  @publicInBinary
  private[tsuji] def constant: Message = new Message(key, Message.Body.Defined(this, Vector.empty))

  private[tsuji] lazy val kinds: Map[String, ArgKind] = arguments.map((n, a) => n -> a.kind).toMap

  private[tsuji] lazy val parsedSource: MessageFormat2.Message =
    MessageFormat2.parse(template).message

  // The inline translations that pass the template checks; checked once, at their first use.
  private[tsuji] lazy val checkedTranslations
    : Map[Language, Either[List[TemplateError], MessageFormat2.Message]] =
    translations.map: (language, t) =>
      val (errors, _) = MessageFormat2.check(t, kinds)
      language -> (if errors.isEmpty then Right(MessageFormat2.parse(t).message) else Left(errors))

  private[tsuji] def decodeArgs(args: JsonTree.Obj, context: MessageArg.Context)
    : Either[String, Vector[Any]] =
    arguments.foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)):
      case (acc, (name, arg)) =>
        acc.flatMap: values =>
          val value = args.get(name) match
            case Some(json) =>
              arg.decode(json, context).left.map(e => s"argument $name of ${key.value}: $e")
            case None       => arg.absent.toRight(s"argument $name of ${key.value} is missing")
          value.map(values :+ _)

  override def toString: String = s"MessageDefinition(${key.value})"

end MessageDefinition
