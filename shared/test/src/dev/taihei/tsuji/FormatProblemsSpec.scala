package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.Language.*

// A message that cannot be formatted falls back; these say that Catalogues.problems names why.
object FormatProblemsSpec extends ZIOSpecDefault:

  private val German = Language("de")

  // A value whose text cannot be made: formatting it throws, with a message that must not reach a
  // problem.
  final class Unprintable:
    override def toString: String = throw new IllegalStateException("secret input")

  given MessageArg[Unprintable] = new MessageArg[Unprintable](
    ArgKind.Text,
    (
      a,
      _,
      _,
      _,
    ) => a,
    (_, _) => JsonTree.Null,
    (_, _) => Left("not readable"),
  )

  final class Boom

  // An argument whose own conversion throws, before any template is tried.
  given MessageArg[Boom] =
    MessageArg.as[Boom, String](_ => throw new IllegalArgumentException("secret input"), _ => Left("no"))

  object Failing extends MessageFamily("failing"):

    val repeated = message[(x: String)]("{$x}", German -> "{$x}{$x}{$x}")

    val unprintable = message[(u: Unprintable)]("Value {$u}")

    val boom = message[(b: Boom)]("Value {$b}")

  private val long = "A" * 400000

  def spec = suite("Catalogues.problems for messages that fail to format")(
    test("a translation past the output limit falls back and is named once, with its language") {
      given c: Catalogues = Catalogues(Seq(Failing))
      val first  = Failing.repeated(x = long).localize(German)
      val second = Failing.repeated(x = long).localize(German)
      assertTrue(
        first == long,
        second == long,
        c.problems == List(CatalogueProblem(
          s"formatted text past ${MessageFormat2.MaxOutputLength} characters",
          Some(German),
          Some(Failing.repeated.key),
        )),
        c.problems.map(_.toString) == List("de: failing.repeated: formatted text past 1000000 characters"),
      )
    },
    test("a value that throws while formatted shows the template and names the class, not the message") {
      given c: Catalogues = Catalogues(Seq(Failing))
      val shown = Failing.unprintable(u = new Unprintable).localize(Dutch)
      assertTrue(
        shown == "Value {$u}",
        c.problems == List(CatalogueProblem(
          "formatting threw java.lang.IllegalStateException",
          Some(English),
          Some(Failing.unprintable.key),
        )),
        !c.problems.exists(_.toString.contains("secret")),
      )
    },
    test("an argument that throws before any template is tried is named with the reader's language") {
      given c: Catalogues = Catalogues(Seq(Failing))
      val shown = Failing.boom(b = new Boom).localize(French)
      assertTrue(
        shown == "Value {$b}",
        c.problems == List(CatalogueProblem(
          "formatting threw java.lang.IllegalArgumentException",
          Some(French),
          Some(Failing.boom.key),
        )),
        !c.problems.exists(_.toString.contains("secret")),
      )
    },
    test("a message past the budget of one message is named apart from the output limit") {
      val c      = Catalogues(Seq(Orders))
      val budget = Budget.fresh()
      budget.charge(Budget.PerMessage.toInt - 3)
      val shown  = c.render(Orders.greeting(name = "Ann"), English, budget)
      assertTrue(
        shown == "Hello {$name}",
        c.problems == List(CatalogueProblem(
          s"formatted text past ${Budget.PerMessage} characters for one message, nested messages included",
          Some(English),
          Some(Orders.greeting.key),
        )),
      )
    },
    test("a listener that throws is named once per language, and the others still hear") {
      val c     = Catalogues()
      var heard = List.empty[Language]
      c.onAdded(_ => throw new RuntimeException("secret input"))
      c.onAdded(l => heard = l :: heard)
      c.add(Catalogue(German, Map.empty))
      c.add(Catalogue(German, Map.empty))
      c.add(Catalogue(French, Map.empty))
      assertTrue(
        heard == List(French, German, German),
        c.problems == List(
          CatalogueProblem("an onAdded listener threw java.lang.RuntimeException", Some(German)),
          CatalogueProblem("an onAdded listener threw java.lang.RuntimeException", Some(French)),
        ),
      )
    },
    test("problems stop at the first 1000") {
      val c         = Catalogues()
      val letters   = 'a' to 'z'
      val languages = (for a <- letters; b <- letters; d <- letters yield s"$a$b$d")
        .take(1005)
        .flatMap(Language.parse(_).toOption)
      c.onAdded(_ => throw new RuntimeException("listener"))
      languages.foreach(l => c.add(Catalogue(l, Map.empty)))
      assertTrue(
        languages.size == 1005,
        c.problems.size == 1000,
        c.problems.last.language == Some(languages(999)),
      )
    },
  )

end FormatProblemsSpec
