package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.DeepNesting.*
import dev.taihei.tsuji.TestJson.*

object DeepNestingSpec extends ZIOSpecDefault:

  // The reader of the tree itself.
  private def tree(json: String): Either[String, JsonTree] =
    json.fromJson[JsonTree](
      using TreeCodec.tree
    )

  private def compact(json: String): Either[String, String] = tree(json).map(JsonTreeWriter.compact)

  // Valid JSON of every kind, as the reader reads it and the writer writes it again; the forms of
  // numbers are those of java.math.BigDecimal.toString, as in 5.x.
  private val valid = List(
    "null" -> "null",
    "true" -> "true",
    "false" -> "false",
    "0" -> "0",
    "-1.5e3" -> "-1.5E+3",
    "12345678901234567890.25" -> "12345678901234567890.25",
    "\"\"" -> "\"\"",
    "\"a\\u00e9\\n\\\"b\"" -> "\"a\u00e9\\n\\\"b\"",
    "[]" -> "[]",
    "{}" -> "{}",
    " [ 1 , [ ] , { } , \"x\" ] " -> "[1,[],{},\"x\"]",
    """{"a":{"b":[1,{"c":null}]},"a":2}""" -> """{"a":{"b":[1,{"c":null}]},"a":2}""",
    """{"key":"order.greeting","args":{"name":"Ann"},"text":"Hello Ann"}""" ->
    """{"key":"order.greeting","args":{"name":"Ann"},"text":"Hello Ann"}""",
    "1e400" -> "1E+400",
    "-0.0e-5" -> "0.000000",
  )

  // Invalid JSON, refused.
  private val invalid = List(
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
    "1" + "0" * 1000,
    "1." + "0" * 1000,
    "{\"a\":[1,{\"b\":}]}",
    "{\"a\":1,\"b\":[1 2]}",
    // A mantissa of 256 bits, one more than 5.x read.
    "123456789012345678901234567890123456789012345678901234567890123456789012345678",
  )

  // A message whose argument is a list of messages: each level of it is three levels of JSON.
  object Lists extends MessageFamily("lists"):
    val all = message[(errors: List[Message])]("{$errors}")

  def spec = suite("Deeply nested messages and texts")(
    test("depths of 5000 and 100000 are a decoding error in every place, and never throw") {
      val missed = Depths.flatMap(notRefused)
      assertTrue(inputs(5000).size == 10, decodes("[]").size == 3, missed.isEmpty)
    },
    test("64 levels are read, 65 are refused") {
      // The text's own object is the first level.
      val within = s"""{"text":"x","language":"en","j":${arrays(JsonTreeReader.MaxDepth - 1)}}"""
      val beyond = s"""{"text":"x","language":"en","j":${arrays(JsonTreeReader.MaxDepth)}}"""
      assertTrue(
        within.fromJson[Text] == Right(Text("x", Language.English)),
        refusedForDepth(beyond.fromJson[Text]),
        tree(arrays(64)).isRight,
        refusedForDepth(tree(arrays(65))),
        refusedForDepth(tree(objects(65))),
      )
    },
    test("a nested message within the bound still decodes and round-trips") {
      given Catalogues = Catalogues(Seq(Shapes, Orders))
      def nest(n: Int): Message = if n == 0 then Orders.cancelled else Shapes.nested("f", nest(n - 1))
      val unknown               = """{"key":"no.such","args":{"a":[[[[{"b":[1,2,{"c":"d"}]}]]]]},"text":"t"}"""
      assertTrue(
        nest(8).toJson.fromJson[Message] == Right(nest(8)),
        nest(8).toJson.fromJson[Displayable] == Right(nest(8)),
        unknown.fromJson[Message].map(_.toJson) == Right(unknown),
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
      val longest = "0." + "0" * (JsonTreeReader.MaxNumberLength - 3) + "1"
      assertTrue(
        results.size == 12,
        results.forall(_.left.exists(_.contains(s"longer than ${JsonTreeReader.MaxNumberLength}"))),
        millis < 1000,
        longest.length == JsonTreeReader.MaxNumberLength,
        compact(longest) == Right("1E-1098"),
        compact("-" + longest).left.exists(_.contains("longer than")),
      )
    },
    test("a number that is not JSON is refused, also where 5.x read it") {
      // 5.x read 01, 1. and their kind (ADR 0006, section 2); 6.0.0 reads RFC 8259.
      val odd    = List(
        "-",
        "1-2",
        "1e",
        "1.",
        "--1",
        "1.2.3",
        "01",
        "1e+",
        "-a",
        "1E5E5",
        "0.5e-3",
        "-01",
        "00",
        "-.5",
        ".5",
        "1.e5",
        "+1",
      )
      val places = odd.flatMap(n => List(s"[$n]", s"""{"a":$n}"""))
      val read   = places.filter(p => tree(p).isRight)
      assertTrue(places.size == 34, read == List("[0.5e-3]", """{"a":0.5e-3}"""))
    },
    test("short numbers with extreme exponents are read or refused at once") {
      val extreme = List("1e2147483647", "1e-2147483648", "1.5e999999999", "-0e-999999999", "9e+2147483648")
      val start   = java.lang.System.nanoTime()
      val results = extreme.map(n => tree(s"[$n]"))
      val millis  = (java.lang.System.nanoTime() - start) / 1000000
      val written =
        List("1.5e999999999", "-0e-999999999", "1e-999999999", "77e+000000123", "1e0000000000005", "1E+05", "0e0")
      assertTrue(
        results.size == 5,
        results.map(_.isRight) == List(false, false, true, true, false),
        millis < 1000,
        written.forall(n => compact(s"[$n]") == Right(s"[${new java.math.BigDecimal(n)}]")),
      )
    },
    test("an exponent of more than nine digits is a decoding error in every place, on both platforms") {
      given Catalogues = Catalogues(Seq(Orders))
      val numbers = List(
        "7.3333333333333333333e-2147483629",
        "1" * 76 + "e-2147483648",
        "1e-2147483648",
        "0e2147483647",
        "1e+0001000000000",
      )
      val places  = numbers.flatMap: n =>
        List(
          s"""{"text":"x","language":"en","n":$n}""",
          s"""{"key":"no.such","args":{"a":[$n]},"text":"t"}""",
          s"""{"key":"order.greeting","args":{"name":$n}}""",
          s"""{"args":[],"translationTemplates":{"en":"x"},"n":$n}""",
        )
      val results = places.flatMap: p =>
        List(guarded(p.fromJson[Message]), guarded(p.fromJson[Displayable]), guarded(p.fromJson[Text]))
      val within  = List("1e999999999", "1e-999999999", "1e+000000000999999999", "-0.5E-999999999").map(n =>
        compact(s"[$n]")
      )
      assertTrue(
        results.size == 60,
        results.forall(_.left.exists(_.contains("exponent of more than 9 digits"))),
        within.forall(_.isRight),
        within.head == Right("[1E+999999999]"),
        within(1) == Right("[1E-999999999]"),
      )
    },
    test("the longest mantissa 5.x read still reads, with leading zeros up to the bound") {
      val longest = "9" * 76
      val tooLong = "9" * 77
      val padded  = "0." + "0" * 997 + "1"
      assertTrue(
        padded.length == 1000,
        compact(s"[$longest]") == Right(s"[$longest]"),
        compact(s"[$tooLong]").left.exists(_.contains("mantissa of 256 bits")),
        compact(s"[$padded]") == Right("[1E-998]"),
      )
    },
    test("4 MB of numbers just under the bound are read in time that grows with their number") {
      // A number of the full length that reads to a value: its digits are leading zeros.
      val near   = "0." + "0" * (JsonTreeReader.MaxNumberLength - 3) + "1"
      val many   = List.fill(4 * 1024 * 1024 / (near.length + 1))(near).mkString("[", ",", "]")
      val start  = java.lang.System.nanoTime()
      val result = tree(many)
      val millis = (java.lang.System.nanoTime() - start) / 1000000
      assertTrue(near.length == JsonTreeReader.MaxNumberLength, many.length > 4000000, result.isRight, millis < 5000)
    },
    test("valid JSON reads and writes as 5.x wrote it; invalid JSON is refused") {
      val wrong = valid.filterNot((in, out) => compact(in) == Right(out))
      val read  = invalid.filter(i => tree(i).isRight)
      assertTrue(valid.size == 15, invalid.size == 19, wrong.isEmpty, read.isEmpty)
    },
  )

end DeepNestingSpec
