package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import scala.scalajs.js

import zio.test.*

// Scala.js only: the JVM formats a local date or time without a zone, and has no Date range.
// Node reads process.env.TZ again when it is assigned, so the spec can run in a zone with a
// daylight-saving gap whatever zone the machine is in, and puts the old value back after.
object RuntimeZoneSpec extends ZIOSpecDefault:

  private def inZone[A](zone: String)(body: => A): A =
    val env      = js.Dynamic.global.process.env
    val previous = env.TZ
    env.TZ = zone
    try body
    finally
      if js.isUndefined(previous) then env.updateDynamic("TZ")(js.undefined)
      else env.TZ = previous

  private def show(value: Any, options: Map[String, String] = Map.empty) =
    PlatformDateTimeFormatter.formatDateTime(value, options, "de")

  def spec = suite("Dates and times on Scala.js")(
    test("a time in Brussels' daylight-saving gap is written as given") {
      val gap               = LocalDateTime.of(2026, 3, 29, 2, 30)
      val (zoneHour, shown) = inZone("Europe/Brussels") {
        // The zone took: in it, the Date constructor moves 02:30 to 03:30.
        (new js.Date(2026, 2, 29, 2, 30).getHours().toInt, (show(gap), show(gap.toLocalTime)))
      }
      assertTrue(
        zoneHour == 3,
        shown == ("29.03.2026, 02:30:00", "02:30:00"),
      )
    },
    test("a year past the JavaScript Date range is written as its ISO text") {
      val far = LocalDate.of(300000, 1, 1)
      assertTrue(
        show(far) == "+300000-01-01",
        show(LocalDateTime.of(far, LocalTime.of(2, 30))) == "+300000-01-01T02:30",
        show(LocalDate.of(-300000, 1, 1)) == "-300000-01-01",
        MessageFormat2.format("on {$d :date}", Map("d" -> far), "de") == "on +300000-01-01",
      )
    },
    test("the last day the Date range holds is still formatted") {
      assertTrue(show(LocalDate.of(275760, 9, 13)) == "13.09.275760")
    },
  )

end RuntimeZoneSpec
