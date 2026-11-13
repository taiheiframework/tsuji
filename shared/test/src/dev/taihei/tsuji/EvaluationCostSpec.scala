package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import zio.test.*

// What one evaluation reads: a date or time style, an option's value.
object EvaluationCostSpec extends ZIOSpecDefault:

  private val day      = LocalDate.of(2026, 11, 10)
  private val dayTime  = LocalDateTime.of(2026, 11, 10, 14, 5)
  private val time     = LocalTime.of(14, 5)
  private val longText = "l" * 60000

  def spec = suite("What an evaluation reads")(
    test("the platform formatters read a date or time style of at most six letters") {
      val options            = Map("dateStyle" -> longText, "timeStyle" -> longText)
      def medium(value: Any) = PlatformDateTimeFormatter.formatDateTime(value, Map.empty, "en")
      TimeBudget.within("20000 dates, times and both with 60K styles", 1000)(
        (1 to 20000).map(_ =>
          List(day, dayTime, time).map(PlatformDateTimeFormatter.formatDateTime(_, options, "en"))
        ).last
      ): out =>
        assertTrue(out == List(medium(day), medium(dayTime), medium(time)))
    },
    test("an over-long option is no option: a 60K dateStyle formats as medium, cheaply") {
      val shapes = List(
        s".match {$$v :date dateStyle=$longText}\nwhen * {}" -> day,
        s"{$$v :date dateStyle=$longText}" -> day,
        s"{$$v :time timeStyle=$longText}" -> time,
      ).map((template, value) => MessageFormat2.parse(template).message -> value)
      TimeBudget.within("3 x 20000 evaluations of a 60K date or time style", 1000)(
        shapes.map: (message, value) =>
          (1 to 20000).map(_ => MessageFormat2.formatted(message, Map("v" -> value), "en", Budget.fresh())).last
      ): out =>
        assertTrue(
          out == List(
            Right(""),
            Right(MessageFormat2.format("{$v :date}", Map("v" -> day))),
            Right(MessageFormat2.format("{$v :time}", Map("v" -> time))),
          )
        )
    },
    // Looked up by every option, 20000 uses cost 4.4 s on Scala.js and 0.66 s on the JVM; looked
    // up by what the options come to, about 30 ms on both. The budget leaves room for a loaded
    // machine, so Scala.js is where this test catches the old key.
    test("two thousand options a function does not read cost nothing per use") {
      val many    = (1 to 2000).map(i => s"o$i=aaaaaaaaaaaaaa").mkString(" ")
      val message = MessageFormat2.parse(s".match {$$v :date $many}\nwhen * {}").message
      TimeBudget.within("20000 evaluations of a function with 2000 options", 1000)(
        (1 to 20000).map(_ => MessageFormat2.formatted(message, Map("v" -> day), "en", Budget.fresh())).last
      ): out =>
        assertTrue(out == Right(""))
    },
  )

end EvaluationCostSpec
