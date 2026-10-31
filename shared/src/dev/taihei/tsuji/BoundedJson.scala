package dev.taihei.tsuji

import scala.collection.mutable
import scala.util.control.NonFatal

import zio.json.*
import zio.json.ast.Json
import zio.json.internal.{
  Lexer,
  RetractReader,
}

import zio.{
  Chunk,
  ChunkBuilder,
}

// Reads a JSON value into zio-json's AST without recursion, refusing arrays and objects nested
// deeper than MaxDepth and numbers longer than MaxNumberLength. Messages and texts are input the
// application did not compile, and zio-json's own Json decoder calls itself once per level, so
// its stack use grows with the input. Here the open arrays and objects are kept on a heap stack
// instead, and the depth is bounded so that whatever reads or writes the result afterwards,
// recursively, stays shallow too. A message needs far less: nested messages stop at MessageArg's
// depth of 8, the kept arguments of an unknown key at 16 levels, each message two levels of JSON.
private[tsuji] object BoundedJson:

  val MaxDepth = 64

  // An array or an object that is still open: what it holds so far, and the trace of its errors.
  sealed private trait Open:
    def trace: List[JsonError]

  final private class OpenArr(val trace: List[JsonError]) extends Open:
    val elements = ChunkBuilder.make[Json]()
    var index    = 0

  final private class OpenObj(val trace: List[JsonError]) extends Open:
    val fields = ChunkBuilder.make[(String, Json)]()
    var name   = ""

  private val TooDeep = s"nests arrays and objects deeper than $MaxDepth"

  private def tooDeep(trace: List[JsonError]): Nothing = Lexer.error(TooDeep, trace)

  /** zio-json's Json decoder, with the same values and errors, bounded to MaxDepth levels. */
  val decoder: JsonDecoder[Json] = new JsonDecoder[Json]:

    def unsafeDecode(trace: List[JsonError], in: RetractReader): Json =
      val open   = mutable.Stack.empty[Open]
      // The trace of the value about to be read.
      var at     = trace
      var result = Option.empty[Json]
      while result.isEmpty do
        // Read one value. An array or an object that is not empty is opened, and the loop reads
        // its first element; anything else is a complete value.
        val c         = in.nextNonWhitespace()
        var completed = Option.empty[Json]
        c match
          case '[' =>
            if open.size >= MaxDepth then tooDeep(at)
            if Lexer.firstArrayElement(in) then
              val arr = OpenArr(at)
              open.push(arr)
              at = JsonError.ArrayAccess(0) :: arr.trace
            else completed = Some(Json.Arr(Chunk.empty))
          case '{' =>
            if open.size >= MaxDepth then tooDeep(at)
            if Lexer.firstField(in) then
              val obj = OpenObj(at)
              obj.name = readName(obj.trace, in)
              open.push(obj)
              at = JsonError.ObjectAccess(obj.name) :: obj.trace
            else completed = Some(Json.Obj(Chunk.empty))
          case _   =>
            in.retract()
            completed = Some(scalar(c, at, in))
        end match
        // Hand a complete value to the array or object that holds it; one that is complete with
        // it is handed on in turn, until one still has elements to read, or none is open.
        while completed.isDefined do
          val value = completed.get
          completed = None
          if open.isEmpty then result = Some(value)
          else
            open.top match
              case arr: OpenArr =>
                arr.elements += value
                arr.index += 1
                if Lexer.nextArrayElement(arr.trace, in) then
                  at = JsonError.ArrayAccess(arr.index) :: arr.trace
                else
                  open.pop()
                  completed = Some(Json.Arr(arr.elements.result()))
              case obj: OpenObj =>
                obj.fields += obj.name -> value
                if Lexer.nextField(obj.trace, in) then
                  obj.name = readName(obj.trace, in)
                  at = JsonError.ObjectAccess(obj.name) :: obj.trace
                else
                  open.pop()
                  completed = Some(Json.Obj(obj.fields.result()))
          end if
        end while
      end while
      result.get

    end unsafeDecode

    // An AST that is already built is taken as it is, as zio-json's decoder does, once it is
    // known to hold nothing this decoder would not read: no deeper nesting, and no number of a
    // scale that a number within this reader's bounds cannot have.
    override def unsafeFromJsonAST(trace: List[JsonError], json: Json): Json =
      problem(json) match
        case Some(p) => Lexer.error(p, trace)
        case None    => json

  // A field's name and the colon after it, as zio-json reads them.
  private def readName(trace: List[JsonError], in: RetractReader): String =
    val name = Lexer.string(trace, in).toString
    val c    = in.nextNonWhitespace()
    if c != ':' then Lexer.error(s"expected ':' got '$c'", trace)
    name

  private def scalar(
    c: Char,
    trace: List[JsonError],
    in: RetractReader,
  ): Json = c match
    case 'n'       => Json.Null.decoder.unsafeDecode(trace, in)
    case 'f' | 't' => Json.Bool.decoder.unsafeDecode(trace, in)
    case '"'       => Json.Str.decoder.unsafeDecode(trace, in)
    case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => number(trace, in)
    case other => Lexer.error(s"unexpected '$other'", trace)

  // The longest number token read: room for a mantissa of 256 bits (about 77 digits) with an
  // exponent and hundreds of leading or trailing zeros. A message has no use for more, and a
  // bound on each token keeps the work of reading numbers in step with the input's length.
  val MaxNumberLength = 1100

  // A number: its characters are collected first, at most MaxNumberLength of them, and then read
  // by zio-json as the one element of an array, so that zio-json itself checks that the number is
  // the whole token. In valid JSON a number is followed by a character that cannot be part of
  // one, so the token is exactly the number zio-json reads in place; where the number ends before
  // the token does, zio-json refuses the character after it in place too.
  private def number(trace: List[JsonError], in: RetractReader): Json =
    val token = new java.lang.StringBuilder("[")
    var c     = in.read()
    while c != -1 && isNumberChar(c.toChar) do
      if token.length > MaxNumberLength then
        Lexer.error(s"a number longer than $MaxNumberLength characters", trace)
      token.append(c.toChar)
      c = in.read()
    if c != -1 then in.retract()
    if exponentDigits(token) > MaxExponentDigits then
      Lexer.error(s"a number with an exponent of more than $MaxExponentDigits digits", trace)
    token.append(']')
    // Whatever goes wrong while the number is read is a decoding error.
    val read  =
      try oneNumber.decodeJson(token)
      catch case NonFatal(_) => Left("(not a number)")
    read match
      case Right(n)    => Json.Num(n.head)
      case Left(error) =>
        Lexer.error(error.substring(error.indexOf('(') + 1).stripSuffix(")"), trace)

  end number

  // The digits of a number's exponent, without its sign and leading zeros: 0 for a number
  // without one. A decimal number's scale is an Int; nine digits of exponent keep every scale far
  // inside it, whatever the token's other characters.
  private val MaxExponentDigits = 9

  private def exponentDigits(token: java.lang.StringBuilder): Int =
    var i      = 0
    while i < token.length && token.charAt(i) != 'e' && token.charAt(i) != 'E' do i += 1
    i += 1
    if i < token.length && (token.charAt(i) == '+' || token.charAt(i) == '-') then i += 1
    while i < token.length && token.charAt(i) == '0' do i += 1
    var digits = 0
    while i < token.length && token.charAt(i) >= '0' && token.charAt(i) <= '9' do
      digits += 1
      i += 1
    digits

  private def isNumberChar(c: Char): Boolean =
    (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

  private val oneNumber = JsonDecoder.chunk[java.math.BigDecimal]

  // The largest scale, either way, of a number this reader takes from text: an exponent of nine
  // digits, moved by at most the token's other digits.
  val MaxScale = 1000001100

  private val ScaleBeyond = s"a number with a scale beyond $MaxScale"

  // What in a built AST this reader would not have read, found without recursion: nesting deeper
  // than MaxDepth, or a number of a scale beyond MaxScale.
  private[tsuji] def problem(json: Json): Option[String] =
    val pending = mutable.Stack[(Json, Int)](json -> 0)
    var found   = Option.empty[String]
    while pending.nonEmpty && found.isEmpty do
      val (value, depth) = pending.pop()
      value match
        case Json.Obj(fields)   =>
          if depth >= MaxDepth then found = Some(TooDeep)
          else fields.foreach((_, v) => pending.push(v -> (depth + 1)))
        case Json.Arr(elements) =>
          if depth >= MaxDepth then found = Some(TooDeep)
          else elements.foreach(v => pending.push(v -> (depth + 1)))
        case Json.Num(n)        =>
          if math.abs(n.scale.toLong) > MaxScale then found = Some(ScaleBeyond)
        case _                  => ()
    found

  end problem

end BoundedJson
