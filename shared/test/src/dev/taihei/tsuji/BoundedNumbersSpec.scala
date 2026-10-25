package dev.taihei.tsuji

import java.math.BigInteger

import zio.json.*
import zio.json.ast.Json
import zio.test.*

// Numbers beyond the reader's bounds, in a catalogue and in an AST built elsewhere: both are
// refused as a decoding error, on both platforms.
object BoundedNumbersSpec extends ZIOSpecDefault:

  private val extreme = List(
    "7.3333333333333333333e-2147483629",
    "1" * 76 + "e-2147483648",
    "1e-2147483648",
    "0e2147483647",
    "11111111111111111111.1e-2147483647",
    "1e+0001000000000",
  )

  private def guarded[A](parse: => Either[A, Any]): Either[String, Any] =
    try parse.left.map(_.toString)
    catch case t: Throwable => Right(s"threw $t")

  private def catalogue(note: String, message: String = "\"Eins\""): String =
    s"""{"format":1,"language":"de","note":$note,"messages":{"numbers.one":$message}}"""

  def spec = suite("Numbers out of bounds")(
    test("a catalogue with an extreme exponent anywhere is refused, and parse never throws") {
      val inputs  = extreme.flatMap: n =>
        List(
          s"""{"format":$n,"language":"de","messages":{}}""",
          catalogue("1", n),
          catalogue(n),
        )
      val results = inputs.map(i => guarded(Catalogue.parse(i)))
      assertTrue(
        results.size == 18,
        results.forall(_.left.exists(_.contains("exponent of more than 9 digits"))),
      )
    },
    test("a catalogue with a number of a million digits is refused at once") {
      val start  = java.lang.System.nanoTime()
      val result = guarded(Catalogue.parse(catalogue("1" + "0" * 1000000)))
      val millis = (java.lang.System.nanoTime() - start) / 1000000
      assertTrue(result.left.exists(_.contains("longer than 1100 characters")), millis < 1000)
    },
    test("4M characters of catalogue numbers just under the bound are read in time that grows with them") {
      val near   = "0." + "0" * (BoundedJson.MaxNumberLength - 3) + "1"
      val many   = List.fill((Catalogue.MaxLength - 200) / (near.length + 1))(near).mkString("[", ",", "]")
      val input  = catalogue(many)
      val start  = java.lang.System.nanoTime()
      val result = guarded(Catalogue.parse(input))
      val millis = (java.lang.System.nanoTime() - start) / 1000000
      assertTrue(input.length <= Catalogue.MaxLength, input.length > 4000000, result.isRight, millis < 5000)
    },
    test("a valid catalogue parses as before") {
      val parsed = Catalogue.parse(catalogue("1e999999999"))
      assertTrue(
        parsed.map(_.language) == Right(Language("de")),
        parsed.map(_.messages.map((k, v) => k.value -> v)) == Right(Map("numbers.one" -> "Eins")),
        parsed.map(_.problems) == Right(Nil),
      )
    },
    test("an AST built elsewhere with a number of an out-of-bounds scale is refused") {
      given Catalogues = Catalogues(Seq(Orders))
      // An AST from another reader, with a number beyond the bound.
      val viaZio        = """{"text":"x","language":"en","n":7e-2147483648}""".fromJson[Json]
      val built         = Json.Num(new java.math.BigDecimal(BigInteger.valueOf(7), Int.MinValue))
      def text(n: Json) = Json.Obj("text" -> Json.Str("x"), "language" -> Json.Str("en"), "n" -> n)
      def msg(n: Json)  = Json.Obj("key" -> Json.Str("no.such"), "args" -> Json.Obj("a" -> n), "text" -> Json.Str("t"))
      val refused       = List(
        viaZio.flatMap(_.as[Text]),
        viaZio.flatMap(_.as[Displayable]),
        text(built).as[Text],
        text(built).as[Displayable],
        msg(built).as[Message],
        msg(built).as[Displayable],
      )
      val edge          = BoundedJson.MaxScale
      val accepted      = List(edge, -edge).map: scale =>
        msg(Json.Num(new java.math.BigDecimal(BigInteger.valueOf(7), scale))).as[Message]
      val beyond        = msg(Json.Num(new java.math.BigDecimal(BigInteger.valueOf(7), edge + 1))).as[Message]
      assertTrue(
        viaZio.isRight,
        refused.forall(_.left.exists(_.contains(s"scale beyond ${BoundedJson.MaxScale}"))),
        accepted.forall(_.isRight),
        beyond.left.exists(_.contains("scale beyond")),
      )
    },
  )

end BoundedNumbersSpec
