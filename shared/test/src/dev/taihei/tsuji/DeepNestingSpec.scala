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
    "1e400",
    "-0.0e-5",
    "123456789012345678901234567890123456789012345678901234567890123456789012345678",
    "1" + "0" * 1000,
    "1." + "0" * 1000,
    "{\"a\":[1,{\"b\":}]}",
    "{\"a\":1,\"b\":[1 2]}",
  )

  // A message whose argument is a list of messages: each level of it is three levels of JSON.
  object Lists extends MessageFamily("lists"):
    val all = message[(errors: List[Message])]("{$errors}")

  // Whether the bounded reader and zio-json's agree on an input: the same value, written out
  // again (Json's own equality is not reflexive for an object that repeats a name), or both an
  // error, the same one unless the input ends in a number inside an array or an object: there
  // zio-json, at the end of the input, reports the number's last digit as the character after it.
  private def agree(input: String): Boolean =
    (
      input.fromJson[Json](
        using BoundedJson.decoder
      ),
      input.fromJson[Json],
    ) match
      case (Right(a), Right(b)) => a.toJson == b.toJson
      case (Left(a), Left(b))   =>
        a == b || input.trim.lastOption.exists(c => c.isDigit && b.endsWith(s"got '$c')"))
      case _                    => false

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
    test("the deepest messages the library writes, nested in lists, read back") {
      given Catalogues = Catalogues(Seq(Lists, Shapes, Orders))
      def nest(n: Int): Message = if n == 0 then Orders.cancelled else Lists.all(List(nest(n - 1)))
      val deepest               = nest(MessageArg.MaxDepth)
      assertTrue(
        deepest.toJson.fromJson[Message] == Right(deepest),
        nest(MessageArg.MaxDepth + 1).toJson.fromJson[Message].left.exists(_.contains("nested deeper")),
      )
    },
    test("a number longer than the bound is refused in every place, quickly") {
      given Catalogues = Catalogues(Seq(Orders))
      val long    = "1" + "0" * 1000000
      val inputs  = List(
        long,
        s"""{"text":"x","language":"en","n":$long}""",
        s"""{"key":"no.such","args":{"a":$long},"text":"t"}""",
        s"""{"key":"order.greeting","args":{"name":$long}}""",
      )
      val start   = java.lang.System.nanoTime()
      val results = inputs.flatMap(i => List(i.fromJson[Message], i.fromJson[Displayable], i.fromJson[Text]))
      val millis  = (java.lang.System.nanoTime() - start) / 1000000
      val longest = "9" * BoundedJson.MaxNumberLength
      assertTrue(
        results.size == 12,
        results.forall(_.left.exists(_.contains(s"longer than ${BoundedJson.MaxNumberLength}"))),
        millis < 1000,
        longest.fromJson[Json](
          using BoundedJson.decoder
        ) == longest.fromJson[Json],
        ("1" + longest).fromJson[Json](
          using BoundedJson.decoder
        ).left.exists(_.contains("longer than")),
      )
    },
    test("an odd number inside an array or an object is read or refused as zio-json's reader does") {
      // At the top level zio-json reads the number's longest prefix and ignores the rest; no
      // message or text is a number, so only nested numbers matter, and these are compared. Where
      // a number ends before its token does, both refuse it, with the error at the number here
      // and at the character after it there.
      val odd    = List("-", "1-2", "1e", "1.", "--1", "1.2.3", "01", "1e+", "-a", "1E5E5", "0.5e-3")
        .flatMap(n => List(s"[$n]", s"""{"a":$n}"""))
      val differ = odd.filterNot: n =>
        (
          n.fromJson[Json](
            using BoundedJson.decoder
          ),
          n.fromJson[Json],
        ) match
          case (Right(a), Right(b)) => a.toJson == b.toJson
          case (Left(_), Left(_))   => true
          case _                    => false
      assertTrue(odd.size == 22, odd.count(_.fromJson[Json].isLeft) == 16, differ.isEmpty)
    },
    test("short numbers with extreme exponents are read or refused at once") {
      val extreme  = List("1e2147483647", "1e-2147483648", "1.5e999999999", "-0e-999999999", "9e+2147483648")
      val start    = java.lang.System.nanoTime()
      val results  = extreme.map(n =>
        s"[$n]".fromJson[Json](
          using BoundedJson.decoder
        )
      )
      val millis   = (java.lang.System.nanoTime() - start) / 1000000
      // Within nine digits of exponent the two readers agree; beyond, see the next test.
      val agreeing = List("1.5e999999999", "-0e-999999999", "1e-999999999", "77e+000000123")
      assertTrue(
        results.size == 5,
        results.map(_.isRight) == List(false, false, true, true, false),
        millis < 1000,
        agreeing.map(n => s"[$n]").forall(agree),
      )
    },
    // BoundedJson also turns anything that goes wrong while it reads a number into a decoding
    // error. No input known reaches that guard within the bounds, so no test does.
    test("an exponent of more than nine digits is a decoding error in every place, on both platforms") {
      given Catalogues = Catalogues(Seq(Orders))
      val numbers                                                      = List(
        "7.3333333333333333333e-2147483629",
        "1" * 76 + "e-2147483648",
        "1e-2147483648",
        "0e2147483647",
        "1e+0001000000000",
      )
      val places                                                       = numbers.flatMap: n =>
        List(
          s"""{"text":"x","language":"en","n":$n}""",
          s"""{"key":"no.such","args":{"a":[$n]},"text":"t"}""",
          s"""{"key":"order.greeting","args":{"name":$n}}""",
          s"""{"args":[],"translationTemplates":{"en":"x"},"n":$n}""",
        )
      def guarded(decode: => Either[String, Any]): Either[String, Any] =
        try decode
        catch case t: Throwable => Right(s"threw $t")
      val results                                                      = places.flatMap: p =>
        List(guarded(p.fromJson[Message]), guarded(p.fromJson[Displayable]), guarded(p.fromJson[Text]))
      val within = List("1e999999999", "1e-999999999", "1e+000000000999999999", "-0.5E-999999999")
        .map(n =>
          s"[$n]".fromJson[Json](
            using BoundedJson.decoder
          )
        )
      assertTrue(
        results.size == 60,
        results.forall(_.left.exists(_.contains("exponent of more than 9 digits"))),
        within.forall(_.isRight),
        within.head.map(_.toJson) == Right("[1E+999999999]"),
        within(1).map(_.toJson) == Right("[1E-999999999]"),
      )
    },
    test("the longest mantissa zio-json accepts still reads, with leading zeros up to the bound") {
      val longest = "9" * 76
      val tooLong = "9" * 77
      val padded  = "0." + "0" * 997 + "1"
      val read    = (n: String) =>
        s"[$n]".fromJson[Json](
          using BoundedJson.decoder
        )
      assertTrue(
        padded.length == 1000,
        read(longest) == s"[$longest]".fromJson[Json],
        read(longest).isRight,
        read(tooLong).left.exists(_.contains("256-bit mantissa")),
        read(padded).map(_.toJson) == Right("[1E-998]"),
      )
    },
    test("4 MB of numbers just under the bound are read in time that grows with their number") {
      // A number of the full length that zio-json reads to a value: its digits are leading zeros.
      val near   = "0." + "0" * (BoundedJson.MaxNumberLength - 3) + "1"
      val many   = List.fill(4 * 1024 * 1024 / (near.length + 1))(near).mkString("[", ",", "]")
      val start  = java.lang.System.nanoTime()
      val result = many.fromJson[Json](
        using BoundedJson.decoder
      )
      val millis = (java.lang.System.nanoTime() - start) / 1000000
      assertTrue(near.length == BoundedJson.MaxNumberLength, many.length > 4000000, result.isRight, millis < 5000)
    },
    test("the bounded reader reads and refuses what zio-json's reader does, with the same errors") {
      val differ = samples.filterNot(agree)
      assertTrue(samples.size == 34, "[1,]".fromJson[Json].isLeft, differ.isEmpty)
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
        BoundedJson.problem(ast(64)).isEmpty,
        BoundedJson.problem(ast(65)).exists(_.contains("deeper than 64")),
      )
    },
  )

end DeepNestingSpec
