package dev.taihei.tsuji.legacy

import java.time.LocalDate

import zio.test.*

import dev.taihei.tsuji.Language
import dev.taihei.tsuji.TestJson.*

// The 4.x Message, kept as legacy.Message until 7.0.0, behaves as it does in 4.0.x, its JSON
// codec included.
object MessageSpec extends ZIOSpecDefault:

  def spec = suite("MessageSpec")(
    test("a fallback template chooses its plural variant by its own language, not the reader's") {
      val items       = ".match {$n :plural} when one {{$n} item} when * {{$n} items}"
      def msg(n: Int) = Message.withNamedArgs(Language.English -> items)("n" -> n)
      assertTrue(
        msg(21).translate(Language("ru")) == "21 items",
        msg(0).translate(Language("hi")) == "0 items",
        msg(1).translate(Language("ru")) == "1 item",
      )
    },
    test("a reader in pt-PT keeps the rules of pt-PT when the template is the pt one") {
      val coisas      = ".match {$n :plural} when one {{$n} coisa} when * {{$n} coisas}"
      def msg(n: Int) = Message.withNamedArgs(Language("pt") -> coisas)("n" -> n)
      assertTrue(
        msg(0).translate(Language("pt-PT")) == "0 coisas",
        msg(0).translate(Language("pt-BR")) == "0 coisa",
      )
    },
    suite("Message creation with positional arguments")(
      test("with0Args creates message without arguments") {
        val msg = Message.with0Args(
          Language.English -> "Hello",
          Language.Dutch -> "Dag",
        )
        assertTrue(
          msg.args.isEmpty,
          msg.namedArgs.isEmpty,
          msg.translationTemplates.size == 2,
        )
      },
      test("with1Arg creates message with one argument") {
        val msg = Message.withNamedArgs(
          Language.English -> "Hello {$name}"
        )("name" -> "World")
        assertTrue(
          msg.namedArgs.size == 1,
          msg.namedArgs("name") == "World",
        )
      },
      test("with2Args creates message with two arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "User {$user} has {$points} points"
        )("user" -> "Alice", "points" -> 100)
        assertTrue(
          msg.namedArgs.size == 2,
          msg.namedArgs("user") == "Alice",
          msg.namedArgs("points") == 100,
        )
      },
      test("with3Args creates message with three arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$year}/{$month}/{$day}"
        )("year" -> "2024", "month" -> "01", "day" -> "15")
        assertTrue(
          msg.namedArgs.size == 3,
          msg.namedArgs("year") == "2024",
          msg.namedArgs("month") == "01",
          msg.namedArgs("day") == "15",
        )
      },
      test("with4Args creates message with four arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$a} {$b} {$c} {$d}"
        )("a" -> "A", "b" -> "B", "c" -> "C", "d" -> "D")
        assertTrue(
          msg.namedArgs.size == 4,
          msg.namedArgs("a") == "A",
          msg.namedArgs("b") == "B",
          msg.namedArgs("c") == "C",
          msg.namedArgs("d") == "D",
        )
      },
      test("with5Args creates message with five arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$a} + {$b} + {$c} + {$d} + {$e}"
        )("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
        assertTrue(
          msg.namedArgs.size == 5,
          msg.namedArgs("a") == 1,
          msg.namedArgs("e") == 5,
        )
      },
      test("with6Args creates message with six arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$a},{$b},{$c},{$d},{$e},{$f}"
        )("a" -> 10, "b" -> 20, "c" -> 30, "d" -> 40, "e" -> 50, "f" -> 60)
        assertTrue(
          msg.namedArgs.size == 6,
          msg.namedArgs("a") == 10,
          msg.namedArgs("f") == 60,
        )
      },
      test("with7Args creates message with seven arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$c0}{$c1}{$c2}{$c3}{$c4}{$c5}{$c6}"
        )(
          "c0" -> "M",
          "c1" -> "o",
          "c2" -> "n",
          "c3" -> "d",
          "c4" -> "a",
          "c5" -> "y",
          "c6" -> "!",
        )
        assertTrue(
          msg.namedArgs.size == 7,
          msg.namedArgs("c0") == "M",
          msg.namedArgs("c6") == "!",
        )
      },
      test("with8Args creates message with eight arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Sum: {$n1} + {$n2} + {$n3} + {$n4} + {$n5} + {$n6} + {$n7} + {$n8}"
        )(
          "n1" -> 1,
          "n2" -> 2,
          "n3" -> 3,
          "n4" -> 4,
          "n5" -> 5,
          "n6" -> 6,
          "n7" -> 7,
          "n8" -> 8,
        )
        assertTrue(
          msg.namedArgs.size == 8,
          msg.namedArgs("n1") == 1,
          msg.namedArgs("n8") == 8,
        )
      },
      test("with9Args creates message with nine arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$n1}{$n2}{$n3}{$n4}{$n5}{$n6}{$n7}{$n8}{$n9}"
        )(
          "n1" -> 1,
          "n2" -> 2,
          "n3" -> 3,
          "n4" -> 4,
          "n5" -> 5,
          "n6" -> 6,
          "n7" -> 7,
          "n8" -> 8,
          "n9" -> 9,
        )
        assertTrue(
          msg.namedArgs.size == 9,
          msg.namedArgs("n1") == 1,
          msg.namedArgs("n9") == 9,
        )
      },
      test("with10Args creates message with ten arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Ten args: {$n0} through {$n9}"
        )(
          "n0" -> 0,
          "n1" -> 1,
          "n2" -> 2,
          "n3" -> 3,
          "n4" -> 4,
          "n5" -> 5,
          "n6" -> 6,
          "n7" -> 7,
          "n8" -> 8,
          "n9" -> 9,
        )
        assertTrue(
          msg.namedArgs.size == 10,
          msg.namedArgs("n0") == 0,
          msg.namedArgs("n9") == 9,
        )
      },
    ),
    suite("Message creation with named arguments")(
      test("withNamedArgs creates message with named arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Hello {$name}"
        )("name" -> "World")
        assertTrue(
          msg.args.isEmpty,
          msg.namedArgs.size == 1,
          msg.namedArgs("name") == "World",
        )
      },
      test("withNamedArgs supports multiple named arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$firstName} {$lastName}"
        )("firstName" -> "John", "lastName" -> "Doe")
        assertTrue(
          msg.namedArgs.size == 2,
          msg.namedArgs("firstName") == "John",
          msg.namedArgs("lastName") == "Doe",
        )
      },
    ),
    suite("Message argument types")(
      test("supports String arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Text: {$value}"
        )("value" -> "hello")
        assertTrue(msg.translate(Language.English).contains("hello"))
      },
      test("supports Int arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Count: {$value}"
        )("value" -> 42)
        assertTrue(msg.translate(Language.English).contains("42"))
      },
      test("supports Long arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Value: {$value}"
        )("value" -> 9999999999L)
        assertTrue(msg.translate(Language.English).contains("9999999999"))
      },
      test("supports Double arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Price: {$value}"
        )("value" -> 19.99)
        assertTrue(msg.translate(Language.English).contains("19.99"))
      },
      test("supports Boolean arguments") {
        val msg = Message.withNamedArgs(
          Language.English -> "Active: {$value}"
        )("value" -> true)
        assertTrue(msg.translate(Language.English).contains("true"))
      },
      test("supports LocalDate arguments") {
        val date = LocalDate.of(2024, 1, 15)
        val msg  = Message.withNamedArgs(
          Language.English -> "Date: {$value}"
        )("value" -> date)
        assertTrue(msg.translate(Language.English).contains("2024-01-15"))
      },
    ),
    suite("Message translation")(
      test("translate returns correct language version") {
        val msg = Message.with0Args(
          Language.English -> "Hello",
          Language.Dutch -> "Dag",
          Language.French -> "Bonjour",
        )
        assertTrue(
          msg.translate(Language.English) == "Hello",
          msg.translate(Language.Dutch) == "Dag",
          msg.translate(Language.French) == "Bonjour",
        )
      },
      test("translate falls back to default language if translation missing") {
        val msg = Message.with0Args(
          Language.English -> "Hello"
        )
        assertTrue(
          msg.translate(Language.English) == "Hello",
          msg.translate(Language.Dutch) == "Hello", // falls back to English
          msg.translate(Language.French) == "Hello", // falls back to English
        )
      },
      test("render is an alias for translate") {
        val msg = Message.with0Args(
          Language.English -> "Test"
        )
        assertTrue(
          msg.render(Language.English) == msg.translate(Language.English)
        )
      },
      test("toString returns English translation") {
        val msg = Message.with0Args(
          Language.English -> "Default",
          Language.Dutch -> "Standaard",
        )
        assertTrue(msg.toString == "Default")
      },
    ),
    suite("Message with MessageFormat2 syntax")(
      test("simple variable substitution works") {
        val msg = Message.withNamedArgs(
          Language.English -> "Hello {$name}"
        )("name" -> "World")
        assertTrue(msg.translate(Language.English) == "Hello World")
      },
      test("multiple variable substitution works") {
        val msg = Message.withNamedArgs(
          Language.English -> "{$user} has {$points} points"
        )("user" -> "Alice", "points" -> 100)
        assertTrue(msg.translate(Language.English) == "Alice has 100 points")
      },
    ),
    suite("Message from a throwable")(
      test("carries the throwable's message") {
        val text = Message.of(new IllegalStateException("broken")).translate(Language.English)
        assertTrue(text == "broken")
      },
      test("a throwable without a message gives its class name instead of failing") {
        val m = Message.of(new RuntimeException())
        assertTrue(m.translate(Language.English) == "java.lang.RuntimeException", m.nonEmpty)
      },
    ),
    suite("Message JSON codec")(
      test("Message can be encoded to JSON") {
        val msg  = Message.withNamedArgs(Language.English -> "Hello {$name}")("name" -> "World")
        val json = msg.toJson
        assertTrue(json.nonEmpty)
      },
      test("Message can be decoded from JSON") {
        val msg     = Message.withNamedArgs(Language.English -> "Test {$value}")("value" -> "Value")
        val encoded = msg.toJson
        val decoded = encoded.fromJson[Message]
        assertTrue(decoded.isRight)
      },
      test("Message round-trips through JSON preserving translations") {
        val original = Message.with0Args(
          Language.English -> "Hello",
          Language.Dutch -> "Dag",
        )
        val encoded  = original.toJson
        val decoded  = encoded.fromJson[Message]
        decoded match
          case Right(msg) =>
            assertTrue(
              msg.translate(Language.English) == "Hello",
              msg.translate(Language.Dutch) == "Dag",
            )
          case Left(_)    => assertTrue(false)
      },
    ),
    suite("Message properties")(
      test("nonEmpty is true for non-empty messages") {
        val msg = Message.with0Args(Language.English -> "Test")
        assertTrue(msg.nonEmpty)
      },
      test("nonEmpty is false for empty messages") {
        val msg = Message.with0Args(Language.English -> "")
        assertTrue(!msg.nonEmpty)
      },
    ),
    suite("Message.of(throwable) convenience method")(
      test("creates message from throwable") {
        val exception = new RuntimeException("Something went wrong")
        val msg       = Message.of(exception)
        assertTrue(
          msg.translate(Language.English) == "Something went wrong"
        )
      },
      test("creates message from throwable with null message") {
        val exception = new NullPointerException("Null reference")
        val msg       = Message.of(exception)
        assertTrue(
          msg.translate(Language.English).contains("Null reference")
        )
      },
      test("handles IllegalArgumentException") {
        val exception = new IllegalArgumentException("Invalid input provided")
        val msg       = Message.of(exception)
        assertTrue(
          msg.translate(Language.English).contains("Invalid input")
        )
      },
      test("handles custom exceptions") {
        case class CustomException(details: String) extends Exception(s"Custom error: $details")
        val exception = CustomException("test details")
        val msg       = Message.of(exception)
        assertTrue(
          msg.translate(Language.English).contains("Custom error"),
          msg.translate(Language.English).contains("test details"),
        )
      },
      test("Message.of creates English-only translation") {
        val exception = new Exception("Error message")
        val msg       = Message.of(exception)
        assertTrue(
          msg.translationTemplates.size == 1,
          msg.translationTemplates.contains(Language.English),
        )
      },
    ),
    suite("Reading a message from JSON")(
      test("a message at every bound reads and writes back") {
        val long      = "x" * 65536
        val templates = (1 until 1000).map(i => Language(f"en-x-t$i%04d") -> s"Hello $i").toMap +
          (Language.English -> long)
        val named     = (1 until 500).map(i => s"n$i" -> "v").toMap + (long -> long)
        val msg       = Message(List.fill(500)(long), named, templates)
        assertTrue(
          msg.translationTemplates.size == 1000,
          msg.args.size + msg.namedArgs.size == 1000,
          msg.toJson.fromJson[Message] == Right(msg),
        )
      },
      test("more than 1000 templates are refused") {
        val templates = (0 to 1000).map(i => Language(f"en-x-t$i%04d") -> "x").toMap
        val result    = Message(Nil, Map.empty, templates).toJson.fromJson[Message]
        assertTrue(templates.size == 1001, result.left.exists(_.contains("1000 templates")))
      },
      test("a template longer than 65536 characters is refused") {
        val ok  = Message.of("x" * 65536).toJson.fromJson[Message]
        val bad = Message.of("x" * 65537).toJson.fromJson[Message]
        assertTrue(ok.isRight, bad.left.exists(_.contains("template is longer")))
      },
      test("more than 1000 arguments, positional and named together, are refused") {
        val positional = Message(List.fill(1001)("a"), Map.empty, Map(Language.English -> "x"))
        val mixed      = Message(
          List.fill(600)("a"),
          (0 until 401).map(i => s"n$i" -> "v").toMap,
          Map(Language.English -> "x"),
        )
        assertTrue(
          positional.toJson.fromJson[Message].left.exists(_.contains("1000 arguments")),
          mixed.toJson.fromJson[Message].left.exists(_.contains("1000 arguments")),
        )
      },
      test("an argument longer than 65536 characters is refused") {
        val positional = Message(List("a" * 65537), Map.empty, Map(Language.English -> "x"))
        val named      = Message(Nil, Map("v" -> "a" * 65537), Map(Language.English -> "x"))
        assertTrue(
          positional.toJson.fromJson[Message].left.exists(_.contains("argument is longer")),
          named.toJson.fromJson[Message].left.exists(_.contains("argument is longer")),
        )
      },
      test("an argument name longer than 65536 characters is refused") {
        val msg = Message(Nil, Map("n" * 65537 -> "v"), Map(Language.English -> "x"))
        assertTrue(msg.toJson.fromJson[Message].left.exists(_.contains("argument name is longer")))
      },
      // The timeouts below fire on the JVM only: Scala.js decodes synchronously on its one thread,
      // so there a regression shows as a run that does not finish rather than a failure.
      test("many argument names with one hash are counted before they are hashed") {
        // "Aa" and "BB" have the same hashCode, so every name built from them shares one.
        val names = (0 until (1 << 17)).map(i =>
          (0 until 17).map(b => if (i >> b & 1) == 1 then "Aa" else "BB").mkString
        )
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
      test("many template languages with one hash are counted before they are hashed") {
        // "an" and "c0" have the same hashCode; these private-use tags are well formed.
        val tags = (0 until (1 << 16)).map(i =>
          "en-x-" + (0 until 4).map(sub =>
            (0 until 4).map(b => if (i >> (sub * 4 + b) & 1) == 1 then "an" else "c0").mkString
          ).mkString("-")
        )
        val json = tags.map(t => s""""$t":"x"""").mkString(
          """{"args":[],"translationTemplates":{""",
          ",",
          "}}",
        )
        assertTrue(
          tags.map(_.hashCode).distinct.size == 1,
          Language.parse(tags.head).isRight,
          json.fromJson[Message].left.exists(_.contains("1000 templates")),
        )
      } @@ TestAspect.timeout(zio.Duration.fromSeconds(15)),
      test("reading keeps the 4.0.1 shape: defaults, built-in names, duplicate keys") {
        val noNamed   = """{"args":["a"],"translationTemplates":{"English":"x {$0}","nl":"y"}}"""
        val duplicate = """{"args":[],"translationTemplates":{"en":"first","English":"last"}}"""
        val badKey    = """{"args":[],"translationTemplates":{"not a tag!":"x"}}"""
        assertTrue(
          noNamed.fromJson[Message] ==
          Right(Message(List("a"), Map.empty, Map(Language.English -> "x {$0}", Language.Dutch -> "y"))),
          duplicate.fromJson[Message].map(_.translationTemplates) == Right(Map(Language.English -> "last")),
          badKey.fromJson[Message].left.exists(e => e.contains("not a language") && !e.contains("not a tag")),
        )
      },
      test("the largest message that reads shows its template instead of a text past the limit") {
        val template = "{$0}" * 16384
        val json     =
          s"""{"args":["${"A" * 65536}"],"namedArgs":{},"translationTemplates":{"en":"$template"}}"""
        val shown    = json.fromJson[Message].map(_.translate(Language.English))
        assertTrue(template.length == 65536, shown == Right(template))
      },
    ),
  )

end MessageSpec
