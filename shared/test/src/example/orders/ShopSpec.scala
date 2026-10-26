package example.orders

import java.time.LocalDate

import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*
import dev.taihei.tsuji.TestJson.*

object ShopSpec extends ZIOSpecDefault:

  def spec = suite("a family defined outside dev.taihei.tsuji")(
    test("its messages show, round-trip and verify") {
      given Catalogues = Catalogues(Seq(Shop))
      val opens = Shop.opens(on = LocalDate.of(2026, 11, 2), by = "Ann")
      assertTrue(
        Shop.closed.localize(Dutch) == "De winkel is gesloten",
        opens.localize(Dutch) == "Opent op 2 november 2026, zegt Ann",
        opens.localize(English) == "Opens on November 2, 2026, says Ann",
        opens.toJson.fromJson[Message] == Right(opens),
        Catalogues.verify(Seq(Shop), Nil, required = Set(Dutch)).isEmpty,
      )
    },
    test("a lazy val adds its definition once, when it is first used") {
      val a = Shop.later
      val b = Shop.later
      assertTrue(a == b, Shop.definitions.count(_.key.value == "example.shop.later") == 1)
    },
    test("two messages under one val name are reported by verify") {
      object Twice extends MessageFamily("example.twice"):
        val dup =
          message("first"); message("second")
      assertTrue(
        Twice.dup.toString == "second",
        Catalogues.verify(Seq(Twice), Nil).exists(p =>
          p.message == "defined twice" && p.key.exists(_.value == "example.twice.dup")
        ),
      )
    },
    test("a message is a val of its family object, nowhere else") {
      for
        inDef    <- typeCheck("""object F extends MessageFamily("f") { def d = message("x") }""")
        inObject <- typeCheck("""object F extends MessageFamily("f") { object Inner { val x = message("x") } }""")
        inClass  <- typeCheck("""class F extends MessageFamily("f") { val x = message("x") }""")
        local    <- typeCheck("""object F extends MessageFamily("f") { def f = { val a = message("x"); a } }""")
        ok       <- typeCheck("""object F extends MessageFamily("f") { val x = message("x") }""")
      yield assertTrue(
        List(
          inDef,
          inObject,
          inClass,
          local,
        ).forall(_.left.exists(_.contains("val in the body of its MessageFamily object"))),
        ok.isRight,
      )
    },
  )

end ShopSpec
