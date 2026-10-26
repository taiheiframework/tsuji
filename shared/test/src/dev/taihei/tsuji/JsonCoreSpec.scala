package dev.taihei.tsuji

import java.nio.charset.StandardCharsets.UTF_8

import scala.annotation.nowarn

import zio.test.*

import dev.taihei.tsuji.TestJson.{*, given}

import com.github.plokhotnyuk.jsoniter_scala.core.*

// The JSON core on jsoniter-scala (ADR 0006, section 1): the codecs
// throw nothing but JsonReaderException on any input, writing never throws and reads back, the
// codecs compose in a caller's own, and a catalogue read in the middle of a caller's decode
// leaves that decode intact.
@nowarn("cat=deprecation")
object JsonCoreSpec extends ZIOSpecDefault:

  given Catalogues = Catalogues(Seq(Orders, Shapes))

  // Whatever a read throws, by kind: a decoding error, or the class of anything else.
  private def outcome(read: => Any): String =
    try
      read
      "read"
    catch
      case _: JsonReaderException => "refused"
      case t: Throwable           => s"threw ${t.getClass.getName}"

  // Every reader of untrusted input: the codecs, from a String and from bytes, and Catalogue.parse
  // (its Left is the refusal; it must never throw).
  private def outcomes(input: String): List[(String, String)] =
    val bytes = input.getBytes(UTF_8)
    List(
      "Message" -> outcome(readFromString[Message](input)),
      "Displayable" -> outcome(readFromString[Displayable](input)),
      "Text" -> outcome(readFromString[Text](input)),
      "legacy" -> outcome(readFromString[legacy.Message](input)),
      "Language" -> outcome(readFromString[Language](input)),
      "MessageKey" -> outcome(readFromString[MessageKey](input)),
      "Message bytes" -> outcome(readFromArray[Message](bytes)),
      "Text bytes" -> outcome(readFromArray[Text](bytes)),
      "Catalogue" ->
      (outcome(Catalogue.parse(input)) match
        case "read" if Catalogue.parse(input).isLeft => "refused"
        case other                                   => other),
    )

  end outcomes

  private def thrown(input: String): List[String] =
    outcomes(input).collect { case (which, o) if o.startsWith("threw") => s"$which: $o" }

  // Inputs at each bound and one past it, in the places a reader reaches.
  private def bounded: List[String] =
    def arrays(n: Int)        = "[" * n + "1" + "]" * n
    def objects(n: Int)       = "{\"a\":" * n + "1" + "}" * n
    def text(j: String)       = s"""{"text":"x","language":"en","j":$j}"""
    def kept(a: String)       = s"""{"key":"no.such","args":{"a":$a},"text":"t"}"""
    def known(a: String)      = s"""{"key":"order.greeting","args":{"name":$a}}"""
    def legacyWith(j: String) = s"""{"args":[],"translationTemplates":{"en":"x"},"j":$j}"""
    val values                = List(
      arrays(63),
      arrays(64),
      objects(63),
      objects(64),
      "1" * 1100,
      "1" * 1101,
      "0." + "0" * 1097 + "1",
      "0." + "0" * 1098 + "1",
      "1e999999999",
      "1e1000000000",
      "9" * 76,
      "9" * 77,
      arrays(15),
      arrays(16),
      List.fill(10001)("1").mkString("[", ",", "]"),
      "\"\\ud800\"",
      "\"\\udc00\\ud800\"",
      "\"\\ud800\\u0041\"",
    )
    values.flatMap(v => List(v, text(v), kept(v), known(v), legacyWith(v)))

  end bounded

  // Short random inputs from the pieces JSON is made of, broken in every way.
  private val pieces = Vector(
    "{",
    "}",
    "[",
    "]",
    ",",
    ":",
    "\"",
    "\\",
    "\\u",
    "d800",
    "dc00",
    "0",
    "1",
    "9",
    "-",
    "+",
    ".",
    "e",
    "E",
    "null",
    "true",
    "false",
    "nul",
    "\"key\"",
    "\"text\"",
    "\"language\"",
    "\"args\"",
    "\"translationTemplates\"",
    "\"order.greeting\"",
    "\"no.such\"",
    "\"en\"",
    " ",
    "\n",
    "\u0000",
    "\u007f",
    "\ud800",
    "\u00e9",
    "1e999999999",
  )

  private val hostile: Gen[Any, String] = Gen.listOfBounded(0, 40)(Gen.elements(pieces*)).map(_.mkString)

  // Bytes that are not UTF-8 inside a string, and around it.
  private val malformed = List(
    Array[Byte](0x80.toByte),
    Array[Byte](0xc0.toByte, 0x80.toByte),
    Array[Byte](0xed.toByte, 0xa0.toByte, 0x80.toByte),
    Array[Byte](0xf4.toByte, 0x90.toByte, 0x80.toByte, 0x80.toByte),
    Array[Byte](0xff.toByte),
    Array[Byte](0xe2.toByte, 0x82.toByte),
    // Lead bytes of code points beyond U+10FFFF, which UTF-8 does not have.
    Array[Byte](0xf5.toByte, 0x80.toByte, 0x80.toByte, 0x80.toByte),
    Array[Byte](0xf7.toByte, 0xbf.toByte, 0xbf.toByte, 0xbf.toByte),
  )

  def spec = suite("The JSON core")(
    suite("on hostile input")(
      test("each bound at its limit and one past it: refused or read, never another exception") {
        val inputs  = bounded
        val results = inputs.map(i => i -> outcomes(i))
        assertTrue(
          inputs.size == 90,
          results.flatMap((i, rs) =>
            rs.collect { case (w, o) if o.startsWith("threw") => s"$w on ${i.take(60)}: $o" }
          ).isEmpty,
        )
      },
      test("each bound reads at its limit and refuses one past it, under any ReaderConfig") {
        // Under an unused field of a text, whose own object is the first level of nesting.
        def text(j: String)        = s"""{"text":"x","language":"en","j":$j}"""
        val pairs                  = List(
          ("[" * 63 + "1" + "]" * 63) -> ("[" * 64 + "1" + "]" * 64),
          ("0." + "0" * 1097 + "1") -> ("0." + "0" * 1098 + "1"),
          "1e999999999" -> "1e1000000000",
          // The mantissa: below 256 bits reads, 256 bits is refused, either sign.
          java.math.BigInteger.valueOf(2).pow(255).subtract(java.math.BigInteger.ONE).toString ->
          java.math.BigInteger.valueOf(2).pow(255).toString,
          java.math.BigInteger.valueOf(2).pow(255).negate.toString ->
          java.math.BigInteger.valueOf(2).pow(255).negate.subtract(java.math.BigInteger.ONE).toString,
        )
        // A caller's own configuration: no end-of-input check, small buffers, no hex dump. The
        // bounds are counted in Tsuji's walk, so none of it moves them.
        val configs                = List(
          ReaderConfig,
          ReaderConfig
            .withCheckForEndOfInput(false)
            .withPreferredBufSize(12)
            .withPreferredCharBufSize(12)
            .withAppendHexDumpToParseException(false),
        )
        val wrong                  =
          for
            config            <- configs
            (within, outside) <- pairs
            read    = outcome(readFromString[Text](text(within), config))
            refused = outcome(readFromString[Text](text(outside), config))
            if read != "read" || refused != "refused"
          yield s"${within.take(20)}: $read, ${outside.take(20)}: $refused"
        def streamed(json: String) =
          outcome(readFromStream[Text](new java.io.ByteArrayInputStream(text(json).getBytes(UTF_8)), configs(1)))
        val streamedWrong          =
          pairs.filterNot((within, outside) => streamed(within) == "read" && streamed(outside) == "refused")
        assertTrue(pairs.size == 5, wrong.isEmpty, streamedWrong.isEmpty)
      },
      test("every prefix of valid input is refused, never thrown") {
        val valid  = List(
          """{"key":"order.shipped","args":{"count":3,"on":"2026-10-23"},"text":"3 items"}""",
          """{"text":"Disk full","language":"en"}""",
          """{"args":["a"],"namedArgs":{"n":"v"},"translationTemplates":{"English":"x"}}""",
          """{"format":1,"language":"de","messages":{"a.b":"x"}}""",
        )
        val cut    = valid.flatMap(v => (0 until v.length).map(v.take))
        val threw  = cut.flatMap(thrown)
        val passed = cut.filter(c => outcomes(c).exists(_._2 == "read"))
        assertTrue(cut.size > 200, threw.isEmpty, passed.isEmpty)
      },
      test("malformed UTF-8 is refused by the codecs reading bytes") {
        val inputs  = malformed.flatMap: bad =>
          List(
            "{\"text\":\"".getBytes(UTF_8) ++ bad ++ "\",\"language\":\"en\"}".getBytes(UTF_8),
            "{\"key\":\"no.such\",\"text\":\"t\",\"args\":{\"".getBytes(UTF_8) ++ bad ++ "\":1}}".getBytes(UTF_8),
          )
        val results = inputs.flatMap(b => List(outcome(readFromArray[Text](b)), outcome(readFromArray[Message](b))))
        assertTrue(results.size == 32, results.forall(_ == "refused"))
      },
      test("random broken JSON makes no codec throw anything but a decoding error") {
        check(hostile)(input => assertTrue(thrown(input).isEmpty))
      } @@ TestAspect.samples(2000),
    ),
    suite("writing")(
      test("never throws, and what it writes reads back, an unpaired surrogate as U+FFFD") {
        val strings = List("a\ud800b", "\udc00", "\udc00\ud800", "x\ud83d", "ok \ud83d\ude00", "\u007f\u0000\u2028")
        val texts   = strings.map(s => Text(s, Language.English))
        val back    = texts.map(t => readFromString[Text](writeToString(t)))
        val greets  = strings.map(s => Orders.greeting(s))
        val again   = greets.map(m => readFromString[Message](writeToString(m)))
        val olds    = strings.map(s => legacy.Message(List(s), Map(s -> s), Map(Language.English -> s)))
        val oldBack = olds.map(m => readFromString[legacy.Message](writeToString(m)))
        val expect  = List("a\ufffdb", "\ufffd", "\ufffd\ufffd", "x\ufffd", "ok \ud83d\ude00", "\u007f\u0000\u2028")
        assertTrue(
          back.map(_.value) == expect,
          again.map(_.toString) == expect.map("Hello " + _),
          oldBack.map(_.args) == expect.map(List(_)),
          oldBack.map(_.namedArgs.keys.toList) == expect.map(List(_)),
        )
      },
      test("Tsuji's values compose in a caller's codec, as fields, elements and map values") {
        // A caller's own codec of a pair of a message and a text, written as an object.
        val pair       = new JsonValueCodec[(Message, Text)]:
          def decodeValue(in: JsonReader, default: (Message, Text)): (Message, Text) =
            if !in.isNextToken('{') then in.decodeError("expected an object")
            in.rollbackToken()
            in.isNextToken('{')
            val m =
              in.readKeyAsString(); summon[JsonValueCodec[Message]].decodeValue(in, null)
            if !in.isNextToken(',') then in.decodeError("expected ','")
            val t =
              in.readKeyAsString(); summon[JsonValueCodec[Text]].decodeValue(in, null)
            if !in.isNextToken('}') then in.decodeError("expected '}'")
            (m, t)
          def encodeValue(x: (Message, Text), out: JsonWriter): Unit                 =
            out.writeObjectStart()
            out.writeKey("m")
            summon[JsonValueCodec[Message]].encodeValue(x._1, out)
            out.writeKey("t")
            summon[JsonValueCodec[Text]].encodeValue(x._2, out)
            out.writeObjectEnd()
          def nullValue: (Message, Text)                                             = null
        val value      = (Orders.greeting("Ann"), Text("Disk full", Language.Dutch))
        val written    = writeToString(value)(
          using pair
        )
        val indented   = writeToString(value, WriterConfig.withIndentionStep(2))(
          using pair
        )
        val list       = List(Text("a", Language.English), Text("b", Language("nl-BE")))
        val accented   = Text("caf\u00e9 \u2028\u2029 \ud83d\ude00", Language.French)
        val byLanguage =
          Map(Language.English -> Text("hi", Language.English), Language("de") -> Text("hallo", Language("de")))
        assertTrue(
          written ==
          """{"m":{"key":"order.greeting","args":{"name":"Ann"},"text":"Hello Ann"},"t":{"text":"Disk full","language":"nl"}}""",
          readFromString(written)(
            using pair
          ) == value,
          // The caller's layout indents the caller's structure; Tsuji's values stay compact.
          indented.contains("\n  \"t\": {\"text\":\"Disk full\",\"language\":\"nl\"}"),
          readFromString(indented)(
            using pair
          ) == value,
          writeToString(list) == """[{"text":"a","language":"en"},{"text":"b","language":"nl-BE"}]""",
          writeToString(list).fromJson[List[Text]] == Right(list),
          writeToString(byLanguage) ==
          """{"English":{"text":"hi","language":"en"},"de":{"text":"hallo","language":"de"}}""",
          writeToString(byLanguage).fromJson[Map[Language, Text]] == Right(byLanguage),
          // Nor does the caller's escapeUnicode: non-ASCII, U+2028 and U+2029 stay as they are.
          writeToString(accented, WriterConfig.withEscapeUnicode(true)) == writeToString(accented),
          writeToString(accented).contains("caf\u00e9 \u2028\u2029 \ud83d\ude00"),
        )
      },
    ),
    suite("reading")(
      test("a catalogue parsed in the middle of a caller's decode leaves that decode intact") {
        // A caller's codec that, between two of its own values, parses a catalogue, as Tsuji's lazy
        // catalogue loading does when a message is shown inside a decode. Catalogue.parse must not
        // touch the pooled reader of the decode it runs in.
        val catalogue = """{"format":1,"language":"de","messages":{"order.cancelled":"Storniert"}}"""
        val around    = new JsonValueCodec[(String, Option[Catalogue], String)]:
          def decodeValue(in: JsonReader, default: (String, Option[Catalogue], String))  =
            if !in.isNextToken('[') then in.decodeError("expected '['")
            val first  = in.readString(null)
            if !in.isNextToken(',') then in.decodeError("expected ','")
            val parsed = Catalogue.parse(catalogue).toOption
            val second = in.readString(null)
            if !in.isNextToken(']') then in.decodeError("expected ']'")
            (first, parsed, second)
          def encodeValue(x: (String, Option[Catalogue], String), out: JsonWriter): Unit = ()
          def nullValue: (String, Option[Catalogue], String)                             = null
        val read      = readFromString("""["one",  "two"]""")(
          using around
        )
        assertTrue(
          read._1 == "one",
          read._2.map(_.messages.values.toList) == Some(List("Storniert")),
          read._3 == "two",
        )
      },
      test("an object's fields compare in any order, its numbers by value and scale, as in 5.x") {
        val a    = """{"key":"no.such","args":{"x":1,"y":[2.0,{"z":null}]},"text":"t"}"""
        val b    = """{"key":"no.such","args":{"y":[2.0,{"z":null}],"x":1},"text":"t"}"""
        val c    = """{"key":"no.such","args":{"x":1,"y":[2.00,{"z":null}]},"text":"t"}"""
        val d    = """{"key":"no.such","args":{"x":1,"x":1},"text":"t"}"""
        val e    = """{"key":"no.such","args":{"x":1,"y":1},"text":"t"}"""
        val read = List(a, b, c, d, e).map(readFromString[Message](_))
        assertTrue(
          read(0) == read(1),
          read(0).hashCode == read(1).hashCode,
          read(0) != read(2),
          read(3) == read(3),
          read(3) != read(4),
          writeToString(read(1)) == b,
        )
      },
    ),
  )

end JsonCoreSpec
