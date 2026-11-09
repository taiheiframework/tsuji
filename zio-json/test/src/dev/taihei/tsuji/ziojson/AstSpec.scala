package dev.taihei.tsuji.ziojson

import scala.annotation.nowarn

import zio.json.*
import zio.json.ast.Json
import zio.test.*

import zio.Chunk

import dev.taihei.tsuji.*
import dev.taihei.tsuji.golden.Corpus
import dev.taihei.tsuji.ziojson.TsujiZioJson.given

import com.github.plokhotnyuk.jsoniter_scala.core.*

// A value given as a zio-json AST, `.as[Message]`: checked against the core's bounds without
// recursion, then decoded by the core's own decoders. The AST route reads what the byte route
// reads, and is never laxer on what the core bounds.
@nowarn("cat=deprecation")
object AstSpec extends ZIOSpecDefault:

  given Catalogues = Corpus.catalogues

  // A built AST `n` levels deep, without recursion.
  private def ast(n: Int): Json =
    var j: Json = Json.Num(1)
    var i       = 0
    while i < n do
      j = if i % 2 == 0 then Json.Arr(Chunk(j)) else Json.Obj("a" -> j)
      i += 1
    j

  // A built AST `n` levels deep in which every array holds the level below twice: the same
  // object, so that it takes n + 1 nodes and stands for 2^(n + 1) - 1 values.
  private def shared(n: Int): Json = Iterator.iterate[Json](Json.Num(1))(j => Json.Arr(Chunk(j, j))).drop(n).next()

  private def withX(x: Json): Json = Json.Obj("text" -> Json.Str("x"), "language" -> Json.Str("en"), "x" -> x)

  private def keptArg(x: Json): Json =
    Json.Obj("key" -> Json.Str("no.such"), "args" -> Json.Obj("a" -> x), "text" -> Json.Str("T"))

  private def number(unscaled: BigInt, scale: Int): Json = Json.Num(new java.math.BigDecimal(unscaled.bigInteger, scale))

  private def refused[A](result: Either[String, A], why: String): Boolean = result.left.exists(_.contains(why))

  def spec = suite("A zio-json AST read as Tsuji's types")(
    test("one deeper than the bound is refused, at the bound it is read") {
      // The text's own object is the first level.
      assertTrue(
        withX(ast(JsonTreeReader.MaxDepth - 1)).as[Text] == Right(Text("x", Language.English)),
        refused(withX(ast(JsonTreeReader.MaxDepth)).as[Text], "deeper than 64"),
        Capture.problem(ast(64)).isEmpty,
        Capture.problem(ast(65)).exists(_.contains("deeper than 64")),
      )
    },
    test("100000 levels are refused in every type, without recursion") {
      val deep = withX(ast(100000))
      assertTrue(
        refused(deep.as[Text], "deeper"),
        refused(deep.as[Displayable], "deeper"),
        refused(deep.as[Message], "deeper"),
        refused(deep.as[legacy.Message], "deeper"),
        refused(ast(100000).as[Language], "deeper"),
        refused(ast(100000).as[MessageKey], "deeper"),
      )
    },
    test("a subtree shared at every level is refused as the values it stands for, not walked through") {
      // 22 levels of two places each stand for 2^23 - 1 values, about twice the bound.
      val twice = shared(22)
      assertTrue(
        Capture.problem(twice).contains(Capture.TooLarge),
        refused(withX(twice).as[Text], "values and characters"),
        refused(keptArg(twice).as[Message], "values and characters"),
        refused(keptArg(twice).as[Displayable], "values and characters"),
        refused(withX(twice).as[legacy.Message], "values and characters"),
        refused(twice.as[Language], "values and characters"),
        Capture.problem(shared(20)).isEmpty,
        withX(shared(20)).as[Text] == Right(Text("x", Language.English)),
      )
    },
    test("a subtree shared at 60 levels is refused at once") {
      val huge = withX(shared(60))
      TimeBudget.within("a subtree shared at 60 levels", 1000)(huge.as[Text]): result =>
        assertTrue(refused(result, "values and characters"))
    },
    test("a string shared in many places counts its characters in every place") {
      val s      = Json.Str("x" * 1000)
      val over   = Json.Arr(Chunk.fill(5000)(s))
      val within = Json.Arr(Chunk.fill(4000)(s))
      assertTrue(
        refused(withX(over).as[Text], "values and characters"),
        withX(within).as[Text] == Right(Text("x", Language.English)),
      )
    },
    test("an AST of exactly the bound's values is read, one more is refused") {
      // The array itself is the first value.
      val at     = Json.Arr(Chunk.fill(Capture.MaxSize - 1)(Json.Null))
      val beyond = Json.Arr(Chunk.fill(Capture.MaxSize)(Json.Null))
      assertTrue(
        Capture.MaxSize == 4 * 1024 * 1024,
        Capture.problem(at).isEmpty,
        Capture.problem(beyond).contains(Capture.TooLarge),
      )
    },
    test("a number of a scale beyond the bound, or of a mantissa of 256 bits, is refused") {
      val max = JsonTreeReader.MaxScale
      assertTrue(
        keptArg(number(1, max)).as[Message].isRight,
        keptArg(number(1, -max)).as[Message].isRight,
        refused(keptArg(number(1, max + 1)).as[Message], "scale beyond"),
        refused(keptArg(number(1, -max - 1)).as[Message], "scale beyond"),
        refused(keptArg(number(1, Int.MinValue)).as[Displayable], "scale beyond"),
        keptArg(number(BigInt(2).pow(256) - 1 >> 1, 0)).as[Message].isRight,
        refused(keptArg(number(BigInt(2).pow(255), 0)).as[Message], "256 bits"),
        refused(keptArg(number(-BigInt(2).pow(255) - 1, 3)).as[Message], "256 bits"),
      )
    },
    test("an unpaired surrogate in a string or a name is refused") {
      assertTrue(
        refused(Json.Obj("text" -> Json.Str("a\ud800"), "language" -> Json.Str("en")).as[Text], "not Unicode"),
        refused(keptArg(Json.Obj("\udc00" -> Json.Num(1))).as[Message], "not Unicode"),
        refused(Json.Str("\ud800").as[Language], "not Unicode"),
        keptArg(Json.Obj("\ud83d\ude00" -> Json.Str("\ud83d\ude00"))).as[Message].isRight,
      )
    },
    test("every value of the corpus the byte route reads, the AST route reads to the same value") {
      // The AST is zio-json's reading of the input. Where that refuses, there is no AST to compare.
      val cases                                = Corpus.reads.flatMap: (name, input) =>
        input.fromJson[Json].toOption.toList.flatMap: json =>
          List(
            (name, "message", input.fromJson[Message], json.as[Message]),
            (name, "displayable", input.fromJson[Displayable], json.as[Displayable]),
            (name, "text", input.fromJson[Text], json.as[Text]),
            (name, "legacy", input.fromJson[legacy.Message], json.as[legacy.Message]),
          )
      // Compared by their JSON: a NaN argument is not equal to itself.
      def json(d: Either[String, Displayable]) = d.map(writeToString(_)(
        using summon[JsonValueCodec[Displayable]]
      ))
      val wrong                                = cases.collect:
        case (name, kind, a @ Right(_), b) if json(b) != json(a) => s"$kind|$name: bytes $a, AST $b"
      val stated                               = cases.count(_._3.isRight)
      assertTrue(cases.size > 800, stated > 250, wrong.isEmpty)
    },
    test("an AST that only the byte route refuses is a form the core does not read, or content after it") {
      // zio-json's own reader gives an AST for numbers that are not JSON and ignores what follows a
      // value: those are the AST's to answer for. Every other input the byte route refuses, the
      // AST route refuses too.
      val notJson = Set("int leading zero", "number 01", "trailing", "two values") ++
        Corpus.numbers.zipWithIndex.collect:
          case (n, i) if Set("01", "-01", "00", "1.", "-.5", ".5", "1.e5")(n) => s"number $i ($n)"
      val laxer   = Corpus.reads.flatMap: (name, input) =>
        input.fromJson[Json].toOption.toList.flatMap: json =>
          List(
            (input.fromJson[Message], json.as[Message]),
            (input.fromJson[Text], json.as[Text]),
          ).collect { case (Left(_), Right(_)) => name }
      assertTrue(laxer.nonEmpty, laxer.toSet.subsetOf(notJson))
    },
  )

end AstSpec
