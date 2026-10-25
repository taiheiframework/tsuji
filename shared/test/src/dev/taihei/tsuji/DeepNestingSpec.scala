package dev.taihei.tsuji

import zio.json.*
import zio.json.ast.Json
import zio.test.*

import zio.Chunk

import dev.taihei.tsuji.DeepNesting.*

object DeepNestingSpec extends ZIOSpecDefault:

  // Valid JSON of every kind, and invalid JSON of several kinds, for comparing with zio-json's
  // own reader.
  private val samples = List(
    "null",
    "true",
    "false",
    "0",
    "-1.5e3",
    "12345678901234567890.25",
    "\"\"",
    "\"a\\u00e9\\n\\\"b\"",
    "[]",
    "{}",
    " [ 1 , [ ] , { } , \"x\" ] ",
    """{"a":{"b":[1,{"c":null}]},"a":2}""",
    """{"key":"order.greeting","args":{"name":"Ann"},"text":"Hello Ann"}""",
    "",
    "[",
    "]",
    "[1,]",
    "[1 2]",
    "{\"a\" 1}",
    "{\"a\":1",
    "{\"a\":1,}",
    "{1:2}",
    "tru",
    "nul",
    "\"\\x\"",
    "x",
    "[1] 2",
    "01",
    "-",
    "{\"a\":[1,{\"b\":}]}",
  )

  // A built AST `n` levels deep, without recursion.
  private def ast(n: Int): Json =
    var j: Json = Json.Num(1)
    var i       = 0
    while i < n do
      j = if i % 2 == 0 then Json.Arr(Chunk(j)) else Json.Obj("a" -> j)
      i += 1
    j

  def spec = suite("Deeply nested messages and texts")(
    test("depths of 5000 and 100000 are a decoding error in every place, and never throw") {
      val missed = Depths.flatMap(notRefused)
      assertTrue(inputs(5000).size == 10, decodes("[]").size == 3, missed.isEmpty)
    },
    test("64 levels are read, 65 are refused") {
      // The text's own object is the first level.
      val within = s"""{"text":"x","language":"en","j":${arrays(BoundedJson.MaxDepth - 1)}}"""
      val beyond = s"""{"text":"x","language":"en","j":${arrays(BoundedJson.MaxDepth)}}"""
      assertTrue(
        within.fromJson[Text] == Right(Text("x", Language.English)),
        refusedForDepth(beyond.fromJson[Text]),
        arrays(64).fromJson[Json](
          using BoundedJson.decoder
        ).isRight,
        refusedForDepth(arrays(65).fromJson[Json](
          using BoundedJson.decoder
        )),
        refusedForDepth(objects(65).fromJson[Json](
          using BoundedJson.decoder
        )),
      )
    },
    test("a nested message within the bound still decodes and round-trips") {
      given Catalogues = Catalogues(Seq(Shapes, Orders))
      def nest(n: Int): Message = if n == 0 then Orders.cancelled else Shapes.nested("f", nest(n - 1))
      val unknown               = """{"key":"no.such","args":{"a":[[[[{"b":[1,2,{"c":"d"}]}]]]]},"text":"t"}"""
      assertTrue(
        nest(8).toJson.fromJson[Message] == Right(nest(8)),
        nest(8).toJson.fromJson[Displayable] == Right(nest(8)),
        unknown.fromJson[Message].map(_.toJson.fromJson[Json]) == Right(unknown.fromJson[Json]),
      )
    },
    test("the bounded reader reads and refuses what zio-json's reader does, with the same errors") {
      // Compared as written out again: Json's own equality is not reflexive for an object that
      // repeats a name, and the repeated name must survive as it does with zio-json's reader.
      val differ = samples.filter: s =>
        s.fromJson[Json](
          using BoundedJson.decoder
        ).map(_.toJson) != s.fromJson[Json].map(_.toJson)
      assertTrue(samples.size == 30, "[1,]".fromJson[Json].isLeft, differ.isEmpty)
    },
    test("a built AST deeper than the bound is refused without recursion, a shallow one is kept") {
      given Catalogues = Catalogues(Seq(Orders))
      val shallow = Json.Obj("text" -> Json.Str("x"), "language" -> Json.Str("en"), "j" -> ast(10))
      val deep    = Json.Obj("text" -> Json.Str("x"), "language" -> Json.Str("en"), "j" -> ast(100000))
      val text    = deep.as[Text]
      val message = deep.as[Displayable]
      assertTrue(
        shallow.as[Text] == Right(Text("x", Language.English)),
        refusedForDepth(text),
        refusedForDepth(message),
        !BoundedJson.exceeds(ast(64)),
        BoundedJson.exceeds(ast(65)),
      )
    },
  )

end DeepNestingSpec
