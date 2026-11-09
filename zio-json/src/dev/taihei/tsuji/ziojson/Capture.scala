package dev.taihei.tsuji.ziojson

import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.util.control.NonFatal

import zio.json.*
import zio.json.ast.Json
// The only symbols of zio.json.internal this module uses (ADR 0006): a decoder of its own
// must read from a RetractReader, and an encoder of its own writes into a Write. Both are named
// in the signatures of JsonDecoder.unsafeDecode and JsonEncoder.unsafeEncode, so no decoder or
// encoder can avoid them. Through a RetractReader this module calls read, readChar and
// nextNonWhitespace, which are declared on its parent OneCharReader, and retract, the one method
// RetractReader declares itself; of Write it calls write(String).
import zio.json.internal.{RetractReader, Write}

import zio.Chunk

import dev.taihei.tsuji.{Catalogue, JsonTree, JsonTreeReader}

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

  val Unreadable = "the JSON could not be read"

  val ScaleBeyond = s"a number with a scale beyond $MaxScale"

  val MantissaBeyond = s"a number with a mantissa of $MaxMantissaBits bits or more"

  // The most values and characters a built AST may stand for, counting a subtree once for every
  // place it appears in: as many as the longest catalogue text the core reads has characters. A
  // caller can place one subtree in many places, so that a small AST stands for a tree too large
  // to walk; without a bound, converting it and decoding it would take time exponential in its
  // depth. Text cannot share, so the route from bytes needs no such bound.
  val MaxSize: Int = Catalogue.MaxLength

  val TooLarge =
    s"a built value of more than $MaxSize values and characters, " +
    "counting each place a shared part appears in"

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
        case _: StackOverflowError  => fail(Unreadable, trace)
        case NonFatal(_)            => fail(Unreadable, trace)

    override def unsafeFromJsonAST(trace: List[JsonError], json: Json): A =
      val read   = checked(json).fold(fail(_, trace), identity)
      val result =
        try route.fromTree(tree(json, read))
        catch
          case _: StackOverflowError => Left(Unreadable)
          case NonFatal(_)           => Left(Unreadable)
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
        catch case NonFatal(_) => Left(Unreadable)
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
   * unpaired surrogate; an AST that stands for more than [[MaxSize]] values and characters; and a
   * null where a value, a name or a member should be, or a collection that fails to give one.
   */
  def problem(json: Json): Option[String] = checked(json).left.toOption

  // A collection by its identity: a Chunk placed in several places is the same one in each.
  final private class Same(val chunk: AnyRef):

    override def equals(that: Any): Boolean = that match
      case same: Same => same.chunk eq chunk
      case _          => false

    override def hashCode: Int = System.identityHashCode(chunk)

  /** The members of every collection in an AST, as the walk read them, each collection once. */
  final private class Read:
    val arrays  = mutable.HashMap.empty[Same, Array[Json]]
    val objects = mutable.HashMap.empty[Same, Array[(String, Json)]]

  // What problem found, or the members it read for tree.
  private def checked(json: Json): Either[String, Read] =
    val read = Read()
    try walk(json, read).toLeft(read)
    catch case NonFatal(_) => Left(Unreadable)

  private def walk(json: Json, read: Read): Option[String] =
    val pending              = mutable.Stack[(Json, Int)](json -> 0)
    var found                = Option.empty[String]
    // The values and characters met so far, the root included. A subtree the caller placed in
    // several places is met, and counted, once for every place, as the conversion to the core's
    // tree and the core's decoders meet it. A collection counts its length before any member is
    // met and a string its characters before they are checked, so the walk stops at the bound.
    var size                 = 1L
    def count(n: Long): Unit =
      size += n
      if size > MaxSize then found = Some(TooLarge)
    // The members of an array or an object. A zio Chunk can share structure: concatenated with
    // itself it holds 2^n elements in n + 1 nodes, and a slice of it, or its iterator or its copy
    // into an array, can pass through every element it shares without yielding them. So members
    // are read by index only, which passes through one node per level, and at a level built by
    // `updated` through up to 256 indices it holds; zio copies a Chunk before 128 levels, so
    // before about 64 such levels (measured: about 3 us an index at the worst). That is what
    // zio's own foreach, iterator and toJson pay for each member too, and no access to a Chunk
    // costs less for every structure. Each collection is read once, however many places it is
    // in, and tree uses what was read.
    // Its length is an Int that can wrap: a negative length is refused, and one that wrapped to
    // a small number is read as that many members; a member that is null is refused.
    def members[M <: AnyRef : scala.reflect.ClassTag](
      all: Chunk[M],
      depth: Int,
      known: mutable.HashMap[Same, Array[M]],
    )(
      member: M => Json
    ): Unit                  =
      val length = all.length
      if length < 0 || length > MaxSize - size then found = Some(TooLarge)
      else
        count(length.toLong)
        val same   = Same(all)
        val before = known.get(same)
        val got    = before.getOrElse(new Array[M](length))
        var i      = 0
        while found.isEmpty && i < length do
          if before.isEmpty then got(i) = all(i)
          val m = got(i)
          if m == null then found = Some(Unreadable)
          else pending.push(member(m) -> (depth + 1))
          i += 1
        if found.isEmpty && before.isEmpty then known.update(same, got)
    end members
    while pending.nonEmpty && found.isEmpty do
      val (value, depth) = pending.pop()
      value match
        case null               => found = Some(Unreadable)
        case Json.Obj(fields)   =>
          if fields == null then found = Some(Unreadable)
          else if depth >= MaxDepth then found = Some(TooDeep)
          else
            members(fields, depth, read.objects): (name, v) =>
              if name == null then found = Some(Unreadable)
              else
                count(name.length.toLong)
                if found.isEmpty && !isUnicode(name) then found = Some(NotUnicode)
              v
        case Json.Arr(elements) =>
          if elements == null then found = Some(Unreadable)
          else if depth >= MaxDepth then found = Some(TooDeep)
          else members(elements, depth, read.arrays)(identity)
        case Json.Num(n)        =>
          // The bound is 5.0.x's for a built AST, the same either way; from text the lowest
          // scale is a little higher, but nothing costs more for the difference.
          if n == null then found = Some(Unreadable)
          else if math.abs(n.scale.toLong) > MaxScale then found = Some(ScaleBeyond)
          else if n.unscaledValue.bitLength >= MaxMantissaBits then found = Some(MantissaBeyond)
        case Json.Str(s)        =>
          if s == null then found = Some(Unreadable)
          else
            count(s.length.toLong)
            if found.isEmpty && !isUnicode(s) then found = Some(NotUnicode)
        case _                  => ()
      end match
    end while
    found

  end walk

  // The core's tree of an AST that problem found nothing wrong with, so at most MaxDepth levels
  // deep: this recursion is bounded by it. A collection's members are the ones problem read and
  // counted, never read from the Chunk again.
  private def tree(json: Json, read: Read): JsonTree = json match
    case Json.Obj(fields)   =>
      JsonTree.Obj(read.objects(Same(fields)).toVector.map((n, v) => n -> tree(v, read)))
    case Json.Arr(elements) => JsonTree.Arr(read.arrays(Same(elements)).toVector.map(tree(_, read)))
    case Json.Str(s)        => JsonTree.Str(s)
    case Json.Num(n)        => JsonTree.Num(n)
    case Json.Bool(b)       => JsonTree.Bool(b)
    case Json.Null          => JsonTree.Null

end Capture
