package dev.taihei.tsuji

import zio.test.*

// Numbers beyond the reader's bounds in a catalogue are refused, on both platforms.
object BoundedNumbersSpec extends ZIOSpecDefault:

  private val extreme = List(
    "7.3333333333333333333e-2147483629",
    "1" * 76 + "e-2147483648",
    "1e-2147483648",
    "0e2147483647",
    "11111111111111111111.1e-2147483647",
    "1e+0001000000000",
  )

  // A throw becomes a Left that holds only the exception's class name, never its message, which
  // may carry a reader error's text: it fails a test that expects a given refusal and a test that
  // expects a successful read alike.
  private def guarded[A](parse: => Either[A, Any]): Either[String, Any] =
    try parse.left.map(_.toString)
    catch case t: Throwable => Left(s"threw ${t.getClass.getName}")

  private def catalogue(note: String, message: String = "\"Eins\""): String =
    s"""{"format":1,"language":"de","note":$note,"messages":{"numbers.one":$message}}"""

  def spec = suite("Numbers out of bounds")(
    test("a catalogue with an extreme exponent anywhere is refused, and parse never throws") {
      val inputs  = extreme.flatMap: n =>
        List(
          s"""{"format":$n,"language":"de","messages":{}}""",
          catalogue("1", n),
          catalogue(n),
        )
      val results = inputs.map(i => guarded(Catalogue.parse(i)))
      assertTrue(
        results.size == 18,
        results.forall(_.left.exists(_.contains("exponent of more than 9 digits"))),
      )
    },
    // The three timing tests retry a correct but slow run on a loaded machine (TimeBudget); the
    // budgets stay as they are, and a wrong result fails at once.
    test("a catalogue with a number of a million digits is refused at once") {
      val input = catalogue("1" + "0" * 1000000)
      TimeBudget.within("a million digits", 1000)(guarded(Catalogue.parse(input))): result =>
        assertTrue(result.left.exists(_.contains("longer than 1100 characters")))
    },
    test("4M characters of catalogue numbers just under the bound are read in time that grows with them") {
      val near  = "0." + "0" * (JsonTreeReader.MaxNumberLength - 3) + "1"
      val many  = List.fill((Catalogue.MaxLength - 200) / (near.length + 1))(near).mkString("[", ",", "]")
      val input = catalogue(many)
      assertTrue(input.length <= Catalogue.MaxLength, input.length > 4000000) &&
      TimeBudget.within("4M characters of long numbers", 5000)(guarded(Catalogue.parse(input))): result =>
        assertTrue(result.isRight)
    },
    test("4M characters of short numbers are read quickly, on both platforms") {
      // Many short tokens, rather than a few long ones: what each number costs dominates.
      val small = catalogue(List.fill((Catalogue.MaxLength - 200) / 2)("0").mkString("[", ",", "]"))
      val mixed = catalogue(List.fill((Catalogue.MaxLength - 200) / 6)("-12.5").mkString("[", ",", "]"))
      assertTrue(small.length > 4000000, mixed.length > 4000000) &&
      TimeBudget.within("4M characters of short numbers", 5000)(List(small, mixed).map(c =>
        guarded(Catalogue.parse(c))
      )): read =>
        assertTrue(read.forall(_.isRight))
    },
    test("a valid catalogue parses as before") {
      val parsed = Catalogue.parse(catalogue("1e999999999"))
      assertTrue(
        parsed.map(_.language) == Right(Language("de")),
        parsed.map(_.messages.map((k, v) => k.value -> v)) == Right(Map("numbers.one" -> "Eins")),
        parsed.map(_.problems) == Right(Nil),
      )
    },
  )

end BoundedNumbersSpec
