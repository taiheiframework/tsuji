package dev.taihei.tsuji

import java.time.LocalDate

import zio.test.*

import dev.taihei.tsuji.Language.*
import dev.taihei.tsuji.TestJson.*

object MessageFamilySpec extends ZIOSpecDefault:

  private val date   = LocalDate.of(2026, 10, 23)
  private val German = Language("de")

  def spec = suite("MessageFamily and Message")(
    suite("definitions")(
      test("a message without arguments is a Message with the family's key") {
        val m: Message = Orders.cancelled
        assertTrue(m.key.value == "order.cancelled", m.toString == "Your order was cancelled")
      },
      test("a definition applied by name gives a key and the arguments, nothing else") {
        val m = Orders.shipped(count = 3, on = date)
        assertTrue(
          m.key.value == "order.shipped",
          m == Orders.shipped(3, date),
          m != Orders.shipped(4, date),
          Orders.shipped.argumentNames == List("count", "on"),
          Orders.shipped.family.eq(Orders),
        )
      },
      test("the family lists its definitions in order, with their inline translations") {
        assertTrue(
          Orders.definitions.map(_.key.value) == List("order.cancelled", "order.shipped", "order.greeting"),
          Orders.definitions.head.translations.keySet == Set(Dutch),
          Shapes.sourceLanguage == Dutch,
        )
      },
      test("a family prefix that is not one is refused") {
        val e = scala.util.Try(new MessageFamily("not a prefix") {}).failed.toOption
        assertTrue(e.exists(_.isInstanceOf[IllegalArgumentException]))
      },
    ),
    suite("compile-time checks")(
      test("a valid family compiles") {
        assertZIO(typeCheck("""object Ok extends MessageFamily("ok") { val a = message[(n: Int)]("{$n :number}") }"""))(
          Assertion.isRight
        )
      },
      test("invalid MF2") {
        assertZIO(typeCheck("""object Bad extends MessageFamily("bad") { val a = message("Hello {$name") }"""))(
          Assertion.isLeft(Assertion.containsString("not closed"))
        )
      },
      test("a currency that is not a three-letter code") {
        for
          bad  <- typeCheck(
            """object Bad extends MessageFamily("bad") { val a = message[(n: Int)]("{$n :number style=currency currency=euro}") }"""
          )
          good <- typeCheck(
            """object Ok extends MessageFamily("ok") { val a = message[(n: Int)]("{$n :number style=currency currency=EUR}") }"""
          )
        yield assertTrue(bad.left.exists(_.contains("three-letter currency code")), good.isRight)
      },
      test("style=currency without a currency") {
        assertZIO(
          typeCheck(
            """object Bad extends MessageFamily("bad") { val a = message[(n: Int)]("{$n :number style=currency}") }"""
          )
        )(Assertion.isLeft(Assertion.containsString("style=currency needs currency=")))
      },
      test("a variable that is not an argument") {
        assertZIO(
          typeCheck("""object Bad extends MessageFamily("bad") { val a = message[(name: String)]("Hi {$nmae}") }""")
        )(
          Assertion.isLeft(Assertion.containsString("$nmae is not an argument"))
        )
      },
      test(":number on a date and :datetime on an Int") {
        for
          a <- typeCheck(
            """object Bad extends MessageFamily("bad") { val a = message[(d: java.time.LocalDate)]("{$d :number}") }"""
          )
          b <- typeCheck("""object Bad extends MessageFamily("bad") { val a = message[(n: Int)]("{$n :datetime}") }""")
        yield assertTrue(
          a.left.exists(_.contains(":number does not apply to a date")),
          b.left.exists(_.contains(":datetime does not apply to a number")),
        )
      },
      test("a .match without a default variant") {
        assertZIO(
          typeCheck(
            """object Bad extends MessageFamily("bad") { val a = message[(n: Int)](".match {$n :plural} when one {a} when other {b}") }"""
          )
        )(Assertion.isLeft(Assertion.containsString("default variant")))
      },
      test("a literal inline translation is checked like the template") {
        assertZIO(
          typeCheck(
            """object Bad extends MessageFamily("bad") { val a = message[(n: Int)]("{$n}", Language.Dutch -> "{$m}") }"""
          )
        )(Assertion.isLeft(Assertion.containsString("Dutch translation of a: $m is not an argument")))
      },
      test("a template that is not a literal") {
        assertZIO(
          typeCheck("""object Bad extends MessageFamily("bad") { val t = "x"; val a = message(t) }""")
        )(Assertion.isLeft(Assertion.containsString("literal string")))
      },
      test("a message outside the body of a family") {
        assertZIO(
          typeCheck("""object Bad extends MessageFamily("bad") { def f = { val a = message("x"); a } }""")
        )(Assertion.isLeft(Assertion.containsString("val in the body")))
      },
      test("an argument type without a MessageArg") {
        assertZIO(
          typeCheck("""object Bad extends MessageFamily("bad") { val a = message[(x: Array[Int])]("{$x}") }""")
        )(Assertion.isLeft(Assertion.containsString("No MessageArg")))
      },
      test("arguments that are not a named tuple") {
        assertZIO(
          typeCheck("""object Bad extends MessageFamily("bad") { val a = message[(Int, String)]("x") }""")
        )(Assertion.isLeft(Assertion.containsString("named tuple")))
      },
      test("arguments applied with a wrong name or type do not compile") {
        for
          a <- typeCheck("""Orders.shipped(cnt = 3, on = java.time.LocalDate.now())""")
          b <- typeCheck("""Orders.shipped(count = "3", on = java.time.LocalDate.now())""")
        yield assertTrue(a.isLeft, b.isLeft)
      },
    ),
    suite("localize and the fallback chain")(
      test("the source language, an inline translation, and a fallback formatted for the reader") {
        given Catalogues = Catalogues()
        val m = Orders.shipped(count = 3, on = date)
        assertTrue(
          m.localize(English) == "3 items shipped on October 23, 2026",
          m.localize(Dutch) == "3 artikels verzonden op 23 oktober 2026",
          m.localize(Language("nl-BE")).startsWith("3 artikels verzonden op 23 oktober 2026"),
          // German has no template: the English one, with the date in German.
          m.localize(German) == "3 items shipped on 23. Oktober 2026",
          Orders.shipped(1, date).localize(English) == "One item shipped on October 23, 2026",
        )
      },
      test("a family in another source language falls back to it") {
        given Catalogues = Catalogues()
        assertTrue(
          Shapes.circle.localize(French) == "Cirkel",
          Shapes.circle.localize(English) == "Circle",
          Shapes.circle.toString == "Cirkel",
        )
      },
      test("the chain: exact, subtags removed, configured fallbacks walked the same way, source") {
        val c = Catalogues(fallbacks =
          Map(Language("pt-BR") -> Seq(Language("pt"), Language("es")), Language("lb") -> Seq(German))
        )
        assertTrue(
          c.fallbackChain(Language("zh-Hant-TW"), English).map(_.tag) == List("zh-Hant-TW", "zh-Hant", "zh", "en"),
          c.fallbackChain(Language("pt-BR"), English).map(_.tag) == List("pt-BR", "pt", "es", "en"),
          c.fallbackChain(Language("lb-LU"), English).map(_.tag) == List("lb-LU", "lb", "de", "en"),
          c.fallbackChain(Language("en-GB"), English).map(_.tag) == List("en-GB", "en"),
          c.fallbackChain(Language("de-x-private"), English).map(_.tag) == List("de-x-private", "de", "en"),
          c.fallbackChain(Language("de-CH-u-co-phonebk-x-a"), English).map(_.tag) ==
          List("de-CH-u-co-phonebk-x-a", "de-CH", "de", "en"),
          c.fallbackChain(Language("zh-Hant-TW"), English) eq c.fallbackChain(Language("zh-Hant-TW"), English),
        )
      },
      test("cyclic fallbacks end") {
        val c = Catalogues(fallbacks = Map(Language("aa") -> Seq(Language("bb")), Language("bb") -> Seq(Language("aa"))))
        assertTrue(c.fallbackChain(Language("aa"), English).map(_.tag) == List("aa", "bb", "en"))
      },
      test("a catalogue wins over the inline translation, a subtag catalogue over the source") {
        given c: Catalogues = Catalogues()
        val problems = c.add(Catalogue(Dutch, Map(Orders.cancelled.key -> "Geannuleerd")))
        c.add(Catalogue(Language("zh"), Map(Orders.cancelled.key -> "已取消")))
        assertTrue(
          problems.isEmpty,
          Orders.cancelled.localize(Dutch) == "Geannuleerd",
          Orders.cancelled.localize(Language("zh-Hant-TW")) == "已取消",
          c.languages == Set(Dutch, Language("zh")),
        )
      },
      test("a configured fallback is used") {
        given c: Catalogues = Catalogues(fallbacks = Map(Language("lb") -> Seq(German)))
        c.add(Catalogue(German, Map(Orders.greeting.key -> "Hallo {$name}")))
        assertTrue(Orders.greeting(name = "Ann").localize(Language("lb")) == "Hallo Ann")
      },
      test("a template that fails the checks is dropped with a problem, and its key falls back") {
        given c: Catalogues = Catalogues(Seq(Orders))
        val problems = c.add(Catalogue(German, Map(Orders.greeting.key -> "Hallo {$nmae}", Orders.cancelled.key -> "{$x")))
        assertTrue(
          problems.flatMap(_.key).toSet == Set(Orders.greeting.key, Orders.cancelled.key),
          problems.forall(_.language.contains(German)),
          c.problems == problems,
          Orders.greeting(name = "Ann").localize(German) == "Hello Ann",
          Orders.cancelled.localize(German) == "Your order was cancelled",
        )
      },
      test("a catalogue for a family not yet known is checked when its message is first shown") {
        given c: Catalogues = Catalogues()
        val problems = c.add(Catalogue(German, Map(Shapes.circle.key -> "{$nope}")))
        assertTrue(
          problems.isEmpty,
          Shapes.circle.localize(German) == "Cirkel",
          c.problems.exists(_.key.contains(Shapes.circle.key)),
        )
      },
      test("listeners hear of each added language until they stop") {
        val c     = Catalogues()
        var heard = List.empty[Language]
        val stop  = c.onAdded(l => heard = l :: heard)
        c.onAdded(_ => throw new RuntimeException("a listener that throws does not stop the others"))
        c.add(Catalogue(German, Map.empty))
        stop()
        c.add(Catalogue(French, Map.empty))
        assertTrue(heard == List(German))
      },
      test("arguments of every kind") {
        given Catalogues = Catalogues()
        val note = Shapes.note(id = OrderId("A-1"), by = None, in = French)
        assertTrue(
          note.localize(English) == "Bestelling A-1 door  in French",
          Shapes.note(OrderId("A-1"), Some("Ann"), French).localize(Dutch) == "Bestelling A-1 door Ann in Frans",
          Shapes.list(items = List(1, 2, 3)).localize(Dutch) == "Items: 1, 2, 3",
          Shapes.nested(field = "email", error = Orders.cancelled).localize(Dutch) ==
          "email: Uw bestelling werd geannuleerd",
        )
      },
      test("Text shows as it is") {
        given Catalogues = Catalogues()
        assertTrue(
          Text("Disk full", English).localize(Dutch) == "Disk full",
          Text.of(new RuntimeException()).value == "java.lang.RuntimeException",
          Text.of(new IllegalStateException("broken")) == Text("broken", English),
        )
      },
    ),
    suite("JSON, format 1")(
      test("only the key and typed arguments travel, with the source text") {
        // Writing needs no catalogue; a program that only writes gives the codec an empty one.
        given Catalogues = Catalogues()
        val json = Orders.shipped(count = 3, on = date).toJson
        assertTrue(
          json ==
          """{"key":"order.shipped","args":{"count":3,"on":"2026-10-23"},"text":"3 items shipped on October 23, 2026"}""",
          Orders.cancelled.toJson == """{"key":"order.cancelled","text":"Your order was cancelled"}""",
          Orders.cancelled.toJson(
            using Message.codec(withText = false)
          ) == """{"key":"order.cancelled"}""",
        )
      },
      test("a message round-trips with its argument types") {
        given Catalogues = Catalogues(Seq(Orders, Shapes))
        val messages = List(
          Orders.shipped(count = 3, on = date),
          Orders.cancelled,
          Shapes.note(OrderId("A-1"), Some("Ann"), Language("nl-BE")),
          Shapes.note(OrderId("A-1"), None, French),
          Shapes.list(List(1, 2)),
          Shapes.nested("email", Orders.greeting("Ann")),
        )
        val back     = messages.map(m => m.toJson.fromJson[Message])
        assertTrue(
          back == messages.map(Right(_)),
          back.head.map(_.localize(Dutch)) == Right("3 artikels verzonden op 23 oktober 2026"),
        )
      },
      test("an argument missing on the wire is an error, unless it is an Option") {
        given Catalogues = Catalogues(Seq(Orders, Shapes))
        assertTrue(
          """{"key":"order.shipped","args":{"count":3}}""".fromJson[Message].left.exists(_.contains(
            "argument on of order.shipped is missing"
          )),
          """{"key":"shape.note","args":{"id":"A","in":"fr"}}""".fromJson[Message] ==
          Right(Shapes.note(OrderId("A"), None, French)),
          """{"key":"order.shipped","args":{"count":"3","on":"2026-10-23"}}""".fromJson[Message].left.exists(_.contains(
            "expected a number"
          )),
          """{"key":"order.shipped","args":{"count":3.5,"on":"2026-10-23"}}""".fromJson[Message].left.exists(_.contains(
            "not an Int"
          )),
          """{"key":"order.shipped","args":{"count":3,"on":"tomorrow"}}""".fromJson[Message].left.exists(_.contains(
            "ISO 8601"
          )),
          """{"key":"order.cancelled","args":{"extra":1}}""".fromJson[Message] == Right(Orders.cancelled),
        )
      },
      test("an unknown key reads as its text, and is an error without one") {
        given Catalogues = Catalogues()
        val unknown = """{"key":"newer.message","args":{"n":1},"text":"From a newer server"}"""
        val read    = unknown.fromJson[Message]
        assertTrue(
          read.map(_.localize(Dutch)) == Right("From a newer server"),
          read.map(_.toJson) == Right(unknown),
          """{"key":"newer.message"}""".fromJson[Message].left.exists(_.contains("unknown message key")),
          """{"key":"not a key","text":"x"}""".fromJson[Message].isLeft,
          """{"text":"x"}""".fromJson[Message].isLeft,
        )
      },
      test("4.x JSON reads as a legacy message that renders as before") {
        given Catalogues = Catalogues()
        val v4   =
          """{"args":[],"namedArgs":{"name":"Ann"},"translationTemplates":{"English":"Hello {$name}","Dutch":"Dag {$name}"}}"""
        val read = v4.fromJson[Message]
        assertTrue(
          read.map(_.key.value) == Right("legacy"),
          read.map(_.localize(Dutch)) == Right("Dag Ann"),
          read.flatMap(_.toJson.fromJson[Message]) == read,
          v4.fromJson[Displayable].map(_.localize(English)) == Right("Hello Ann"),
        )
      },
      test("Text and Displayable") {
        given Catalogues = Catalogues(Seq(Orders))
        val text = Text("Disk full", English)
        assertTrue(
          text.toJson == """{"text":"Disk full","language":"en"}""",
          """{"text":"Disk full","language":"English"}""".fromJson[Text] == Right(text),
          (text: Displayable).toJson.fromJson[Displayable] == Right(text),
          (Orders.cancelled: Displayable).toJson.fromJson[Displayable] == Right(Orders.cancelled),
          """{"text":"x","language":"not a tag"}""".fromJson[Text].isLeft,
          "[]".fromJson[Displayable].isLeft,
        )
      },
      test("nesting deeper than 8 messages is refused, a list longer than 10000 too") {
        given Catalogues = Catalogues(Seq(Shapes, Orders))
        def nest(n: Int): Message =
          if n == 0 then Orders.cancelled else Shapes.nested("f", nest(n - 1))
        val list                  = (1 to 10001).mkString(",")
        assertTrue(
          nest(8).toJson.fromJson[Message].isRight,
          nest(9).toJson.fromJson[Message].left.exists(_.contains("nested deeper")),
          s"""{"key":"shape.list","args":{"items":[$list]}}""".fromJson[Message].left.exists(_.contains("longer than")),
        )
      },
      test("numbers that JSON cannot hold travel as strings") {
        object Nums extends MessageFamily("nums"):
          val d = message[(x: Double)]("{$x}")
        given Catalogues = Catalogues(Seq(Nums))
        val m = Nums.d(x = Double.NaN)
        assertTrue(m.toJson.contains("\"NaN\""), m.toJson.fromJson[Message].map(_.toJson) == Right(m.toJson))
      },
    ),
    suite("input that multiplies")(
      test("a 4.x message that repeats a long argument falls back instead of running out of memory") {
        given Catalogues = Catalogues(Seq(Shapes))
        val template = "{$0}" * 16000
        val wire     = s"""{"args":["${"A" * 50000}"],"translationTemplates":{"English":"$template"}}"""
        val shown    = wire.fromJson[Message].map(_.localize(English))
        val nested   =
          s"""{"key":"shape.nested","args":{"field":"f","error":$wire}}""".fromJson[Message].map(_.localize(Dutch))
        assertTrue(
          shown.exists(_.length <= MessageFormat2.MaxOutputLength),
          nested.exists(_.length <= 2 * MessageFormat2.MaxOutputLength),
          MessageFormat2.format(template, Map("0" -> "A" * 50000)) == template,
        )
      },
      test("a list of nested messages near the limit is held to one budget per message") {
        given Catalogues = Catalogues(CoreMessages.families)
        def element(repeat: Int)        =
          s"""{"args":[],"namedArgs":{"x":"${"A" * 1732}"},"translationTemplates":{"English":"${"{$x}" * repeat}"}}"""
        def failed(n: Int, repeat: Int) =
          s"""{"key":"taihei.validation.failed","args":{"errors":[${List.fill(n)(element(repeat)).mkString(",")}]}}"""
        val under                       = failed(300, 577).fromJson[Message].map(_.localize(English))
        val over                        = failed(2000, 578).fromJson[Message].map(_.localize(English))
        assertTrue(
          under == Right("Validation failed: {$errors}"),
          over == Right("Validation failed: {$errors}"),
        )
      },
      test("writing such a message back holds all its texts to one budget") {
        given Catalogues = Catalogues(Seq(Shapes) ++ CoreMessages.families)
        val leaf    = s"""{"args":["${"A" * 2000}"],"translationTemplates":{"English":"${"{$0}" * 500}"}}"""
        val wrapped = s"""{"key":"shape.nested","args":{"field":"f","error":$leaf}}"""
        val outer   =
          s"""{"key":"taihei.validation.failed","args":{"errors":[${List.fill(2000)(wrapped).mkString(",")}]}}"""
        val written = outer.fromJson[Message].map(_.toJson)
        assertTrue(written.exists(_.length < outer.length + 2 * Budget.PerMessage.toInt))
      },
      test("a 4.x message with too many or too long templates is refused") {
        given Catalogues = Catalogues()
        val many = (1 to 1001).map(i => s""""l$i":"x"""").mkString(",")
        val long = "x" * (MessageFormat2.MaxTemplateLength + 1)
        assertTrue(
          s"""{"args":[],"translationTemplates":{$many}}""".fromJson[Message].left.exists(_.contains("more than 1000")),
          s"""{"args":[],"translationTemplates":{"English":"$long"}}""".fromJson[Message].left.exists(_.contains(
            "longer than"
          )),
        )
      },
      // The timeouts below fire on the JVM only: Scala.js decodes synchronously on its one thread,
      // so there a regression shows as a run that does not finish rather than a failure.
      test("a 4.x message with many argument names of one hash is counted before they are hashed") {
        given Catalogues = Catalogues()
        val names = SameHash.names(17)
        val json  = names.map(n => s""""$n":"v"""").mkString(
          """{"args":[],"namedArgs":{""",
          ",",
          """},"translationTemplates":{"en":"x"}}""",
        )
        assertTrue(
          names.map(_.hashCode).distinct.size == 1,
          json.fromJson[Message].left.exists(_.contains("1000 arguments")),
        )
      } @@ TestAspect.timeout(zio.Duration.fromSeconds(15)),
      test("the kept arguments of an unknown message with many names of one hash are refused") {
        given Catalogues = Catalogues()
        val names              = SameHash.names(16)
        val args               = names.map(n => s""""$n":1""").mkString("{", ",", "}")
        val few                = SameHash.names(4).map(n => s""""$n":1""").mkString("{", ",", "}")
        def unknown(a: String) = s"""{"key":"newer.message","args":$a,"text":"x"}"""
        assertTrue(
          unknown(args).fromJson[Message].left.exists(_.contains("share a hash code")),
          unknown(s"""{"outer":$args}""").fromJson[Message].left.exists(_.contains("share a hash code")),
          unknown(few).fromJson[Message] == unknown(few).fromJson[Message],
          unknown(few).fromJson[Message].isRight,
        )
      } @@ TestAspect.timeout(zio.Duration.fromSeconds(15)),
      test("template errors escape what they echo, and stay short") {
        val errors = MessageFormat2.validate(".match {$n :plural} when \u001b\u202e {x} when * {y}") ++
          MessageFormat2.validate("{" + "\u00e9" * 60000 + "}")
        assertTrue(
          errors.nonEmpty,
          errors.forall(e => e.message.forall(c => c >= ' ' && c <= '~') && e.message.length <= 210),
        )
      },
      test("Text and Message take the reader's language by the same name") {
        given Catalogues = Catalogues()
        assertTrue(
          Text("x", English).localize(language = Dutch) == "x",
          Orders.cancelled.localize(language = Dutch).nonEmpty,
        )
      },
    ),
    suite("catalogue files")(
      test("a file parses, round-trips and keeps its problems") {
        val file =
          """{"format":1,"language":"de","messages":{"order.cancelled":"Storniert","bad key":"x","order.greeting":3}}"""
        val c    = Catalogue.parse(file)
        assertTrue(
          c.map(_.messages) == Right(Map(Orders.cancelled.key -> "Storniert")),
          c.map(_.problems.size) == Right(2),
          c.flatMap(cat => Catalogue.parse(cat.toJson)).map(_.messages) == c.map(_.messages),
        )
      },
      test("what is not a catalogue is refused without throwing") {
        val n   = 100000
        val bad = List(
          "",
          "null",
          "[]",
          "{",
          """{"format":2,"language":"de","messages":{}}""",
          """{"format":1,"language":"not a tag","messages":{}}""",
          """{"format":1,"language":"de","messages":[]}""",
          """{"format":1,"language":"de"}""",
          "[" * n + "]" * n,
          """{"format":1,"language":"de","messages":{"a.b":""" + "[" * n + "]" * n + "}}",
          "\"" + "a" * (Catalogue.MaxLength + 1) + "\"",
        )
        assertTrue(bad.forall(Catalogue.parse(_).isLeft))
      },
      test("a template that is too long is left out") {
        val file =
          s"""{"format":1,"language":"de","messages":{"a.b":"${"x" * (MessageFormat2.MaxTemplateLength + 1)}"}}"""
        assertTrue(Catalogue.parse(file).map(c => (c.messages.size, c.problems.size)) == Right((0, 1)))
      },
      test("a duplicate key keeps the first") {
        val file = """{"format":1,"language":"de","messages":{"a.b":"one","a.b":"two"}}"""
        assertTrue(Catalogue.parse(file).map(c => c.messages.values.toList -> c.problems.size) == Right(List("one") -> 1))
      },
      test("a catalogue whose keys share hash codes beyond what real keys do is refused") {
        val keys                  = SameHash.names(16).map("k." + _)
        def file(ks: Seq[String]) =
          ks.map(k => s""""$k":"x"""").mkString("""{"format":1,"language":"de","messages":{""", ",", "}}")
        val few                   = keys.take(SharedHashes.Max)
        assertTrue(
          keys.map(_.hashCode).distinct.size == 1,
          file(keys).length < Catalogue.MaxLength,
          Catalogue.parse(file(keys)).left.exists(_.message.contains("share a hash code")),
          Catalogue.parse(file(few)).map(_.messages.size) == Right(SharedHashes.Max),
          Catalogue.parse(file(keys.take(SharedHashes.Max + 1))).isLeft,
        )
      } @@ TestAspect.timeout(zio.Duration.fromSeconds(15)),
      test("random input never throws") {
        check(Gen.stringBounded(0, 300)(Gen.elements('{', '}', '[', ']', '"', ':', ',', '\\', 'a', '1', ' '))) { s =>
          assertTrue(Catalogue.parse(s).isLeft || Catalogue.parse(s).isRight)
        }
      },
    ),
    suite("verify")(
      test("a consistent application has no problems") {
        val nl = Catalogue(Dutch, Map(Orders.greeting.key -> "Dag {$name}"))
        assertTrue(Catalogues.verify(Seq(Orders), Seq(nl), required = Set(Dutch)).isEmpty)
      },
      test("missing keys, unknown keys, bad templates, duplicate prefixes and languages") {
        val de       = Catalogue(
          German,
          Map(Orders.greeting.key -> "Hallo {$nmae}", MessageKey.parse("order.gone").toOption.get -> "x"),
        )
        val problems = Catalogues.verify(Seq(Orders, Clash), Seq(de), required = Set(Dutch, German))
        def has(text: String, key: String) =
          problems.exists(p => p.message.contains(text) && p.key.forall(_.value == key))
        assertTrue(
          has("2 families have the prefix order", ""),
          has("$nmae is not an argument", "order.greeting"),
          has("no message has this key", "order.gone"),
          has("missing in a required language", "order.greeting"),
          problems.exists(p =>
            p.message.contains("missing") && p.language.contains(German) && p.key.contains(Orders.cancelled.key)
          ),
          !problems.exists(p => p.language.contains(Dutch) && p.key.contains(Orders.cancelled.key)),
        )
      },
      test("the functions of an argument of your own type are checked") {
        object Own extends MessageFamily("own"):
          val a = message[(id: OrderId)]("{$id :number}")
        assertTrue(Catalogues.verify(Seq(Own), Nil).exists(_.message.contains(":number does not apply to text")))
      },
    ),
  )

end MessageFamilySpec

// Names that all have one hash code: "Aa" and "BB" share theirs, so every string of n such pairs
// does too, and there are 2^n of them.
private object SameHash:

  def names(n: Int): IndexedSeq[String] = (0 until (1 << n)).map(i =>
    (0 until n).map(b => if (i >> b & 1) == 1 then "Aa" else "BB").mkString
  )
