package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import scala.util.Try

/**
 * How a value of type `A` is a message argument: what a template sees of it, and how it is written
 * to and read from JSON. Instances exist for `String`, `Int`, `Long`, `Double`, `Float`,
 * `Boolean`, `LocalDate`, `LocalDateTime`, `LocalTime`, `Language`, a nested `Message`, `Text` or
 * `Displayable`, and `Option` and `List` of any of these.
 *
 * Your own type becomes an argument through one of these with [[MessageArg.as]]:
 *
 * {{{
 * final case class OrderId private (value: String)
 * object OrderId:
 *   def parse(text: String): Either[String, OrderId] =
 *     Either.cond(text.matches("[A-Z]{2}-[0-9]{1,9}"), new OrderId(text), "not an order id")
 *
 * given MessageArg[OrderId] = MessageArg.as[OrderId, String](_.value, OrderId.parse)
 * }}}
 *
 * The second function reads what came over the wire: it is the place for the type's own check.
 *
 * An `Option` argument may be missing on the wire: a reader then sees `None`, which a template
 * shows as empty text. That is how an argument added later keeps its key.
 */
final class MessageArg[A] private[tsuji] (
  private[tsuji] val kind: ArgKind,
  private[tsuji] val toFormat: (A, Language, Catalogues, Budget) => Any,
  private[tsuji] val encode: (A, Budget) => JsonTree,
  private[tsuji] val decode: (JsonTree, MessageArg.Context) => Either[String, A],
  private[tsuji] val absent: Option[A] = None)

object MessageArg:

  // What decoding a nested message needs: the reader's definitions, and how deep it already is.
  final private[tsuji] case class Context(catalogues: Catalogues, depth: Int)

  // A nested message deeper than this, or a list longer than that, is refused: they come from
  // input the application did not write.
  private[tsuji] val MaxDepth    = 8
  private[tsuji] val MaxElements = 10000

  // Longer than any ISO 8601 date or time.
  private val MaxTemporalLength = 64

  private def simple[A](
    kind: ArgKind,
    encode: A => JsonTree,
    decode: JsonTree => Either[String, A],
  ): MessageArg[A] =
    new MessageArg[A](
      kind,
      (
        a,
        _,
        _,
        _,
      ) => a,
      (a, _) => encode(a),
      (json, _) => decode(json),
    )

  private def number[A](
    encode: A => JsonTree,
    decode: BigDecimal => Option[A],
    name: String,
    special: String => Option[A] = (_: String) => None,
  ): MessageArg[A] =
    simple(
      ArgKind.Number,
      encode,
      {
        case JsonTree.Num(n) => decode(BigDecimal(n)).toRight(s"not $name: $n")
        case JsonTree.Str(s) => special(s).toRight(s"expected a number, not a string")
        case other           => Left(s"expected a number, not ${kindOf(other)}")
      },
    )

  // JSON has no NaN or infinity: they travel as the strings Java and JavaScript print for them.
  private def finite(d: Double): JsonTree =
    if d.isNaN || d.isInfinite then JsonTree.Str(d.toString)
    else JsonTree.Num(BigDecimal(d.toString).bigDecimal)

  private def nonFinite(s: String): Option[Double] = s match
    case "NaN"       => Some(Double.NaN)
    case "Infinity"  => Some(Double.PositiveInfinity)
    case "-Infinity" => Some(Double.NegativeInfinity)
    case _           => None

  private def temporal[A](
    kind: ArgKind,
    parse: String => A,
    name: String,
  ): MessageArg[A] =
    simple(
      kind,
      a => JsonTree.Str(a.toString),
      {
        case JsonTree.Str(s) if s.length <= MaxTemporalLength =>
          Try(parse(s)).toOption.toRight(s"not an ISO 8601 $name: '${Printable(s)}'")
        case JsonTree.Str(_) => Left(s"not an ISO 8601 $name: too long")
        case other           => Left(s"expected an ISO 8601 $name, not ${kindOf(other)}")
      },
    )

  private def kindOf(json: JsonTree): String = json match
    case _: JsonTree.Obj  => "an object"
    case _: JsonTree.Arr  => "an array"
    case _: JsonTree.Str  => "a string"
    case _: JsonTree.Num  => "a number"
    case _: JsonTree.Bool => "a boolean"
    case JsonTree.Null    => "null"

  /** A string, shown as it is. */
  given MessageArg[String] = simple(
    ArgKind.Text,
    JsonTree.Str(_),
    {
      case JsonTree.Str(s) => Right(s)
      case other           => Left(s"expected a string, not ${kindOf(other)}")
    },
  )

  /** An `Int`, a number to the template (`:number`, `:integer`, `:plural`). */
  given MessageArg[Int] = number[Int](
    i => JsonTree.Num(BigDecimal(i).bigDecimal),
    n => Option.when(n.isValidInt)(n.toInt),
    "an Int",
  )

  /** A `Long`, a number to the template. */
  given MessageArg[Long] = number[Long](
    l => JsonTree.Num(BigDecimal(l).bigDecimal),
    n => Option.when(n.isValidLong)(n.toLong),
    "a Long",
  )

  /**
   * A `Double`, a number to the template. NaN and the infinities travel as the strings `"NaN"`,
   * `"Infinity"` and `"-Infinity"`, since JSON has no number for them.
   */
  given MessageArg[Double] = number[Double](finite, n => Some(n.toDouble), "a Double", nonFinite)

  /** A `Float`, a number to the template, written as a `Double` is. */
  given MessageArg[Float] =
    number[Float](
      f => finite(f.toString.toDouble),
      n => Some(n.toFloat),
      "a Float",
      s => nonFinite(s).map(_.toFloat),
    )

  /** A boolean, a JSON `true` or `false`. */
  given MessageArg[Boolean] = simple(
    ArgKind.Text,
    JsonTree.Bool(_),
    {
      case JsonTree.Bool(b) => Right(b)
      case other            => Left(s"expected a boolean, not ${kindOf(other)}")
    },
  )

  /** A date for `:date` or `:datetime`, travelling as its ISO 8601 string (`2026-10-20`). */
  given MessageArg[LocalDate] = temporal(ArgKind.Date, LocalDate.parse, "date")

  /** A date and time for `:datetime`, `:date` or `:time`, travelling as its ISO 8601 string. */
  given MessageArg[LocalDateTime] = temporal(ArgKind.DateTime, LocalDateTime.parse, "date and time")

  /** A time for `:time` or `:datetime`, travelling as its ISO 8601 string. */
  given MessageArg[LocalTime] = temporal(ArgKind.Time, LocalTime.parse, "time")

  /** A language travels as its tag (`"nl-BE"`) and shows as its name in the reader's language. */
  given MessageArg[Language] = simple(
    ArgKind.Text,
    l => JsonTree.Str(l.tag),
    Language.fromTree,
  )

  private def nested[A <: Displayable](decodeNested: (JsonTree, Context) => Either[String, A])
    : MessageArg[A] =
    new MessageArg[A](
      ArgKind.Text,
      (
        a,
        language,
        catalogues,
        budget,
      ) =>
        catalogues.renderWithin(a, language, budget),
      (a, budget) => Displayable.toTree(a, budget),
      (json, context) =>
        if context.depth >= MaxDepth then Left(s"messages nested deeper than $MaxDepth")
        else decodeNested(json, context.copy(depth = context.depth + 1)),
    )

  /** A nested message, shown in the reader's language. */
  given MessageArg[Message] = nested(Message.decode)

  /** A nested text, shown as it is. */
  given MessageArg[Text] = nested((json, _) => Text.decode(json))

  /** A nested message or text. */
  given MessageArg[Displayable] = nested(Displayable.decode)

  /** An optional argument: missing on the wire is `None`, which shows as empty text. */
  given [A](
    using base: MessageArg[A]
  ): MessageArg[Option[A]] =
    new MessageArg[Option[A]](
      base.kind,
      (
        o,
        language,
        catalogues,
        budget,
      ) => o.fold("")(a => base.toFormat(a, language, catalogues, budget)),
      (o, budget) => o.fold(JsonTree.Null)(a => base.encode(a, budget)),
      (json, context) =>
        json match
          case JsonTree.Null => Right(None)
          case other         => base.decode(other, context).map(Some(_)),
      absent = Some(None),
    )

  /** A list, shown as its elements joined by `, `. */
  given [A](
    using base: MessageArg[A]
  ): MessageArg[List[A]] =
    new MessageArg[List[A]](
      ArgKind.Text,
      (
        as,
        language,
        catalogues,
        budget,
      ) =>
        // One reading of numbers for the whole list: an element given many times is read once,
        // and every element written is charged to the message's budget.
        val numbers = new MessageFormat2.Digits(budget)
        MessageFormat2.joined(
          as.map(a =>
            MessageFormat2.show(
              base.toFormat(a, language, catalogues, budget),
              language.tag,
              numbers,
            )
          ),
          budget,
        )
      ,
      (as, budget) => JsonTree.Arr(as.iterator.map(a => base.encode(a, budget)).toVector),
      (json, context) =>
        json match
          case JsonTree.Arr(elements) if elements.size <= MaxElements =>
            elements.foldLeft[Either[String, List[A]]](Right(Nil)): (acc, e) =>
              acc.flatMap(list => base.decode(e, context).map(_ :: list))
            .map(_.reverse)
          case JsonTree.Arr(_) => Left(s"a list longer than $MaxElements")
          case other           => Left(s"expected an array, not ${kindOf(other)}"),
    )

  /**
   * An argument of your own type `A`, through an argument type `B` that already has one: `to`
   * gives what a template sees and what is written, `from` reads it back or says why it cannot.
   * The functions that apply to `A` in a template are those of `B`; they are checked when the
   * tests call `Catalogues.verify`.
   */
  def as[A, B](
    to: A => B,
    from: B => Either[String, A],
  )(
    using base: MessageArg[B]
  ): MessageArg[A] =
    new MessageArg[A](
      base.kind,
      (
        a,
        language,
        catalogues,
        budget,
      ) => base.toFormat(to(a), language, catalogues, budget),
      (a, budget) => base.encode(to(a), budget),
      (json, context) => base.decode(json, context).flatMap(from),
      base.absent.flatMap(b => from(b).toOption),
    )

end MessageArg
