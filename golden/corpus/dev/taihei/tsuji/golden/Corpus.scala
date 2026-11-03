package dev.taihei.tsuji.golden

import java.time.{LocalDate, LocalDateTime, LocalTime}

import scala.annotation.nowarn

import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*

// The corpus of the wire-format golden files (ADR 0006, section 2). It is compiled twice: by the
// generator in golden/generate against the released taihei-tsuji 5.0.2, which wrote the golden
// files, and by the tests against this version, which must write the same bytes and read the
// same values. So it uses only what both versions have: families, values and strings, no codec.

// A family whose text renders the same on every JDK and Node version: it shows only strings,
// nested messages and texts, never a number, a date or a language name.
object GoldenText extends MessageFamily("golden.text"):

  val plain = message("Plain", Dutch -> "Gewoon")

  val say = message[(s: String)]("Say {$s}", Dutch -> "Zeg {$s}")

  val wrapped = message[(inner: Message)]("Wrapped: {$inner}")

  val shown = message[(d: Displayable)]("Shown: {$d}")

  val quoted = message[(t: Text)]("Quoted: {$t}")

  val words = message[(xs: List[String])]("Words: {$xs}")

  val maybe = message[(o: Option[String])]("Maybe [{$o}]")

  // A template that the files of taihei-tsuji-tools must escape: a quote, a backslash, non-ASCII,
  // U+007F and U+2028.
  val escaped = message(
    "Say \"hi\" \\ caf\u00e9 \u007f\u2028 \ud83d\ude00",
    Dutch -> "Zeg \"hoi\" \u00e9\u00e9n",
  )

end GoldenText

final case class GoldenId(value: String)

object GoldenId:

  given MessageArg[GoldenId] =
    MessageArg.as[GoldenId, String](
      _.value,
      s => if s.isEmpty then Left("an empty id") else Right(GoldenId(s)),
    )

// Every argument type. Its messages are written without their text, whose numbers, dates and
// language names follow the platform's formatting.
object GoldenArgs extends MessageFamily("golden.args"):

  val scalars =
    message[(s: String, i: Int, l: Long, d: Double, f: Float, b: Boolean)](
      "{$s} {$i} {$l} {$d} {$f} {$b}"
    )

  val times = message[(date: LocalDate, dateTime: LocalDateTime, time: LocalTime)](
    "{$date} {$dateTime} {$time}"
  )

  val language = message[(in: Language)]("{$in}")

  val options =
    message[(i: Option[Int], d: Option[Double], s: Option[String], date: Option[LocalDate])](
      "{$i} {$d} {$s} {$date}"
    )

  val lists = message[(
    ints: List[Int],
    doubles: List[Double],
    dates: List[LocalDate],
    opts: List[Option[Int]],
    nested: List[List[String]],
  )]("{$ints} {$doubles} {$dates} {$opts} {$nested}")

  val id = message[(id: GoldenId)]("{$id}")

  val messages = message[(ms: List[Message], d: Option[Displayable])]("{$ms} {$d}")

end GoldenArgs

// What is written: a value and the codec it is written with.
enum Written:
  case Msg(m: Message, withText: Boolean)
  case Disp(d: Displayable)
  case Txt(t: Text)
  case Legacy(m: Corpus.LegacyMessage)
  case Lang(l: Language)
  case Key(k: String)
  case Cat(c: Catalogue)

@nowarn("cat=deprecation")
object Corpus:

  type LegacyMessage = legacy.Message

  val catalogues: Catalogues = Catalogues(Seq(GoldenText, GoldenArgs))

  // Strings for every rule of escaping, and the unpaired surrogates that 6.0.0 writes as U+FFFD.
  val strings: List[(String, String)] = List(
    "empty" -> "",
    "ascii" -> "Hello, world",
    "quote" -> "a \"b\" c",
    "backslash" -> "a\\b\\",
    "slash" -> "a/b</c>",
    "controls" -> (0 until 32).map(_.toChar).mkString,
    "del" -> "a\u007fb",
    "c1" -> "\u0080\u0085\u009f",
    "latin" -> "caf\u00e9 \u00f1",
    "cjk" -> "\u65e5\u672c\u8a9e",
    "emoji" -> "\ud83d\ude00!",
    "separators" -> "a\u2028b\u2029c",
    "bom" -> "\ufeffx",
    "replacement" -> "\ufffd",
    "nul" -> "\u0000",
    "max" -> "\uffff\uffee",
    "highAlone" -> "a\ud800b",
    "lowAlone" -> "a\udc00b",
    "reversed" -> "\udc00\ud800",
    "highAtEnd" -> "x\ud83d",
    "lowAtStart" -> "\ude00x",
  )

  // The names of the strings with an unpaired surrogate.
  val unpaired: Set[String] = Set("highAlone", "lowAlone", "reversed", "highAtEnd", "lowAtStart")

  val doubles: List[Double] = List(
    0.0,
    -0.0,
    1.0,
    -1.5,
    0.1 + 0.2,
    1e21,
    1e-7,
    123456789.125,
    Double.MaxValue,
    Double.MinPositiveValue,
    Double.NaN,
    Double.PositiveInfinity,
    Double.NegativeInfinity,
  )

  val floats: List[Float] = List(0.0f, 1.1f, -3.4e38f, 1e-45f, Float.NaN, Float.NegativeInfinity)

  private val day = LocalDate.of(2026, 10, 26)

  val languages: List[(String, Language)] = List(
    "English" -> English,
    "Dutch" -> Dutch,
    "French" -> French,
    "de" -> Language("de"),
    "nl-BE" -> Language("nl-BE"),
    "zh-Hant-TW" -> Language("zh-Hant-TW"),
    "en-US" -> Language("en-US"),
    "x-private" -> Language("en-x-private"),
  )

  val writes: List[(String, Written)] =
    val byString       = strings.flatMap: (name, s) =>
      List(
        s"say $name" -> Written.Msg(GoldenText.say(s), withText = true),
        s"text $name" -> Written.Txt(Text(s, English)),
        s"displayable text $name" -> Written.Disp(Text(s, Dutch)),
        s"legacy $name" -> Written.Legacy(legacy.Message(List(s), Map(s -> s), Map(English -> s))),
        s"words $name" -> Written.Msg(GoldenText.words(List(s, "x")), withText = true),
        s"catalogue $name" ->
        Written.Cat(Catalogue(English, Map(MessageKey.parse("golden.s").toOption.get -> s))),
      )
    val scalars        =
      for
        (d, i) <- doubles.zipWithIndex
        f = floats(i % floats.size)
      yield s"scalars $i" -> Written.Msg(
        GoldenArgs.scalars("s", i * 1000003, i.toLong * 4000000000L, d, f, i % 2 == 0),
        withText = false,
      )
    val extremes       = List(
      "scalars extremes" -> Written.Msg(
        GoldenArgs.scalars("", Int.MinValue, Long.MaxValue, 0.0, 0.0f, false),
        withText = false,
      ),
      "scalars other extremes" -> Written.Msg(
        GoldenArgs.scalars("", Int.MaxValue, Long.MinValue, -0.0, -0.0f, true),
        withText = false,
      ),
    )
    val others         = List(
      "plain" -> Written.Msg(GoldenText.plain, withText = true),
      "plain without text" -> Written.Msg(GoldenText.plain, withText = false),
      "plain displayable" -> Written.Disp(GoldenText.plain),
      "wrapped" -> Written.Msg(GoldenText.wrapped(GoldenText.say("in")), withText = true),
      "wrapped without text" -> Written.Msg(GoldenText.wrapped(GoldenText.plain), withText = false),
      "wrapped deep" -> Written.Msg(
        (1 to 7).foldLeft(GoldenText.plain)((m, _) => GoldenText.wrapped(m)),
        withText = true,
      ),
      "shown text" -> Written.Msg(GoldenText.shown(Text("t", French)), withText = true),
      "shown message" -> Written.Msg(GoldenText.shown(GoldenText.plain), withText = true),
      "shown legacy" -> Written.Msg(GoldenText.shown(legacy.Message.of("old")), withText = true),
      "quoted" -> Written.Msg(GoldenText.quoted(Text("q", Language("nl-BE"))), withText = true),
      "words none" -> Written.Msg(GoldenText.words(Nil), withText = true),
      "maybe none" -> Written.Msg(GoldenText.maybe(None), withText = true),
      "maybe some" -> Written.Msg(GoldenText.maybe(Some("x")), withText = true),
      "times" -> Written.Msg(
        GoldenArgs.times(day, LocalDateTime.of(2026, 10, 26, 13, 5, 7, 123000000), LocalTime.of(0, 0)),
        withText = false,
      ),
      "times seconds" -> Written.Msg(
        GoldenArgs.times(
          LocalDate.of(-1, 1, 1),
          LocalDateTime.of(10000, 12, 31, 23, 59, 59),
          LocalTime.of(23, 59, 59, 1),
        ),
        withText = false,
      ),
      "options none" -> Written.Msg(GoldenArgs.options(None, None, None, None), withText = false),
      "options some" -> Written.Msg(
        GoldenArgs.options(Some(1), Some(Double.NaN), Some(""), Some(day)),
        withText = false,
      ),
      "lists" -> Written.Msg(
        GoldenArgs.lists(
          List(1, -2, 3),
          List(1.0, 2.5, Double.PositiveInfinity),
          List(day),
          List(None, Some(4)),
          List(Nil, List("a", "b")),
        ),
        withText = false,
      ),
      "lists empty" -> Written.Msg(GoldenArgs.lists(Nil, Nil, Nil, Nil, Nil), withText = false),
      "id" -> Written.Msg(GoldenArgs.id(GoldenId("abc-1")), withText = false),
      "messages" -> Written.Msg(
        GoldenArgs.messages(List(GoldenText.plain, GoldenText.say("x")), Some(Text("d", English))),
        withText = false,
      ),
      "messages legacy" ->
      Written.Msg(GoldenArgs.messages(Nil, Some(legacy.Message.of("l"))), withText = false),
      "legacy nothing" -> Written.Legacy(legacy.Message(Nil, Map.empty, Map.empty)),
      "legacy of" -> Written.Legacy(legacy.Message.of("Disk full")),
      "legacy args" -> Written.Legacy(
        legacy.Message(
          List(
            "a",
            1,
            2L,
            1.5,
            2.5f,
            true,
            day,
            LocalDateTime.of(2026, 1, 2, 3, 4),
            LocalTime.of(5, 6),
          ),
          Map("n" -> 1.0, "m" -> "v", "k" -> Double.NaN),
          Map(
            English -> "{$0} {$n}",
            Dutch -> "nl",
            Language("nl-BE") -> "be",
            Language("de") -> "de",
          ),
        )
      ),
      "legacy many" -> Written.Legacy(
        legacy.Message(
          Nil,
          (1 to 20).map(i => s"k$i" -> s"v$i").toMap,
          List("en", "nl", "fr", "de", "es", "it", "pt", "nl-BE", "fr-CA").map(t =>
            Language(t) -> t
          ).toMap,
        )
      ),
      "displayable legacy" -> Written.Disp(legacy.Message.of("x")),
      "catalogue no messages" -> Written.Cat(Catalogue(Dutch, Map.empty)),
      "catalogue sorted" -> Written.Cat(
        Catalogue(
          Language("nl-BE"),
          List("z.a", "a.z", "a.b", "golden.text.plain", "B.a")
            .map(k => MessageKey.parse(k).toOption.get -> s"[$k]")
            .toMap,
        )
      ),
    )
    val languageWrites = languages.map((n, l) => s"language $n" -> Written.Lang(l))
    val keyWrites      =
      List("order.shipped", "a.b_c.D9", "golden.text.plain").map(k => s"key $k" -> Written.Key(k))
    byString ++ scalars ++ extremes ++ others ++ languageWrites ++ keyWrites

  end writes

  // A message nested `n` levels in GoldenText.wrapped, as JSON.
  def wrappedJson(n: Int): String = (1 to n).foldLeft("""{"key":"golden.text.plain"}""")(
    (inner, _) =>
      s"""{"key":"golden.text.wrapped","args":{"inner":$inner}}"""
  )

  private def say(s: String)  = s"""{"key":"golden.text.say","args":{"s":$s}}"""
  private def kept(a: String) = s"""{"key":"no.such","args":{"a":$a},"text":"T"}"""

  private def int(i: String) =
    s"""{"key":"golden.args.scalars","args":{"s":"","i":$i,"l":0,"d":0,"f":0,"b":true}}"""

  private def double(d: String) =
    s"""{"key":"golden.args.scalars","args":{"s":"","i":0,"l":0,"d":$d,"f":0,"b":true}}"""

  private def text(s: String) = s"""{"text":$s,"language":"en"}"""
  private def nest(n: Int)    = "[" * n + "1" + "]" * n

  // Numbers as JSON writes them and as it does not: each read as a kept argument of an unknown
  // key, which keeps its value as it came, and so shows how the reader read it.
  val numbers: List[String] = List(
    "0",
    "-0",
    "1",
    "-1",
    "1.0",
    "1.50",
    "-0.0",
    "0.000",
    "1e2",
    "1E2",
    "1e+2",
    "1E-2",
    "1e-0",
    "1.5e3",
    "-1.5E+3",
    "1e400",
    "1e-400",
    "0.1",
    "0.0000001",
    "12345678901234567890.25",
    "9" * 18,
    "9" * 19,
    "9" * 76,
    "9" * 77,
    "1" + "0" * 30,
    "1." + "0" * 30,
    "0." + "0" * 30 + "1",
    "123456789012345678901234567890e-10",
    "1e999999999",
    "-1e-999999999",
    "1e-999999999",
    "1e0000000000005",
    "1E+05",
    "0e0",
    "0." + "0" * 1097 + "1",
    "-9223372036854775808",
    "-922337203685477580.8",
    "-92233720368547758.08",
    "-9223372036854775808e2",
    "9223372036854775807",
    "-9223372036854775807",
    "01",
    "-01",
    "00",
    "1.",
    "-.5",
    ".5",
    "1.e5",
    "+1",
    "1e",
    "1e+",
    "-",
    "--1",
    "1.2.3",
    "1-2",
    "1E5E5",
    "0x10",
    "NaN",
    "Infinity",
    "-Infinity",
    // Either side of the reader's fast path for short integers: 18 digits are read there, 19
    // through jsoniter-scala.
    "-" + "9" * 18,
    "1" + "0" * 18,
  )

  // Inputs read as a Message, a Displayable, a Text and a legacy.Message. None reaches a bound
  // that 5.0.2 added (depth 64, numbers of 1100 characters or with an exponent of more than nine
  // digits); the bounds have their own tests.
  val reads: List[(String, String)] =
    val messages    = List(
      "plain" -> """{"key":"golden.text.plain"}""",
      "plain with text" -> """{"key":"golden.text.plain","text":"Plain"}""",
      "plain other text" -> """{"key":"golden.text.plain","text":"Something else"}""",
      "plain args empty" -> """{"key":"golden.text.plain","args":{}}""",
      "plain args null" -> """{"key":"golden.text.plain","args":null}""",
      "plain args array" -> """{"key":"golden.text.plain","args":[]}""",
      "plain args string" -> """{"key":"golden.text.plain","args":"x"}""",
      "plain text number" -> """{"key":"golden.text.plain","text":1}""",
      "plain text null" -> """{"key":"golden.text.plain","text":null}""",
      "plain key null" -> """{"key":null}""",
      "plain key number" -> """{"key":1}""",
      "plain key invalid" -> """{"key":"no key","text":"T"}""",
      "plain key long" -> s"""{"key":"a.${"b" * 300}","text":"T"}""",
      "plain field order" -> """{"text":"t","args":{},"key":"golden.text.plain"}""",
      "plain whitespace" -> " \n\t{ \"key\" : \"golden.text.plain\" ,\r\n \"text\":\"x\" } \n",
      "plain unknown fields" ->
      """{"key":"golden.text.plain","x":{"a":[1,2.5,{"b":null}],"c":true},"y":[]}""",
      "plain duplicate key" ->
      """{"key":"golden.text.plain","key":"golden.text.say","args":{"s":"x"}}""",
      "plain duplicate args" -> """{"key":"golden.text.say","args":{},"args":{"s":"x"}}""",
      "say" -> say("\"x\""),
      "say missing" -> """{"key":"golden.text.say","args":{}}""",
      "say null" -> say("null"),
      "say number" -> say("1"),
      "say object" -> say("{}"),
      "say extra" -> """{"key":"golden.text.say","args":{"s":"x","t":"y"}}""",
      "say duplicate" -> """{"key":"golden.text.say","args":{"s":"a","s":"b"}}""",
      "say escapes" -> say("\"\\u00e9\\n\\t\\\"\\\\\\/\\b\\f\\r\\u0000\\u001f\""),
      "say pair escaped" -> say("\"\\ud83d\\ude00\""),
      "say pair upper" -> say("\"\\uD83D\\uDE00\""),
      "say high escaped" -> say("\"a\\ud800b\""),
      "say low escaped" -> say("\"a\\udc00b\""),
      "say high at end escaped" -> say("\"\\ud800\""),
      "say reversed escaped" -> say("\"\\udc00\\ud800\""),
      "say bad escape" -> say("\"\\x\""),
      "say short escape" -> say("\"\\u12\""),
      "say raw control" -> say("\"a\u0001b\""),
      "say raw newline" -> say("\"a\nb\""),
      "say raw tab" -> say("\"a\tb\""),
      "say raw del" -> say("\"a\u007fb\""),
      "say raw separators" -> say("\"a\u2028b\u2029\""),
      "say raw high surrogate" -> say("\"a\ud800b\""),
      "say raw low surrogate" -> say("\"a\udc00b\""),
      "say raw pair" -> say("\"\ud83d\ude00\""),
      "say raw nul" -> say("\"\u0000\""),
      "say unterminated" -> """{"key":"golden.text.say","args":{"s":"x}}""",
      "maybe missing" -> """{"key":"golden.text.maybe","args":{}}""",
      "maybe no args" -> """{"key":"golden.text.maybe"}""",
      "maybe null" -> """{"key":"golden.text.maybe","args":{"o":null}}""",
      "maybe some" -> """{"key":"golden.text.maybe","args":{"o":"v"}}""",
      "words" -> """{"key":"golden.text.words","args":{"xs":["a","b"]}}""",
      "words empty" -> """{"key":"golden.text.words","args":{"xs":[]}}""",
      "words null element" -> """{"key":"golden.text.words","args":{"xs":["a",null]}}""",
      "words not array" -> """{"key":"golden.text.words","args":{"xs":"a"}}""",
      "words 10000" ->
      s"""{"key":"golden.text.words","args":{"xs":${List.fill(10000)("\"w\"").mkString(
          "[",
          ",",
          "]",
        )}}}""",
      "words 10001" ->
      s"""{"key":"golden.text.words","args":{"xs":${List.fill(10001)("\"w\"").mkString(
          "[",
          ",",
          "]",
        )}}}""",
      "wrapped" -> wrappedJson(1),
      "wrapped 8" -> wrappedJson(8),
      "wrapped 9" -> wrappedJson(9),
      "wrapped unknown inner" ->
      """{"key":"golden.text.wrapped","args":{"inner":{"key":"no.such","text":"Kept"}}}""",
      "wrapped text inner" ->
      """{"key":"golden.text.wrapped","args":{"inner":{"text":"t","language":"en"}}}""",
      "wrapped legacy inner" ->
      """{"key":"golden.text.wrapped","args":{"inner":{"args":[],"translationTemplates":{"English":"L"}}}}""",
      "shown text" -> """{"key":"golden.text.shown","args":{"d":{"text":"t","language":"fr"}}}""",
      "shown message" -> """{"key":"golden.text.shown","args":{"d":{"key":"golden.text.plain"}}}""",
      "shown legacy" ->
      """{"key":"golden.text.shown","args":{"d":{"args":[],"translationTemplates":{"en":"L"}}}}""",
      "shown string" -> """{"key":"golden.text.shown","args":{"d":"t"}}""",
      "quoted" -> """{"key":"golden.text.quoted","args":{"t":{"text":"q","language":"nl-BE"}}}""",
      "quoted message" ->
      """{"key":"golden.text.quoted","args":{"t":{"key":"golden.text.plain"}}}""",
      "scalars" ->
      """{"key":"golden.args.scalars","args":{"s":"s","i":1,"l":2,"d":1.5,"f":2.5,"b":false}}""",
      "int 1.0" -> int("1.0"),
      "int 1e2" -> int("1e2"),
      "int 1.5" -> int("1.5"),
      "int string" -> int("\"1\""),
      "int max" -> int("2147483647"),
      "int beyond" -> int("2147483648"),
      "int min" -> int("-2147483648"),
      "int beyond min" -> int("-2147483649"),
      "int leading zero" -> int("01"),
      "int null" -> int("null"),
      "long beyond" ->
      """{"key":"golden.args.scalars","args":{"s":"","i":0,"l":9223372036854775808,"d":0,"f":0,"b":true}}""",
      "double NaN" -> double("\"NaN\""),
      "double Infinity" -> double("\"Infinity\""),
      "double -Infinity" -> double("\"-Infinity\""),
      "double other string" -> double("\"1.5\""),
      "double 1e400" -> double("1e400"),
      "double 1e-400" -> double("1e-400"),
      "double precise" -> double("0.30000000000000004"),
      "double long" -> double("3.14159265358979323846264338327950288"),
      "float string" ->
      """{"key":"golden.args.scalars","args":{"s":"","i":0,"l":0,"d":0,"f":"NaN","b":true}}""",
      "boolean string" ->
      """{"key":"golden.args.scalars","args":{"s":"","i":0,"l":0,"d":0,"f":0,"b":"true"}}""",
      "times" ->
      """{"key":"golden.args.times","args":{"date":"2026-10-26","dateTime":"2026-10-26T13:05:07.123","time":"00:00"}}""",
      "times bad date" ->
      """{"key":"golden.args.times","args":{"date":"2026-13-01","dateTime":"2026-10-26T13:05","time":"00:00"}}""",
      "times long" ->
      s"""{"key":"golden.args.times","args":{"date":"${"2" *
        65}","dateTime":"2026-10-26T13:05","time":"00:00"}}""",
      "times number" ->
      """{"key":"golden.args.times","args":{"date":20261026,"dateTime":"2026-10-26T13:05","time":"00:00"}}""",
      "language tag" -> """{"key":"golden.args.language","args":{"in":"nl-BE"}}""",
      "language name" -> """{"key":"golden.args.language","args":{"in":"French"}}""",
      "language case" -> """{"key":"golden.args.language","args":{"in":"NL-be"}}""",
      "language bad" -> """{"key":"golden.args.language","args":{"in":"not a tag"}}""",
      "language number" -> """{"key":"golden.args.language","args":{"in":1}}""",
      "options all null" ->
      """{"key":"golden.args.options","args":{"i":null,"d":null,"s":null,"date":null}}""",
      "options all missing" -> """{"key":"golden.args.options"}""",
      "options set" ->
      """{"key":"golden.args.options","args":{"i":1,"d":"NaN","s":"","date":"2026-10-26"}}""",
      "lists" ->
      """{"key":"golden.args.lists","args":{"ints":[1,2],"doubles":[1.5,"Infinity"],"dates":[],"opts":[null,3],"nested":[[],["a"]]}}""",
      "lists missing" -> """{"key":"golden.args.lists","args":{"ints":[1]}}""",
      "id" -> """{"key":"golden.args.id","args":{"id":"abc"}}""",
      "id empty" -> """{"key":"golden.args.id","args":{"id":""}}""",
      "messages" ->
      """{"key":"golden.args.messages","args":{"ms":[{"key":"golden.text.plain"},{"key":"no.such","text":"K"}],"d":{"text":"x","language":"Dutch"}}}""",
      "unknown" -> """{"key":"no.such","text":"T"}""",
      "unknown no text" -> """{"key":"no.such"}""",
      "unknown no text with args" -> """{"key":"no.such","args":{"a":1}}""",
      "unknown args" ->
      """{"key":"no.such","args":{"a":1,"b":[1,2.50,{"c":"d"}],"e":null,"f":true,"g":{}},"text":"T"}""",
      "unknown args empty" -> """{"key":"no.such","args":{},"text":"T"}""",
      "unknown args order" -> """{"text":"T","args":{"z":1,"a":2},"key":"no.such"}""",
      "unknown args duplicate" -> """{"key":"no.such","args":{"a":1,"a":2},"text":"T"}""",
      "unknown args 15" -> kept(nest(14)),
      "unknown args 16" -> kept(nest(15)),
      "unknown args 17" -> kept(nest(16)),
      "unknown args strings" -> kept("\"\\u00e9\\u2028\\ud83d\\ude00\\u007f\""),
      "legacy" ->
      """{"args":["a"],"namedArgs":{"n":"v"},"translationTemplates":{"English":"Hi {$n} {$0}","nl":"Hoi"}}""",
      "legacy no named" -> """{"args":[],"translationTemplates":{"en":"x"}}""",
      "legacy no args" -> """{"namedArgs":{},"translationTemplates":{"en":"x"}}""",
      "legacy only templates" -> """{"translationTemplates":{"en":"x"}}""",
      "legacy empty templates" -> """{"args":[],"translationTemplates":{}}""",
      "legacy number arg" -> """{"args":[1],"translationTemplates":{"en":"x"}}""",
      "legacy null arg" -> """{"args":[null],"translationTemplates":{"en":"x"}}""",
      "legacy number named" ->
      """{"args":[],"namedArgs":{"n":1},"translationTemplates":{"en":"x"}}""",
      "legacy bad language" -> """{"args":[],"translationTemplates":{"not a tag":"x"}}""",
      "legacy language names" ->
      """{"args":[],"translationTemplates":{"Dutch":"nl","French":"fr","nl-BE":"be","DE":"de"}}""",
      "legacy duplicate template" ->
      """{"args":[],"translationTemplates":{"en":"a","English":"b","en":"c"}}""",
      "legacy duplicate named" ->
      """{"args":[],"namedArgs":{"n":"a","n":"b"},"translationTemplates":{"en":"x"}}""",
      "legacy duplicate field" ->
      """{"args":["a"],"args":["b"],"translationTemplates":{"en":"x"}}""",
      "legacy template number" -> """{"args":[],"translationTemplates":{"en":1}}""",
      "legacy templates null" -> """{"args":[],"translationTemplates":null}""",
      "legacy templates array" -> """{"args":[],"translationTemplates":[]}""",
      "legacy args null" -> """{"args":null,"translationTemplates":{"en":"x"}}""",
      "legacy named null" -> """{"args":[],"namedArgs":null,"translationTemplates":{"en":"x"}}""",
      "legacy unknown field" ->
      """{"args":[],"translationTemplates":{"en":"x"},"z":[1,{"a":null}]}""",
      "legacy with key" ->
      """{"key":"golden.text.plain","args":[],"translationTemplates":{"en":"x"}}""",
      "legacy 1000 templates" ->
      (1 to 1000).map(i => s""""x-t$i":"t"""").mkString(
        """{"args":[],"translationTemplates":{""",
        ",",
        "}}",
      ),
      "legacy 1001 templates" ->
      (1 to 1001).map(i => s""""x-t$i":"t"""").mkString(
        """{"args":[],"translationTemplates":{""",
        ",",
        "}}",
      ),
      "legacy 1001 args" -> List.fill(1001)("\"a\"").mkString(
        """{"args":[""",
        ",",
        """],"translationTemplates":{"en":"x"}}""",
      ),
      "legacy long template" -> s"""{"args":[],"translationTemplates":{"en":"${"t" * 65537}"}}""",
      "text" -> text("\"x\""),
      "text language name" -> """{"text":"x","language":"English"}""",
      "text language tag" -> """{"text":"x","language":"nl-BE"}""",
      "text language lower" -> """{"text":"x","language":"english"}""",
      "text language bad" -> """{"text":"x","language":"not a tag"}""",
      "text language number" -> """{"text":"x","language":1}""",
      "text no language" -> """{"text":"x"}""",
      "text no text" -> """{"language":"en"}""",
      "text number" -> text("1"),
      "text null" -> text("null"),
      "text extra" -> """{"text":"x","language":"en","z":{"a":[1e5,"b"]}}""",
      "text duplicate" -> """{"text":"a","text":"b","language":"en"}""",
      "text escapes" -> text("\"\\u00e9\\u2028\\ud83d\\ude00\\u007f\\u0000\""),
      "text high escaped" -> text("\"\\ud800\""),
      "text raw control" -> text("\"\u001f\""),
      "text raw high surrogate" -> text("\"\ud800\""),
      "null" -> "null",
      "array" -> "[]",
      "number" -> "1",
      "string" -> "\"s\"",
      "boolean" -> "true",
      "empty" -> "",
      "blank" -> "  ",
      "open" -> "{",
      "object" -> "{}",
      "trailing" -> """{"key":"golden.text.plain"}x""",
      "two values" -> """{"key":"golden.text.plain"} {}""",
      "truncated" -> """{"key":"golden.text.say","args":{"s":"x"""",
      "trailing comma" -> """{"key":"golden.text.plain",}""",
      "missing colon" -> """{"key" "golden.text.plain"}""",
      "single quotes" -> """{'key':'golden.text.plain'}""",
      "comment" -> """{"key":"golden.text.plain"/* c */}""",
      "unquoted name" -> """{key:"golden.text.plain"}""",
      "bad literal" -> """{"key":"golden.text.plain","x":tru}""",
      "nul literal" -> """{"key":"golden.text.plain","x":nul}""",
      "nested 30" -> s"""{"key":"golden.text.plain","x":${nest(30)}}""",
      "nested 63" -> s"""{"key":"golden.text.plain","x":${nest(62)}}""",
      "bom" -> "\ufeff{\"key\":\"golden.text.plain\"}",
      "long min" ->
      """{"key":"golden.args.scalars","args":{"s":"","i":0,"l":-9223372036854775808,"d":0,"f":0,"b":true}}""",
    )
    val keptNumbers = numbers.zipWithIndex.map((n, i) => s"number $i ($n)" -> kept(s"[$n]"))
    messages ++ keptNumbers

  end reads

  // Inputs read as a Language and as a MessageKey, each a JSON value; then as the key of an object.
  val values: List[(String, String)] = List(
    "en" -> "\"en\"",
    "English" -> "\"English\"",
    "english" -> "\"english\"",
    "nl-be" -> "\"nl-be\"",
    "zh-hant-tw" -> "\"zh-hant-tw\"",
    "x" -> "\"x\"",
    "key" -> "\"order.shipped\"",
    "key one part" -> "\"order\"",
    "key empty part" -> "\"order..x\"",
    "key long" -> s"\"a.${"b" * 300}\"",
    "empty" -> "\"\"",
    "number" -> "1",
    "null" -> "null",
    "escaped" -> "\"\\u0065n\"",
    "object" -> "{}",
  )

  val keys: List[(String, String)] = List(
    "en" -> """{"en":1}""",
    "English" -> """{"English":1}""",
    "two" -> """{"nl-BE":1,"order.shipped":2}""",
    "key" -> """{"order.shipped":1}""",
    "bad" -> """{"not a tag or key":1}""",
    "empty" -> """{}""",
  )

  // Catalogue files, read with Catalogue.parse.
  val catalogueInputs: List[(String, String)] =
    def nested(n: Int) = s"""{"format":1,"language":"de","x":${"[" * n}${"]" * n},"messages":{}}"""
    List(
      "valid" -> """{"format":1,"language":"de","messages":{"b.x":"B","a.x":"A {$n}"}}""",
      "format 1.0" -> """{"format":1.0,"language":"de","messages":{}}""",
      "format 1e0" -> """{"format":1e0,"language":"de","messages":{}}""",
      "format string" -> """{"format":"1","language":"de","messages":{}}""",
      "format 2" -> """{"format":2,"language":"de","messages":{}}""",
      "no format" -> """{"language":"de","messages":{}}""",
      "no language" -> """{"format":1,"messages":{}}""",
      "language name" -> """{"format":1,"language":"English","messages":{}}""",
      "language bad" -> """{"format":1,"language":"not a tag","messages":{}}""",
      "language number" -> """{"format":1,"language":1,"messages":{}}""",
      "no messages" -> """{"format":1,"language":"de"}""",
      "messages array" -> """{"format":1,"language":"de","messages":[]}""",
      "bad key" -> """{"format":1,"language":"de","messages":{"nokey":"x","a.b":"y"}}""",
      "duplicate key" -> """{"format":1,"language":"de","messages":{"a.b":"x","a.b":"y"}}""",
      "template number" -> """{"format":1,"language":"de","messages":{"a.b":1,"a.c":"y"}}""",
      "template null" -> """{"format":1,"language":"de","messages":{"a.b":null}}""",
      "template long" ->
      s"""{"format":1,"language":"de","messages":{"a.b":"${"t" * 65537}","a.c":"y"}}""",
      "unknown fields" ->
      """{"format":1,"language":"de","note":{"a":[1,2]},"messages":{"a.b":"x"}}""",
      "nested 15" -> nested(14),
      "nested 16" -> nested(15),
      "nested 17" -> nested(16),
      "escapes" ->
      """{"format":1,"language":"de","messages":{"a.b":"\u00e9\n\ud83d\ude00\u2028"}}""",
      "high escaped" -> """{"format":1,"language":"de","messages":{"a.b":"\ud800"}}""",
      "raw control" -> "{\"format\":1,\"language\":\"de\",\"messages\":{\"a.b\":\"\u0001\"}}",
      "whitespace" ->
      " {\n  \"format\" : 1,\n  \"language\" : \"de\",\n  \"messages\" : {\n    \"a.b\" : \"x\"\n  }\n}\n",
      "not object" -> "[]",
      "empty" -> "",
      "truncated" -> """{"format":1,"language":"de","messages":{"a.b":"x"""",
      "trailing" -> """{"format":1,"language":"de","messages":{}}x""",
      "number 01" -> """{"format":1,"language":"de","n":01,"messages":{}}""",
      "bom" -> "\ufeff{\"format\":1,\"language\":\"de\",\"messages\":{}}",
    )

  end catalogueInputs

end Corpus
