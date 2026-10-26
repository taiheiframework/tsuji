package dev.taihei.tsuji.ziojson

import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.util.control.NonFatal

import zio.json.*
import zio.json.ast.Json
// The only symbols of zio.json.internal this module uses (ADR 0006, J10): a decoder of its own
// must read from a RetractReader, and an encoder of its own writes into a Write. Both are named
// in the signatures of JsonDecoder.unsafeDecode and JsonEncoder.unsafeEncode, so no decoder or
// encoder can avoid them. Of RetractReader this module calls read, readChar, nextNonWhitespace
// and retract; of Write, write(String).
import zio.json.internal.{RetractReader, Write}

import dev.taihei.tsuji.{JsonTree, JsonTreeReader}

import com.github.plokhotnyuk.jsoniter_scala.core.*

// How the module reads and writes Tsuji's types with zio-json: through the core, so that there is
// one wire format and one set of bounds.
//
// Writing gives the core's bytes. Reading captures one JSON value from zio-json's reader, as the
// characters stand in the input, escapes included, and hands them to the core's codec as UTF-8.
// The capture is a loop without recursion. It bounds the nesting as the core does, so a value
// nested too deep is refused before it is held, and it refuses an unpaired surrogate, which has
// no UTF-8 form and would otherwise reach the core as something else. Everything else the core's
// grammar refuses (an escape of an unpaired surrogate, a number with a leading zero or a bare
// decimal point) is refused by the core itself, because it reads exactly what the input held. So
// this module opens no laxer route into the core.
//
// A value that is already a zio-json AST (`.as[Message]`) is checked against the core's bounds
// without recursion, then converted to the core's tree and decoded by the core's own decoders, as
// 5.0.x took a built AST once it was within the bounds.
private[ziojson] object Capture:

  val MaxDepth: Int = JsonTreeReader.MaxDepth

  val MaxNumberLength: Int = JsonTreeReader.MaxNumberLength

  val MaxScale: Int = JsonTreeReader.MaxScale

  val MaxMantissaBits: Int = JsonTreeReader.MaxMantissaBits

  val TooDeep: String = JsonTreeReader.TooDeep

  val NotUnicode = "a string that is not Unicode: an unpaired surrogate"

  val ScaleBeyond = s"a number with a scale beyond $MaxScale"

  val MantissaBeyond = s"a number with a mantissa of $MaxMantissaBits bits or more"

  /** How the core reads and writes one of its types: its codec, and its decoder of a tree. */
  final case class Route[A](codec: JsonValueCodec[A], fromTree: JsonTree => Either[String, A])

  def fail(message: String, trace: List[JsonError]): Nothing =
    throw JsonDecoder.UnsafeJson(JsonError.Message(message) :: trace)

  /** An encoder that writes the bytes of the core's codec. */
  def encoder[A](codec: JsonValueCodec[A]): JsonEncoder[A] = new JsonEncoder[A]:
    // A caller's indentation does not reach inside Tsuji's values, as with the core's codecs:
    // they are always compact.
    def unsafeEncode(
      a: A,
      indent: Option[Int],
      out: Write,
    ): Unit =
      out.write(writeToStringReentrant(a)(
        using codec
      ))

  /** A decoder that reads through the core: the captured value, or a built AST. */
  def decoder[A](route: Route[A]): JsonDecoder[A] = new JsonDecoder[A]:

    def unsafeDecode(trace: List[JsonError], in: RetractReader): A =
      val bytes = capture(trace, in)
      try
        readFromArrayReentrant(bytes, config(bytes.length))(
          using route.codec
        )
      catch
        case e: JsonReaderException => fail(e.getMessage, trace)
        case _: StackOverflowError  => fail("the JSON could not be read", trace)
        case NonFatal(_)            => fail("the JSON could not be read", trace)

    override def unsafeFromJsonAST(trace: List[JsonError], json: Json): A =
      problem(json).foreach(fail(_, trace))
      val result =
        try route.fromTree(tree(json))
        catch
          case _: StackOverflowError => Left("the JSON could not be read")
          case NonFatal(_)           => Left("the JSON could not be read")
      result.fold(fail(_, trace), identity)

  end decoder

  /** A field encoder that writes the name the core's key codec writes. */
  def fieldEncoder[A](name: A => String): JsonFieldEncoder[A] = new JsonFieldEncoder[A]:
    def unsafeEncodeField(a: A): String = name(a)

  /** A field decoder that reads the name as the core's key codec does. */
  def fieldDecoder[A](route: Route[A]): JsonFieldDecoder[A] = new JsonFieldDecoder[A]:
    def unsafeDecodeField(trace: List[JsonError], in: String): A =
      if !isUnicode(in) then fail(NotUnicode, trace)
      val result =
        try route.fromTree(JsonTree.Str(in))
        catch case NonFatal(_) => Left("the JSON could not be read")
      result.fold(fail(_, trace), identity)

  // The core reads exactly one value, the one captured, with no hex dump of it in an error.
  // zio-json bounds no string's length, so neither does the core here: no string in the value can
  // be longer than the value.
  private def config(length: Int): ReaderConfig = ReaderConfig
    .withAppendHexDumpToParseException(false)
    .withCheckForEndOfInput(true)
    .withMaxCharBufSize(math.max(ReaderConfig.maxCharBufSize, length))

  /**
   * One JSON value, read from `in` without recursion, as UTF-8 bytes of the characters the input
   * held. Arrays and objects nested deeper than [[MaxDepth]] and a top-level number longer than
   * [[MaxNumberLength]] are refused while they are read; so is an unpaired surrogate anywhere.
   * The capture checks the structure only as far as it needs to find the value's end; the core
   * reads what it captured, and refuses what is not JSON.
   */
  def capture(trace: List[JsonError], in: RetractReader): Array[Byte] =
    val out                       = new java.lang.StringBuilder
    var depth                     = 0
    var string                    = false
    var escaped                   = false
    // A high surrogate, read last, that still waits for its low one.
    var high                      = false
    def surrogates(c: Char): Unit =
      if high then
        if !Character.isLowSurrogate(c) then fail(NotUnicode, trace)
        high = false
      else if Character.isHighSurrogate(c) then high = true
      else if Character.isLowSurrogate(c) then fail(NotUnicode, trace)
    val first                     = in.nextNonWhitespace()
    surrogates(first)
    out.append(first)
    first match
      case '{' | '['                               => depth = 1
      case '"'                                     => string = true
      case c if c == '-' || (c >= '0' && c <= '9') =>
        token(out, trace, in, isNumberChar, MaxNumberLength, "a number")
      case 't' | 'f' | 'n' => token(out, trace, in, c => c >= 'a' && c <= 'z', 5, "a literal")
      case _               => fail("expected a JSON value", trace)
    while depth > 0 || string do
      val c = in.readChar()
      surrogates(c)
      out.append(c)
      if string then
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then string = false
      else
        c match
          case '"'       => string = true
          case '{' | '[' =>
            if depth >= MaxDepth then fail(TooDeep, trace)
            depth += 1
          case '}' | ']' => depth -= 1
          case _         => ()
    end while
    if high then fail(NotUnicode, trace)
    out.toString.getBytes(UTF_8)

  end capture

  // The rest of a token without an end of its own, a number or a literal: the characters that may
  // be part of it, at most `max` in all. The character after it is given back to the reader.
  private def token(
    out: java.lang.StringBuilder,
    trace: List[JsonError],
    in: RetractReader,
    part: Char => Boolean,
    max: Int,
    what: String,
  ): Unit =
    var more = true
    while more do
      val c = in.read()
      if c == -1 then more = false
      else if part(c.toChar) then
        if out.length >= max then fail(s"$what longer than $max characters", trace)
        out.append(c.toChar)
      else
        in.retract()
        more = false

  end token

  private def isNumberChar(c: Char): Boolean =
    (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

  /** Whether a string holds no unpaired surrogate. */
  def isUnicode(s: String): Boolean =
    var i  = 0
    var ok = true
    while ok && i < s.length do
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))
      then i += 2
      else if Character.isSurrogate(c) then ok = false
      else i += 1
    ok

  /**
   * What in a built AST the core would not read from text, found without recursion: arrays and
   * objects nested deeper than [[MaxDepth]], a number of a scale beyond [[MaxScale]] either way or
   * with a mantissa of [[MaxMantissaBits]] bits or more, or a string or a field's name with an
   * unpaired surrogate.
   */
  def problem(json: Json): Option[String] =
    val pending = mutable.Stack[(Json, Int)](json -> 0)
    var found   = Option.empty[String]
    while pending.nonEmpty && found.isEmpty do
      val (value, depth) = pending.pop()
      value match
        case Json.Obj(fields)   =>
          if depth >= MaxDepth then found = Some(TooDeep)
          else
            fields.foreach: (name, v) =>
              if !isUnicode(name) then found = Some(NotUnicode)
              pending.push(v -> (depth + 1))
        case Json.Arr(elements) =>
          if depth >= MaxDepth then found = Some(TooDeep)
          else elements.foreach(v => pending.push(v -> (depth + 1)))
        case Json.Num(n)        =>
          if math.abs(n.scale.toLong) > MaxScale then found = Some(ScaleBeyond)
          else if n.unscaledValue.bitLength >= MaxMantissaBits then found = Some(MantissaBeyond)
        case Json.Str(s)        => if !isUnicode(s) then found = Some(NotUnicode)
        case _                  => ()
      end match
    end while
    found

  end problem

  // The core's tree of an AST that problem found nothing wrong with, so at most MaxDepth levels
  // deep: this recursion is bounded by it.
  private def tree(json: Json): JsonTree = json match
    case Json.Obj(fields)   => JsonTree.Obj(fields.toVector.map((n, v) => n -> tree(v)))
    case Json.Arr(elements) => JsonTree.Arr(elements.toVector.map(tree))
    case Json.Str(s)        => JsonTree.Str(s)
    case Json.Num(n)        => JsonTree.Num(n)
    case Json.Bool(b)       => JsonTree.Bool(b)
    case Json.Null          => JsonTree.Null

end Capture
