package dev.taihei.tsuji.ziojson

import zio.json.*
import zio.json.ast.Json
import zio.test.*

import dev.taihei.tsuji.*

// The module's reading compared with zio-json's own reader of JSON, as 5.0.2 compared its bounded
// reader with it: on valid JSON the two read the same value, and on invalid JSON both refuse. The
// module reads the JSON grammar strictly, as the core does, so it refuses what zio-json's lenient
// number grammar accepts, and escapes of unpaired surrogates (ADR 0006, as amended for 6.0.0);
// each of these is listed, and checked to be a difference.
object AgreementSpec extends ZIOSpecDefault:

  // The module's capture, read by the core into its tree: what any of the module's decoders reads
  // before it decodes a type.
  private val tree: JsonDecoder[JsonTree] = Capture.decoder(Capture.Route(TreeCodec.tree, Right(_)))

  // A value as the module reads it, and as zio-json reads it, each written by the core's writer:
  // zio-json's AST is taken into the core's tree through the module's AST route.
  private def module(input: String): Either[String, String] = tree.decodeJson(input).map(JsonTreeWriter.compact)

  private def zio(input: String): Either[String, String] =
    input.fromJson[Json].flatMap(tree.fromJsonAST).map(JsonTreeWriter.compact)

  private def agree(input: String): Boolean = (module(input), zio(input)) match
    case (Right(a), Right(b)) => a == b
    case (Left(_), Left(_))   => true
    case _                    => false

  private val samples = List(
    "null",
    "true",
    "false",
    "0",
    "-1.5e3",
    "12345678901234567890.25",
    "\"\"",
    "\"a\\u00e9\\n\\\"b\"",
    "\"\\ud83d\\ude00\"",
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

  def spec = suite("The module and zio-json's own reader")(
    test("read and refuse the same JSON") {
      val differ = samples.filterNot(agree)
      assertTrue(samples.size == 35, samples.count(s => module(s).isRight) > 10, differ.isEmpty)
    },
    test("odd numbers inside an array or an object: the same, but for the forms that are not JSON") {
      val odd = List("-", "1-2", "1e", "1.", "--1", "1.2.3", "01", "1e+", "-a", "1E5E5", "0.5e-3", "-.5", "1.e5", "00")
        .flatMap(n => List(s"[$n]", s"""{"a":$n}"""))
      // zio-json reads these; the module refuses them, as the core does.
      val notJson  = Set("1.", "01", "-.5", "1.e5", "00").flatMap(n => Set(s"[$n]", s"""{"a":$n}"""))
      val differ   = odd.filterNot(agree).toSet
      val zioReads = odd.filter(n => zio(n).isRight).toSet
      val modReads = odd.filter(n => module(n).isRight).toSet
      assertTrue(
        odd.size == 28,
        differ == notJson,
        zioReads == modReads ++ notJson,
        modReads == Set("[0.5e-3]", """{"a":0.5e-3}"""),
      )
    },
    test("an escape of an unpaired surrogate: zio-json reads it, the module refuses it") {
      val escapes = List("\"\\ud800\"", "\"\\udc00\"", "\"a\\ud800b\"", "\"\\udc00\\ud800\"", "{\"\\ud800\":1}")
      assertTrue(escapes.forall(e => zio(e).isLeft && module(e).isLeft), escapes.forall(e => e.fromJson[Json].isRight))
    },
    test("short numbers with extreme exponents: the same within nine digits of exponent") {
      val agreeing = List("1.5e999999999", "-0e-999999999", "1e-999999999", "77e+000000123").map(n => s"[$n]")
      val beyond   = List("1e2147483647", "1e-2147483648", "9e+2147483648").map(n => s"[$n]")
      assertTrue(agreeing.forall(agree), agreeing.forall(module(_).isRight), beyond.forall(module(_).isLeft))
    },
    test("the longest mantissa zio-json accepts still reads, the next is refused by both") {
      val longest = s"[${"9" * 76}]"
      val tooLong = s"[${"9" * 77}]"
      assertTrue(module(longest).isRight, agree(longest), module(tooLong).isLeft, zio(tooLong).isLeft)
    },
  )

end AgreementSpec
