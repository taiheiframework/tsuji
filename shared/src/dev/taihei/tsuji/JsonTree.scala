package dev.taihei.tsuji

import java.math.MathContext
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.util.control.NonFatal

import com.github.plokhotnyuk.jsoniter_scala.core.*

// A JSON value, where Tsuji needs one whole: a message's arguments before their types are known,
// the kept arguments of a key this program does not know, a text, a catalogue. Read by
// JsonTree.read and written by JsonTree.compact, both Tsuji's own and without recursion, over
// jsoniter-scala's tokens and bytes. Equality and hash codes are those of the JSON values they
// replace in 5.x (zio-json's), so that two messages compare as they did: the fields of an object
// in any order, its numbers by value and scale.
sealed abstract private[tsuji] class JsonTree:

  override def equals(other: Any): Boolean = (this, other) match
    case (JsonTree.Obj(l), JsonTree.Obj(r))   =>
      l.size == r.size && {
        val left = l.toMap
        r.forall((name, value) => left.get(name).contains(value))
      }
    case (JsonTree.Arr(l), JsonTree.Arr(r))   => l == r
    case (JsonTree.Str(l), JsonTree.Str(r))   => l == r
    case (JsonTree.Num(l), JsonTree.Num(r))   => l == r
    case (JsonTree.Bool(l), JsonTree.Bool(r)) => l == r
    case _                                    => false

  override def hashCode: Int = this match
    case JsonTree.Obj(fields)   => fields.foldLeft(0)((h, field) => h ^ field.hashCode)
    case JsonTree.Arr(elements) => elements.foldLeft(0)((h, e) => 31 * h + e.hashCode)
    case JsonTree.Str(value)    => value.hashCode
    case JsonTree.Num(value)    => value.hashCode
    case JsonTree.Bool(value)   => value.hashCode
    case _                      => 1

end JsonTree

private[tsuji] object JsonTree:

  final case class Obj(fields: Vector[(String, JsonTree)]) extends JsonTree:
    /** The value of the first field of that name, as 5.x read it. */
    def get(name: String): Option[JsonTree] = fields.collectFirst { case (`name`, v) => v }

  object Obj:
    val empty: Obj                              = Obj(Vector.empty)
    def apply(fields: (String, JsonTree)*): Obj = Obj(fields.toVector)

  final case class Arr(elements: Vector[JsonTree]) extends JsonTree
  final case class Str(value: String) extends JsonTree
  final case class Num(value: java.math.BigDecimal) extends JsonTree
  final case class Bool(value: Boolean) extends JsonTree

  // Its own equality: a pattern `case JsonTree.Null` compares with ==, which must not come back
  // to JsonTree's equals.
  case object Null extends JsonTree:
    override def equals(other: Any): Boolean = other.asInstanceOf[AnyRef] eq this
    override def hashCode: Int               = 1

end JsonTree

// Reads a JSON value into a JsonTree without recursion, within the bounds of 5.0.2's reader:
// arrays and objects nested at most MaxDepth deep, numbers of at most MaxNumberLength characters
// with an exponent of at most MaxExponentDigits digits and a mantissa of less than 256 bits. The
// open arrays and objects are kept on a heap stack, so the reader's stack does not grow with the
// input, and the depth is bounded so that whatever reads or writes the tree afterwards stays
// shallow too. Every bound is counted here, in Tsuji's own walk, whatever ReaderConfig the
// caller runs it under. It never skips: every value is read, so a member a decoder does not use
// is validated and counted against the bounds like any other.
private[tsuji] object JsonTreeReader:

  val MaxDepth = 64

  // The longest number token read: room for a mantissa of 256 bits (about 77 digits) with an
  // exponent and hundreds of leading or trailing zeros. A bound on each token keeps the work of
  // reading numbers in step with the input's length.
  val MaxNumberLength = 1100

  // A decimal number's scale is an Int; nine digits of exponent keep every scale far inside it,
  // whatever the token's other characters.
  val MaxExponentDigits = 9

  // The largest scale, either way, of a number read from text: an exponent of nine digits, moved
  // by at most the token's other digits.
  val MaxScale = 1000001100

  // zio-json's limit on a number's mantissa, which 5.x read with: below 256 bits.
  val MaxMantissaBits = 256

  val TooDeep = s"nests arrays and objects deeper than $MaxDepth"

  sealed private trait Open

  final private class OpenArr extends Open:
    val elements = Vector.newBuilder[JsonTree]

  final private class OpenObj(var name: String) extends Open:
    val fields = Vector.newBuilder[(String, JsonTree)]

  def read(in: JsonReader): JsonTree =
    val open   = mutable.Stack.empty[Open]
    // One buffer for the number tokens of this read.
    val token  = new Array[Byte](MaxNumberLength)
    var result = Option.empty[JsonTree]
    while result.isEmpty do
      // One value. An array or an object that is not empty is opened, and the loop reads its
      // first element; anything else is complete.
      var completed = Option.empty[JsonTree]
      in.nextToken() match
        case '{'                                     =>
          if open.size >= MaxDepth then in.decodeError(TooDeep)
          if in.isNextToken('}') then completed = Some(JsonTree.Obj.empty)
          else
            in.rollbackToken()
            open.push(OpenObj(unicode(in.readKeyAsString(), in)))
        case '['                                     =>
          if open.size >= MaxDepth then in.decodeError(TooDeep)
          if in.isNextToken(']') then completed = Some(JsonTree.Arr(Vector.empty))
          else
            in.rollbackToken()
            open.push(OpenArr())
        case '"'                                     =>
          in.rollbackToken()
          completed = Some(JsonTree.Str(unicode(in.readString(null), in)))
        case 't' | 'f'                               =>
          in.rollbackToken()
          completed = Some(JsonTree.Bool(in.readBoolean()))
        case 'n'                                     =>
          completed = Some(in.readNullOrError[JsonTree](JsonTree.Null, "expected a JSON value"))
        case b if b == '-' || (b >= '0' && b <= '9') => completed = Some(number(b, token, in))
        case _                                       => in.decodeError("expected a JSON value")
      end match
      // Hand a complete value to the array or object that holds it; one that is complete with it
      // is handed on in turn, until one still has elements to read, or none is open.
      while completed.isDefined do
        val value = completed.get
        completed = None
        if open.isEmpty then result = Some(value)
        else
          open.top match
            case arr: OpenArr =>
              arr.elements += value
              if !in.isNextToken(',') then
                if !in.isCurrentToken(']') then in.arrayEndOrCommaError()
                open.pop()
                completed = Some(JsonTree.Arr(arr.elements.result()))
            case obj: OpenObj =>
              obj.fields += obj.name -> value
              if in.isNextToken(',') then obj.name = unicode(in.readKeyAsString(), in)
              else
                if !in.isCurrentToken('}') then in.objectEndOrCommaError()
                open.pop()
                completed = Some(JsonTree.Obj(obj.fields.result()))
        end if
      end while
    end while
    result.get

  end read

  // A string as read, or a decoding error if it holds an unpaired surrogate: Tsuji refuses any
  // string that holds one. The check is Tsuji's own, so that what a decoder sees as a string is
  // Unicode whatever reader is underneath.
  private def unicode(s: String, in: JsonReader): String =
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))
      then i += 2
      else if Character.isSurrogate(c) then
        in.decodeError("a string that is not Unicode: malformed UTF-8")
      else i += 1
    s

  private def isNumberByte(b: Byte): Boolean =
    (b >= '0' && b <= '9') || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E'

  // A number: its bytes are collected first, at most MaxNumberLength of them, then checked and
  // read by jsoniter-scala as a number on its own, exactly (MathContext.UNLIMITED). In valid JSON
  // a number is followed by a byte that cannot be part of one, so the token is the whole number.
  private def number(
    first: Byte,
    token: Array[Byte],
    in: JsonReader,
  ): JsonTree =
    token(0) = first
    var length = 1
    var more   = true
    while more && in.hasRemaining() do
      val b = in.nextByte()
      if isNumberByte(b) then
        if length == MaxNumberLength then
          in.decodeError(s"a number longer than $MaxNumberLength characters")
        token(length) = b
        length += 1
      else
        in.rollbackToken()
        more = false
    if exponentDigits(token, length) > MaxExponentDigits then
      in.decodeError(s"a number with an exponent of more than $MaxExponentDigits digits")
    val value  =
      if isPlainInteger(token, length) then java.math.BigDecimal.valueOf(plainInteger(token, length))
      else
        try
          readFromArrayReentrant(java.util.Arrays.copyOf(token, length), NumberConfig)(
            using OneNumber
          )
        catch case NonFatal(_) => in.decodeError("not a number")
    if value.unscaledValue.bitLength >= MaxMantissaBits then
      in.decodeError(s"a number with a mantissa of $MaxMantissaBits bits or more")
    JsonTree.Num(value)

  end number

  // Most numbers in JSON are short integers, and reading each through a reader of its own costs
  // more than the rest of the walk, on Scala.js several times as much. An optional minus and at
  // most 18 digits, without a leading zero unless it is the only digit, is a JSON integer whose
  // value fits a Long; it is read here, to the same value. Anything else goes to the reader.
  private def isPlainInteger(token: Array[Byte], length: Int): Boolean =
    val from   = if token(0) == '-' then 1 else 0
    val digits = length - from
    var plain  = digits >= 1 && digits <= 18 && (token(from) != '0' || digits == 1)
    var i      = from
    while plain && i < length do
      plain = token(i) >= '0' && token(i) <= '9'
      i += 1
    plain

  private def plainInteger(token: Array[Byte], length: Int): Long =
    val negative = token(0) == '-'
    var i        = if negative then 1 else 0
    var value    = 0L
    while i < length do
      value = value * 10 + (token(i) - '0')
      i += 1
    if negative then -value else value

  // The digits of a number's exponent, without its sign and leading zeros: 0 for a number
  // without one.
  private def exponentDigits(token: Array[Byte], length: Int): Int =
    var i      = 0
    while i < length && token(i) != 'e' && token(i) != 'E' do i += 1
    i += 1
    if i < length && (token(i) == '+' || token(i) == '-') then i += 1
    while i < length && token(i) == '0' do i += 1
    var digits = 0
    while i < length && token(i) >= '0' && token(i) <= '9' do
      digits += 1
      i += 1
    digits

  // A number's token is short, and nothing of it goes into an error message.
  private val NumberConfig =
    ReaderConfig.withAppendHexDumpToParseException(false).withPreferredBufSize(MaxNumberLength + 2)

  private val OneNumber: JsonValueCodec[java.math.BigDecimal] =
    new JsonValueCodec[java.math.BigDecimal]:
      def decodeValue(in: JsonReader, default: java.math.BigDecimal): java.math.BigDecimal =
        in.readBigDecimal(null, MathContext.UNLIMITED, Int.MaxValue, MaxNumberLength + 1).bigDecimal
      def encodeValue(x: java.math.BigDecimal, out: JsonWriter): Unit = out.writeVal(BigDecimal(x))
      def nullValue: java.math.BigDecimal                             = null

end JsonTreeReader

// Writes a JsonTree as 5.x wrote it, without recursion: compactly for the wire, and in the
// layout of catalogue files for taihei-tsuji-tools. Tsuji renders the text itself, rather than
// with jsoniter-scala's writer, because the bytes are fixed by 5.x (ADR 0006, section 2), and
// jsoniter-scala escapes U+007F, which 5.x writes as it is. An unpaired surrogate, which no
// encoding can carry, is written as U+FFFD. So writing never throws.
private[tsuji] object JsonTreeWriter:

  /** The tree in one line, as messages, texts and catalogues travel. */
  def compact(tree: JsonTree): String = render(tree, pretty = false)

  /** The tree with each field on its own line, indented two spaces a level, `"name" : value`. */
  def pretty(tree: JsonTree): String = render(tree, pretty = true)

  /** Writes the tree into a jsoniter-scala writer, as one value, compactly. */
  def write(tree: JsonTree, out: JsonWriter): Unit = out.writeRawVal(compact(tree).getBytes(UTF_8))

  // What is left to write of an open array or object: its remaining elements or fields.
  final private class Pending(val iterator: Iterator[(Option[String], JsonTree)], val close: Char):
    var first = true

  private def render(tree: JsonTree, pretty: Boolean): String =
    val out                      = new java.lang.StringBuilder
    val pending                  = mutable.Stack.empty[Pending]
    def newline(): Unit          =
      out.append('\n')
      var i = pending.size
      while i > 0 do
        out.append("  ")
        i -= 1
    // Writes one value, opening an array or an object that is not empty.
    def value(v: JsonTree): Unit = v match
      case JsonTree.Obj(fields) if fields.nonEmpty     =>
        out.append('{')
        pending.push(Pending(fields.iterator.map((n, v) => (Some(n), v)), '}'))
      case JsonTree.Arr(elements) if elements.nonEmpty =>
        out.append('[')
        pending.push(Pending(elements.iterator.map(e => (None, e)), ']'))
      case JsonTree.Obj(_)                             => out.append("{}")
      case JsonTree.Arr(_)                             => out.append("[]")
      case JsonTree.Str(s)                             => string(s, out)
      case JsonTree.Num(n)                             => out.append(n.toString)
      case JsonTree.Bool(b)                            => out.append(b)
      case JsonTree.Null                               => out.append("null")
    value(tree)
    while pending.nonEmpty do
      val top = pending.top
      if top.iterator.hasNext then
        val (name, v) = top.iterator.next()
        if !top.first then out.append(',')
        top.first = false
        if pretty then newline()
        name.foreach: n =>
          string(n, out)
          out.append(if pretty then " : " else ":")
        value(v)
      else
        pending.pop()
        if pretty then newline()
        out.append(top.close)
    end while
    out.toString

  end render

  private val Hex = "0123456789abcdef"

  // A string as 5.x wrote it: `"` and `\` escaped, the control characters below U+0020 as \b, \f,
  // \n, \r, \t or \u00xx, everything else as it is, except an unpaired surrogate, as U+FFFD.
  private[tsuji] def string(s: String, out: java.lang.StringBuilder): Unit =
    out.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'                           => out.append("\\\"")
        case '\\'                          => out.append("\\\\")
        case '\b'                          => out.append("\\b")
        case '\f'                          => out.append("\\f")
        case '\n'                          => out.append("\\n")
        case '\r'                          => out.append("\\r")
        case '\t'                          => out.append("\\t")
        case _ if c < ' '                  =>
          out.append("\\u00").append(Hex.charAt(c >> 4)).append(Hex.charAt(c & 0xf))
        case _
          if Character.isHighSurrogate(c) && i + 1 < s.length &&
          Character.isLowSurrogate(s.charAt(i + 1)) =>
          out.append(c).append(s.charAt(i + 1))
          i += 1
        case _ if Character.isSurrogate(c) => out.append('�')
        case _                             => out.append(c)
      end match
      i += 1
    end while
    out.append('"')

  end string

end JsonTreeWriter

// The codecs of Tsuji's types that travel as JSON objects: read into a bounded tree, then decoded
// from it; written from a tree. A decoding error, and anything else that goes wrong while reading
// (a stack overflow included), reaches the caller as a JsonReaderException, the one exception
// jsoniter-scala's codecs throw for bad input.
private[tsuji] object TreeCodec:

  def apply[A](decode: JsonTree => Either[String, A], encode: A => JsonTree): JsonValueCodec[A] =
    new JsonValueCodec[A]:
      def decodeValue(in: JsonReader, default: A): A =
        guarded(in):
          decode(JsonTreeReader.read(in)) match
            case Right(a)    => a
            case Left(error) => in.decodeError(error)
      def encodeValue(x: A, out: JsonWriter): Unit   = JsonTreeWriter.write(encode(x), out)
      def nullValue: A                               = null.asInstanceOf[A]

  // The tree itself, for Catalogue.parse and the tests.
  val tree: JsonValueCodec[JsonTree] = apply(Right(_), identity)

  private[tsuji] def guarded[A](in: JsonReader)(read: => A): A =
    try read
    catch
      case e: JsonReaderException => throw e
      case _: StackOverflowError  => in.decodeError("the JSON could not be read")
      case NonFatal(_)            => in.decodeError("the JSON could not be read")

end TreeCodec
