package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal("Intl.DateTimeFormat")
private[tsuji] class DateTimeFormat(@unused locale: String, @unused options: js.Dictionary[Any])
  extends js.Object:
  def format(date: js.Date): String = js.native

private[tsuji] object PlatformDateTimeFormatter:

  def formatDateTime(
    value: Any,
    options: Map[String, String],
    locale: String,
  ): String =
    val jsLocale = PlatformLocales.jsLocale(locale)

    // The style options each value shows, and the instant that stands for it. A value past the
    // range of a JavaScript Date has no instant: it shows as its ISO text, as Intl would throw.
    val (styles, instant) = value match
      case ld: LocalDate      =>
        (
          Seq("dateStyle"),
          toJsDate(ld.getYear, ld.getMonthValue, ld.getDayOfMonth, LocalTime.MIDNIGHT),
        )
      case ldt: LocalDateTime =>
        (
          Seq("dateStyle", "timeStyle"),
          toJsDate(ldt.getYear, ldt.getMonthValue, ldt.getDayOfMonth, ldt.toLocalTime),
        )
      case lt: LocalTime      =>
        (Seq("timeStyle"), toJsDate(ReferenceYear, 1, 1, lt))
      case _                  => (Seq.empty, None)

    instant.fold(value.toString): date =>
      val jsOptions = js.Dictionary[Any](
        (("timeZone" -> "UTC") +: styles.map(key => key -> styleOf(options, key)))*
      )
      new DateTimeFormat(jsLocale, jsOptions).format(date)

  end formatDateTime

  // Intl throws a RangeError on a style it does not know. Like the JVM side, take the style in
  // any case and fall back to medium for anything else.
  private val styles = Set("short", "medium", "long", "full")

  private def styleOf(options: Map[String, String], key: String): String =
    options.get(key).map(_.toLowerCase).filter(styles).getOrElse("medium")

  // A LocalTime has no date; Intl needs one, so it gets a fixed one rather than today's.
  private val ReferenceYear = 1970

  // The fields go in as UTC and Intl formats them in UTC, so the runtime's zone never moves them:
  // in the local zone a time in a daylight-saving gap would shift by an hour. setUTCFullYear sets
  // year, month and day together, because the Date constructor and Date.UTC read years 0 to 99
  // as 1900 to 1999. A year outside the Date range leaves the time value NaN.
  private def toJsDate(
    year: Int,
    month: Int,
    day: Int,
    time: LocalTime,
  ): Option[js.Date] =
    val date = new js.Date(
      js.Date.UTC(1970, 0, 1, time.getHour, time.getMinute, time.getSecond)
    )
    date.setUTCFullYear(year, month - 1, day)
    Option.unless(date.getTime().isNaN)(date)

end PlatformDateTimeFormatter
