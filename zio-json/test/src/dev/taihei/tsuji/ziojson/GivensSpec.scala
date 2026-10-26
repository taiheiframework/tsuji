package dev.taihei.tsuji.ziojson

import scala.annotation.nowarn

import zio.json.*
import zio.json.ast.Json
import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.golden.{Corpus, GoldenText}

// A codec object of a caller's own, which makes the module's givens its own.
object CallerCodecs:
  export TsujiZioJson.given

// How a caller reaches the givens, and how they behave in zio-json's own machinery.
@nowarn("cat=deprecation")
object GivensSpec extends ZIOSpecDefault:

  import CallerCodecs.given

  given Catalogues = Corpus.catalogues

  private val message = GoldenText.say("x")
  private val text    = Text("a", Language.English)

  def spec = suite("The givens of TsujiZioJson")(
    test("each one is found, through an export too, and they agree") {
      val codec = summon[JsonCodec[Message]]
      assertTrue(
        summon[JsonEncoder[Language]].encodeJson(Language.Dutch).toString == "\"Dutch\"",
        summon[JsonCodec[Language]].decodeJson("\"nl\"") == Right(Language.Dutch),
        summon[JsonDecoder[Language]].decodeJson("\"Dutch\"") == Right(Language.Dutch),
        summon[JsonCodec[Text]].encodeJson(text, None).toString == text.toJson,
        summon[JsonDecoder[Text]].decodeJson(text.toJson) == Right(text),
        codec.encodeJson(message, None).toString == message.toJson,
        codec.decodeJson(message.toJson) == Right(message),
        summon[JsonDecoder[Displayable]].decodeJson(message.toJson) == Right(message),
        (message: Displayable).toJson == message.toJson,
        summon[JsonDecoder[MessageKey]].decodeJson("\"golden.text.say\"") == MessageKey.parse("golden.text.say"),
        summon[JsonEncoder[MessageKey]].encodeJson(MessageKey.parse("a.b").toOption.get).toString == "\"a.b\"",
      )
    },
    test("messageEncoder writes with or without the text, as the core does") {
      val withText    = TsujiZioJson.messageEncoder().encodeJson(message).toString
      val withoutText = TsujiZioJson.messageEncoder(withText = false).encodeJson(message).toString
      assertTrue(
        withText == message.toJson,
        withText.contains("\"text\""),
        !withoutText.contains("\"text\""),
        withoutText.fromJson[Message] == Right(message),
      )
    },
    test("a 4.x message is written and read, also as a Displayable") {
      val json = """{"args":["a"],"translationTemplates":{"en":"Hello {0}"}}"""
      val read = json.fromJson[legacy.Message]
      assertTrue(
        read.isRight,
        read.map(_.toJson.fromJson[legacy.Message]) == Right(read),
        json.fromJson[Displayable].isRight,
      )
    },
    test("languages and keys as the names of an object's fields") {
      val texts = Map(Language.English -> text, Language("nl-BE") -> text)
      val keyed = Map(MessageKey.parse("a.b").toOption.get -> 1)
      assertTrue(
        texts.toJson.fromJson[Map[Language, Text]] == Right(texts),
        keyed.toJson.fromJson[Map[MessageKey, Int]] == Right(keyed),
        """{"not a key":1}""".fromJson[Map[MessageKey, Int]].isLeft,
        """{"\ud800":1}""".fromJson[Map[Language, Int]].left.exists(_.contains("not Unicode")),
      )
    },
    test("an alternative with orElse rewinds and reads the other") {
      val either = summon[JsonDecoder[Text]].orElseEither(summon[JsonDecoder[Message]])
      assertTrue(
        either.decodeJson(message.toJson) == Right(Right(message)),
        either.decodeJson(text.toJson) == Right(Left(text)),
      )
    },
    test("a value inside a caller's array, with the reader positioned after it") {
      val list = s"[ ${text.toJson} , ${text.toJson} ]"
      assertTrue(
        list.fromJson[List[Text]] == Right(List(text, text)),
        s"[${text.toJson}".fromJson[List[Text]].isLeft,
      )
    },
    test("toJsonAST gives the core's JSON as zio-json's AST, and .as reads it back") {
      val ast = message.toJsonAST
      assertTrue(ast.map(_.toJson) == Right(message.toJson), ast.flatMap(_.as[Message]) == Right(message))
    },
    test("an error says what is wrong, not the input") {
      val secret = "s" * 5000
      val errors = List(
        s"""{"text":"$secret","language":"$secret"}""".fromJson[Text],
        s"""{"key":"$secret"}""".fromJson[Message],
        s"""{"text":"$secret"""".fromJson[Text],
        s""""$secret"""".fromJson[Language],
      )
      assertTrue(errors.forall(_.isLeft), errors.forall(_.left.forall(_.length < 400)))
    },
    test("a raw unpaired surrogate in a string or a name is refused by the capture, a raw pair is read") {
      // Input given as a String, where zio-json hands the capture the characters as they are.
      val lone = List(
        "{\"text\":\"a\ud800b\",\"language\":\"en\"}",
        "{\"text\":\"a\udc00b\",\"language\":\"en\"}",
        "{\"text\":\"a\ud800\",\"language\":\"en\"}",
        "{\"text\":\"\ud800\\udc00\",\"language\":\"en\"}",
        "{\"text\":\"a\",\"language\":\"en\",\"x\":{\"\ud800\":1}}",
        "{\"text\":\"a\",\"language\":\"en\",\"x\":[{\"b\udc00\":1}]}",
      )
      val pair = "{\"text\":\"\ud83d\ude00\",\"language\":\"en\",\"x\":{\"\ud83d\ude00\":1}}"
      assertTrue(
        lone.forall(_.fromJson[Text].left.exists(_.contains(Capture.NotUnicode))),
        lone.map(Capture.isUnicode).count(identity) == 0,
        pair.fromJson[Text] == Right(Text("\ud83d\ude00", Language.English)),
      )
    },
    test("4 MB of numbers at the length bound are read in time that grows with them") {
      val near   = "0." + "0" * (JsonTreeReader.MaxNumberLength - 3) + "1"
      val many   = List.fill(4 * 1024 * 1024 / (near.length + 1))(near).mkString("[", ",", "]")
      val input  = s"""{"text":"a","language":"en","x":$many}"""
      val start  = java.lang.System.nanoTime()
      val result = input.fromJson[Text]
      val millis = (java.lang.System.nanoTime() - start) / 1000000
      // A budget with room for a slow machine and for Node: a reader that is not linear takes far
      // longer.
      assertTrue(input.length > 4000000, result.isRight, millis < 20000)
    },
    test("4 MB of numbers in a text are read in time that grows with them") {
      val many   = List.fill(2 * 1024 * 1024)("0").mkString("[", ",", "]")
      val input  = s"""{"text":"a","language":"en","x":$many}"""
      val start  = java.lang.System.nanoTime()
      val result = input.fromJson[Text]
      val millis = (java.lang.System.nanoTime() - start) / 1000000
      assertTrue(input.length > 4000000, result.isRight, millis < 20000)
    },
    test("a number longer than the bound at the top level is refused as it is read") {
      val long = "1" + "0" * 1000000
      assertTrue(long.fromJson[Text].left.exists(_.contains("longer than 1100")))
    },
  )

end GivensSpec
