package dev.taihei.tsuji.ziojson

import zio.json.*
import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.TestJson.given
import dev.taihei.tsuji.golden.{Corpus, GoldenText, Written}
import dev.taihei.tsuji.ziojson.TsujiZioJson.given

import com.github.plokhotnyuk.jsoniter_scala.core.*

// The module writes the core's bytes (ADR 0006, section 2): for every value of the wire format's
// corpus, alone and inside a zio-json value of the caller's own. The core's bytes are those of
// the golden files, but for the differences the ADR lists; GoldenSpec checks that.
object ByteEqualitySpec extends ZIOSpecDefault:

  given Catalogues = Corpus.catalogues

  // A caller's own type, its codec derived by zio-json.
  final case class Envelope(
    m: Message,
    d: Displayable,
    t: Text,
    ts: Map[Language, Text],
    k: MessageKey)

  given JsonEncoder[Envelope] = DeriveJsonEncoder.gen[Envelope]
  given JsonDecoder[Envelope] = DeriveJsonDecoder.gen[Envelope]

  // (name, what the module writes, what the core writes)
  private def both(name: String, w: Written): List[(String, String, String)] = w match
    case Written.Msg(m, withText) =>
      List((
        name,
        TsujiZioJson.messageEncoder(withText).encodeJson(m).toString,
        writeToString(m)(
          using Message.codec(withText)
        ),
      ))
    case Written.Disp(d)          => List((name, d.toJson, writeToString(d)))
    case Written.Txt(t)           => List((name, t.toJson, writeToString(t)))
    case Written.Legacy(m)        => List((name, m.toJson, writeToString(m)))
    case Written.Lang(l)          =>
      List((name, l.toJson, writeToString(l)), (s"$name as a name", Map(l -> 1).toJson, writeToString(Map(l -> 1))))
    case Written.Key(k)           =>
      val key = MessageKey.parse(k).toOption.get
      List((name, key.toJson, writeToString(key)), (s"$name as a name", Map(key -> 1).toJson, writeToString(Map(key -> 1))))
    case Written.Cat(_)           => Nil

  def spec = suite("The module writes the core's bytes")(
    test("every value of the corpus") {
      val written = Corpus.writes.flatMap(both)
      val wrong   = written.collect:
        case (name, module, core) if module != core => s"$name:\n  module $module\n  core   $core"
      assertTrue(written.size > 150, wrong.isEmpty)
    },
    test("the values inside a caller's own zio-json value, and back") {
      val message  = GoldenText.say("x \u00e9 \u2028 \u007f \ud83d\ude00")
      val text     = Text("a \"b\" \\ c", Language.Dutch)
      val key      = MessageKey.parse("golden.text.say").toOption.get
      val envelope = Envelope(message, text, text, Map(Language.English -> text, Language("nl-BE") -> text), key)
      val expected =
        s"""{"m":${writeToString[Message](message)},"d":${writeToString[Displayable](text)},"t":${writeToString(
            text
          )},"ts":{"English":${writeToString(text)},"nl-BE":${writeToString(text)}},"k":"golden.text.say"}"""
      assertTrue(envelope.toJson == expected, expected.fromJson[Envelope] == Right(envelope))
    },
    test("a caller's indentation does not reach inside Tsuji's values") {
      val text   = Text("a", Language.English)
      val pretty = Map(Language.English -> text).toJsonPretty
      assertTrue(pretty.contains(writeToString(text)), pretty.contains("\n"))
    },
    test("an unpaired surrogate is written as U+FFFD, and writing never throws") {
      val texts = List("\ud800", "a\udc00b", "\udc00\ud800", "x\ud83d").map(Text(_, Language.English))
      assertTrue(
        texts.map(_.toJson) == texts.map(writeToString(_)),
        texts.forall(_.toJson.contains("\ufffd")),
        texts.forall(t => t.toJson.fromJson[Text].isRight),
      )
    },
  )

end ByteEqualitySpec
