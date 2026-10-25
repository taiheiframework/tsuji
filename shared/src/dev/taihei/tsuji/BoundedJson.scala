package dev.taihei.tsuji

import scala.collection.mutable

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
// deeper than MaxDepth. Messages and texts are input the application did not compile, and
// zio-json's own Json decoder calls itself once per level, so its stack use grows with the input.
// Here the open arrays and objects are kept on a heap stack instead, and the depth is bounded so
// that whatever reads or writes the result afterwards, recursively, stays shallow too. A message
// needs far less: nested messages stop at MessageArg's depth of 8, the kept arguments of an
// unknown key at 16 levels, each message two levels of JSON.
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

  private def tooDeep(trace: List[JsonError]): Nothing =
    Lexer.error(s"nests arrays and objects deeper than $MaxDepth", trace)

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
    // known to be no deeper than a value this decoder reads.
    override def unsafeFromJsonAST(trace: List[JsonError], json: Json): Json =
      if exceeds(json) then tooDeep(trace) else json

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
    case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
      Json.Num.decoder.unsafeDecode(trace, in)
    case other => Lexer.error(s"unexpected '$other'", trace)

  // Whether a built AST nests deeper than MaxDepth, found without recursion.
  private[tsuji] def exceeds(json: Json): Boolean =
    val pending = mutable.Stack[(Json, Int)](json -> 0)
    var deeper  = false
    while pending.nonEmpty && !deeper do
      val (value, depth) = pending.pop()
      value match
        case Json.Obj(fields)   =>
          if depth >= MaxDepth then deeper = true
          else fields.foreach((_, v) => pending.push(v -> (depth + 1)))
        case Json.Arr(elements) =>
          if depth >= MaxDepth then deeper = true
          else elements.foreach(v => pending.push(v -> (depth + 1)))
        case _                  => ()
    deeper

end BoundedJson
