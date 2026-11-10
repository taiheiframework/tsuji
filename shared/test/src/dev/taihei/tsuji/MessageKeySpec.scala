package dev.taihei.tsuji

import zio.test.*

import com.github.plokhotnyuk.jsoniter_scala.core.*

/** What is a message key and what is not, and what a refusal says. */
object MessageKeySpec extends ZIOSpecDefault:

  private def accepted(value: String): Boolean = MessageKey.parse(value).isRight

  def spec = suite("MessageKey")(
    test("a prefix and a name, each part a letter followed by letters, digits and _") {
      assertTrue(
        MessageKey.parse("order.shipped").map(_.value) == Right("order.shipped"),
        accepted("taihei.validation.nonEmpty"),
        accepted("a.b"),
        accepted("order.line_2"),
        accepted("Order.Shipped"),
      )
    },
    test("a key needs a dot, and no part may be empty") {
      assertTrue(
        !accepted("order"),
        !accepted(""),
        !accepted("."),
        !accepted("order."),
        !accepted(".shipped"),
        !accepted("order..shipped"),
      )
    },
    test("a part starts with an ASCII letter and holds ASCII letters, digits and _ only") {
      assertTrue(
        !accepted("order.2nd"),
        !accepted("order._private"),
        !accepted("2.shipped"),
        !accepted("order.shipped-late"),
        !accepted("order.shipped late"),
        !accepted("order.expédié"),
        // The Turkish dotless i is a letter to Character.isLetter, not here.
        !accepted("order.shıpped"),
        !accepted("order/shipped"),
      )
    },
    test("the length is bounded at 255 characters") {
      val longest = "a." + "b" * 253
      assertTrue(
        longest.length == 255,
        accepted(longest),
        MessageKey.parse(longest + "b") == Left("Not a message key: longer than 255 characters"),
      )
    },
    test("a refusal quotes the input so that it can be logged as it is") {
      assertTrue(
        MessageKey.parse("order") == Left("Not a message key: 'order'"),
        MessageKey.parse("a\nb.c") == Left("Not a message key: 'a\\u000ab.c'"),
        MessageKey.parse("\u001b[31m.x") == Left("Not a message key: '\\u001b[31m.x'"),
        MessageKey.parse("é" * 100).left.exists(e => e.endsWith("...'") && e.length < 600),
      )
    },
    test("a prefix is one part or more, a part is never empty") {
      assertTrue(
        MessageKey.isPrefix("order"),
        MessageKey.isPrefix("taihei.validation"),
        !MessageKey.isPrefix(""),
        !MessageKey.isPrefix("order."),
        !MessageKey.isPrefix("a" * 256),
        MessageKey.isPart("line_2"),
        !MessageKey.isPart(""),
        !MessageKey.isPart("2nd"),
      )
    },
    test("a key travels as a JSON string and is checked when read") {
      val key = MessageKey.parse("order.shipped").toOption.get
      assertTrue(
        writeToString(key) == "\"order.shipped\"",
        readFromString[MessageKey]("\"order.shipped\"") == key,
        scala.util.Try(readFromString[MessageKey]("\"order\"")).isFailure,
        scala.util.Try(readFromString[MessageKey]("3")).isFailure,
      )
    },
    test("keys sort by their text") {
      val keys = List("b.a", "a.b", "a.a").flatMap(MessageKey.parse(_).toOption)
      assertTrue(keys.sorted.map(_.value) == List("a.a", "a.b", "b.a"))
    },
  )

end MessageKeySpec
