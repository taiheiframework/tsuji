package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import zio.test.*

import dev.taihei.tsuji.Language.*

// Each bound at its value and one past it, on both platforms. The bounds are written out here
// rather than read from the code, so that a test fails when its bound moves by one.
object BoundsSpec extends ZIOSpecDefault:

  private val German = Language("de")

  private def catalogue(entries: Seq[String]): String =
    entries.mkString("""{"format":1,"language":"de","messages":{""", ",", "}}")

  def spec = suite("Bounds")(
    test("a message key has at most 255 characters") {
      val at = "a." + "b" * 253
      assertTrue(
        at.length == 255,
        MessageKey.parse(at).map(_.value) == Right(at),
        MessageKey.parse(at + "b") == Left("Not a message key: longer than 255 characters"),
      )
    },
    test("a date or time of 64 characters reaches its parser, one of 65 is refused as too long") {
      val context          = MessageArg.Context(Catalogues(), 0)
      def read[A](
        s: String
      )(
        using arg: MessageArg[A]
      ): Either[String, A] = arg.decode(JsonTree.Str(s), context)
      // No ISO 8601 date or time is that long: at the bound the text is parsed, and refused as
      // not a date, which says the length was accepted.
      val at               = "2026-10-23" + "x" * 54
      val past             = at + "x"
      assertTrue(
        at.length == 64,
        read[LocalDate](at) == Left(s"not an ISO 8601 date: '$at'"),
        read[LocalDate](past) == Left("not an ISO 8601 date: too long"),
        read[LocalDateTime](at) == Left(s"not an ISO 8601 date and time: '$at'"),
        read[LocalDateTime](past) == Left("not an ISO 8601 date and time: too long"),
        read[LocalTime](at) == Left(s"not an ISO 8601 time: '$at'"),
        read[LocalTime](past) == Left("not an ISO 8601 time: too long"),
      )
    },
    test("a catalogue file has at most 4194304 characters") {
      val core           = catalogue(List("\"a.b\":\"x\""))
      def padded(n: Int) = core + " " * (n - core.length)
      assertTrue(
        Catalogue.parse(padded(4194304)).map(_.messages.size) == Right(1),
        Catalogue.parse(padded(4194305)) ==
        Left(CatalogueProblem("a catalogue is at most 4194304 characters")),
      )
    },
    test("a catalogue holds at most 100000 messages") {
      def file(n: Int) = catalogue((0 until n).map(i => f"\"a.k$i%06d\":\"x\""))
      assertTrue(
        Catalogue.parse(file(100000)).map(_.messages.size) == Right(100000),
        Catalogue.parse(file(100001)) ==
        Left(CatalogueProblem("a catalogue holds at most 100000 messages", Some(German))),
      )
    },
    test("a registry keeps the first 1000 problems") {
      def problems(n: Int): Either[CatalogueProblem, List[String]] =
        val c = Catalogues()
        Catalogue
          .parse(catalogue((1 to n).map(i => f"\"k$i%04d\":\"x\"")))
          .map(file => c.add(file))
          .map(_ => c.problems.map(_.message))
      val first = (1 to 1000).map(i => f"Not a message key: 'k$i%04d'").toList
      assertTrue(problems(1000) == Right(first), problems(1001) == Right(first))
    },
    test("a fallback chain walks 32 languages and not a 33rd") {
      val languages                     = PlainLanguages(33)
      def chain(n: Int): List[Language] =
        val walked = languages.take(n)
        Catalogues(fallbacks = walked.zip(walked.tail).map((a, b) => a -> Seq(b)).toMap)
          .fallbackChain(walked.head, English)
      assertTrue(
        languages.size == 33,
        chain(32) == languages.take(32) :+ English,
        chain(33) == languages.take(32) :+ English,
      )
    },
    test("a registry keeps 1024 fallback chains, and the 1025th drops them all") {
      // A kept chain is the same object when it is asked for again; a dropped one is computed
      // anew. The first and the 1024th chain show whether all were kept or all were dropped.
      def kept(n: Int): (Boolean, Boolean) =
        val c         = Catalogues()
        val languages = PlainLanguages(n)
        val chains    = languages.map(c.fallbackChain(_, English))
        (
          c.fallbackChain(languages.head, English) eq chains.head,
          c.fallbackChain(languages(1023), English) eq chains(1023),
        )
      assertTrue(
        PlainLanguages(1025).size == 1025,
        kept(1024) == (true, true),
        kept(1025) == (false, false),
      )
    },
  )

end BoundsSpec
