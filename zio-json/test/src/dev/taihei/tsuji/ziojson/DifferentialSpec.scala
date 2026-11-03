package dev.taihei.tsuji.ziojson

import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.golden.Corpus
import dev.taihei.tsuji.ziojson.Sides.*
import dev.taihei.tsuji.ziojson.TsujiZioJson.given

// The differential test of ADR 0006: one corpus, valid and hostile, read as bytes by the
// core's codecs and by this module's decoders. Each input reads to the same value on both sides,
// or both refuse it. Two kinds of input differ, both properties of the entry point a caller reads
// with rather than of Tsuji's grammar, and each is checked to differ exactly as described:
//
//  - content after the value: zio-json's fromJson reads one value and ignores the rest, where
//    jsoniter-scala's readFromArray, by default, refuses it;
//  - malformed UTF-8: zio-json's reader of bytes turns it into U+FFFD before any decoder sees a
//    character, where the core refuses it.
object DifferentialSpec extends ZIOSpecDefault:

  given Catalogues = Corpus.catalogues

  private def arrays(n: Int)  = "[" * n + "]" * n
  private def objects(n: Int) = """{"a":""" * n + "1" + "}" * n

  private def kept(a: String)   = s"""{"key":"no.such","args":{"a":$a},"text":"T"}"""
  private def unused(a: String) = s"""{"key":"golden.text.plain","x":$a}"""
  private def inText(a: String) = s"""{"text":"x","language":"en","x":$a}"""

  // Every place a value nested in a message or a text can be.
  private def everywhere(name: String, a: String): List[(String, String)] = List(
    s"$name, kept" -> kept(a),
    s"$name, unused" -> unused(a),
    s"$name, in a text" -> inText(a),
    s"$name, legacy" -> s"""{"args":[],"translationTemplates":{"en":"x"},"x":$a}""",
  )

  // 5.0.2's samples of valid and invalid JSON, compared there with zio-json's own reader.
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
    "1e400",
    "-0.0e-5",
    "1" + "0" * 1000,
    "1." + "0" * 1000,
    "{\"a\":[1,{\"b\":}]}",
    "{\"a\":1,\"b\":[1 2]}",
  )

  private val numbers = List(
    "9" * 76,
    "9" * 77,
    "1" + "0" * 1099,
    "0." + "0" * (JsonTreeReader.MaxNumberLength - 3) + "1",
    "0." + "0" * (JsonTreeReader.MaxNumberLength - 2) + "1",
    "1e999999999",
    "1e1000000000",
    "1e-999999999",
    "1e+0001000000000",
    "7.3333333333333333333e-2147483629",
    "1" * 76 + "e-2147483648",
    "0e2147483647",
    "0." + "0" * 997 + "1",
    "1e2147483648",
    "-",
    "-0",
    "01",
    "1.",
    "-.5",
    "1.e5",
    "0x10",
    "NaN",
    "1E5E5",
  )

  private val hostile: List[(String, String)] =
    everywhere("64 levels", arrays(JsonTreeReader.MaxDepth - 1)) ++
    everywhere("65 levels", arrays(JsonTreeReader.MaxDepth)) ++
    everywhere("65 objects", objects(JsonTreeReader.MaxDepth)) ++
    everywhere("100000 arrays", "[" * 100000) ++
    everywhere("100000 objects", """{"a":""" * 100000) ++
    everywhere("100000 closed", arrays(100000)) ++
    samples.zipWithIndex.flatMap((s, i) => everywhere(s"sample $i", s)) ++
    numbers.zipWithIndex.flatMap((n, i) =>
      everywhere(s"number $i", n) ++ everywhere(s"number $i in an array", s"[$n]")
    ) ++
    List(
      "escaped high in a text" -> """{"text":"a\ud800b","language":"en"}""",
      "escaped low in a name" -> """{"key":"no.such","args":{"\udc00":1},"text":"T"}""",
      "escaped pair in a name" -> """{"key":"no.such","args":{"\ud83d\ude00":1},"text":"T"}""",
      "escaped high then raw" -> "{\"text\":\"\\ud83d\u00e9\",\"language\":\"en\"}",
      "raw control character" -> "{\"text\":\"a\u0001b\",\"language\":\"en\"}",
      "raw DEL" -> "{\"text\":\"a\u007fb\",\"language\":\"en\"}",
      "raw line separator" -> "{\"text\":\"a\u2028b\",\"language\":\"en\"}",
      "raw pair" -> "{\"text\":\"\ud83d\ude00\",\"language\":\"en\"}",
      "form feed between tokens" -> "{\"text\":\"a\",\f\"language\":\"en\"}",
      "vertical tab between tokens" -> "{\"text\":\"a\",\u000b\"language\":\"en\"}",
      "nbsp between tokens" -> "{\"text\":\"a\",\u00a0\"language\":\"en\"}",
      "bom" -> "\ufeff{\"text\":\"a\",\"language\":\"en\"}",
      "null" -> "null",
      "empty" -> "",
      "only whitespace" -> " \n\r\t ",
      "a number" -> "1",
      "a string" -> "\"en\"",
      "an array of a text" -> """[{"text":"a","language":"en"}]""",
      "escaped quote in a name" -> """{"te\"xt":"a","language":"en"}""",
      "backslash at the end of a string" -> """{"text":"a\\""",
      "escape at the end of the input" -> """{"text":"a\""",
      "unicode escape cut short" -> """{"text":"\u00e""",
      "long string" -> s"""{"text":"${"x" * 70000}","language":"en"}""",
      "long name" -> s"""{"key":"no.such","args":{"${"n" * 70000}":1},"text":"T"}""",
    )

  // Every prefix of a message, cut between characters: each is refused on both sides, but the
  // whole message.
  private val message = "{\"key\":\"golden.text.say\",\"args\":{\"s\":\"x\\u00e9 \u00e9\ud83d\ude00\"},\"text\":\"T\"}"

  private val prefixes: List[(String, String)] =
    val cuts = (0 to message.length).filterNot(i => i > 0 && i < message.length && message.charAt(i).isLowSurrogate)
    cuts.toList.map(i => s"prefix $i" -> message.take(i))

  // Content after the value: the value, and what follows it.
  private val after: List[(String, String, String)] = List(
    ("letter", """{"key":"golden.text.plain"}""", "x"),
    ("second value", """{"key":"golden.text.plain"}""", " {}"),
    ("text then garbage", """{"text":"a","language":"en"}""", "]]]"),
    ("string then more", "\"en\"", "\"nl\""),
    ("trailing comma", """{"text":"a","language":"en"}""", ","),
  )

  // In the corpus: content after the value, which the list above covers.
  private val corpusAfter = Map(
    "trailing" -> ("""{"key":"golden.text.plain"}""", "x"),
    "two values" -> ("""{"key":"golden.text.plain"}""", " {}"),
  )

  // Malformed UTF-8 in a text's string, as bytes.
  private val malformed: List[(String, Array[Byte])] =
    def inString(b: Int*) =
      bytes("{\"text\":\"a") ++ b.map(_.toByte).toArray ++ bytes("b\",\"language\":\"en\"}")
    List(
      "continuation byte" -> inString(0x80),
      "overlong slash" -> inString(0xc0, 0xaf),
      "overlong three bytes" -> inString(0xe0, 0x80, 0xaf),
      "CESU high surrogate" -> inString(0xed, 0xa0, 0x80),
      "CESU pair" -> inString(0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80),
      "beyond U+10FFFF" -> inString(0xf4, 0x90, 0x80, 0x80),
      "F7" -> inString(0xf7, 0xbf, 0xbf, 0xbf),
      "truncated three bytes" -> inString(0xe2, 0x82),
      "FF" -> inString(0xff),
      "F8" -> inString(0xf8, 0x88, 0x80, 0x80, 0x80),
    )

  end malformed

  private def differences(results: List[(String, String, String, String)]) =
    results.collect:
      case (name, kind, core, module) if core != module => s"$kind|$name:\n  core   $core\n  module $module"

  def spec = suite("The module reads every input as the core does")(
    test("the corpus: messages, displayables, texts and 4.x messages") {
      val results = Corpus.reads.filterNot((name, _) => corpusAfter.contains(name)).flatMap: (name, input) =>
        Sides.objects(bytes(input)).map(
          (
            kind,
            c,
            m,
          ) => (name, kind, c, m)
        )
      val read    = results.count(_._3 != Refused)
      assertTrue(results.size > 900, read > 250, differences(results).isEmpty)
    },
    test("the corpus: languages and keys, as values and as names") {
      val results = Corpus.values.flatMap((name, input) =>
        Sides.values(bytes(input)).map(
          (
            k,
            c,
            m,
          ) => (name, k, c, m)
        )
      ) ++
        Corpus.keys.flatMap((name, input) =>
          Sides.keys(bytes(input)).map(
            (
              k,
              c,
              m,
            ) => (name, k, c, m)
          )
        )
      assertTrue(results.size == 42, results.count(_._3 != Refused) > 10, differences(results).isEmpty)
    },
    test("hostile input: nesting, numbers, strings, escapes and whitespace") {
      val results = hostile.flatMap: (name, input) =>
        Sides.objects(bytes(input)).map(
          (
            kind,
            c,
            m,
          ) => (name, kind, c, m)
        )
      val threw   = results.filter(r => r._3.startsWith("threw") || r._4.startsWith("threw"))
      assertTrue(
        results.size > 1000,
        results.count(_._3 != Refused) > 100,
        results.count(_._3 == Refused) > 500,
        threw.isEmpty,
        differences(results).isEmpty,
      )
    },
    test("every prefix of a message is refused on both sides, but the whole") {
      val results = prefixes.flatMap: (name, input) =>
        Sides.objects(bytes(input)).take(1).map(
          (
            kind,
            c,
            m,
          ) => (name, kind, c, m)
        )
      assertTrue(
        results.size == prefixes.size,
        results.init.forall(r => r._3 == Refused),
        results.last._3 != Refused,
        differences(results).isEmpty,
      )
    },
    test("content after the value: the module reads the value, the core refuses the input") {
      val cases = after ++ corpusAfter.toList.map((name, va) => (s"corpus $name", va._1, va._2))
      val wrong = cases.flatMap:
        (
          name,
          value,
          rest,
        ) =>
          val whole = bytes(value + rest)
          val read  =
            if value.startsWith("\"") then
              Sides.values(bytes(value)).map(
                (
                  k,
                  c,
                  _,
                ) => k -> c
              )
            else
              Sides.objects(bytes(value)).map(
                (
                  k,
                  c,
                  _,
                ) => k -> c
              )
          val both  =
            if value.startsWith("\"") then Sides.values(whole) else Sides.objects(whole)
          both.zip(read).collect:
            case ((kind, core, module), (_, alone)) if core != Refused || module != alone =>
              s"$kind|$name: core $core, module $module, the value alone $alone"
      assertTrue(cases.size == 7, wrong.isEmpty)
    },
    test("malformed UTF-8: the core refuses it, the module reads zio-json's U+FFFD in its place") {
      val results = malformed.map: (name, input) =>
        (name, Sides.core[Text](input), Sides.module[Text](input))
      val wrong   = results.filterNot:
        (
          _,
          core,
          module,
        ) =>
          core == Refused && (module == Refused || module.contains("\ufffd"))
      assertTrue(results.size == 10, results.count(_._3.contains("\ufffd")) >= 8, wrong.isEmpty)
    },
  )

end DifferentialSpec
