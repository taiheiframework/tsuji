package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}
import java.time.format.{DateTimeFormatter, FormatStyle}

private[tsuji] object PlatformDateTimeFormatter:

  def formatDateTime(
    value: Any,
    options: Map[String, String],
    locale: String,
  ): String =
    val javaLocale = PlatformLocales.javaLocale(locale)

    value match
      case ld: LocalDate =>
        val style = options.get("dateStyle").flatMap(parseFormatStyle).getOrElse(FormatStyle.MEDIUM)
        val formatter = DateTimeFormatter.ofLocalizedDate(style).withLocale(javaLocale)
        formatter.format(ld)

      case ldt: LocalDateTime =>
        val dateStyle =
          options.get("dateStyle").flatMap(parseFormatStyle).getOrElse(FormatStyle.MEDIUM)
        val timeStyle = zoneless(
          options.get("timeStyle").flatMap(parseFormatStyle).getOrElse(FormatStyle.MEDIUM)
        )
        val formatter =
          DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle).withLocale(javaLocale)
        formatter.format(ldt)

      case lt: LocalTime =>
        val style     = zoneless(
          options.get("timeStyle").flatMap(parseFormatStyle).getOrElse(FormatStyle.MEDIUM)
        )
        val formatter = DateTimeFormatter.ofLocalizedTime(style).withLocale(javaLocale)
        formatter.format(lt)

      case _ =>
        // Fallback for non-date/time values
        MessageFormat2.asItIs(value)
    end match

  end formatDateTime

  // The long and full time styles print a time zone, which a LocalTime or LocalDateTime does not
  // have: the JDK throws DateTimeException on them. Medium is the most such a value can show.
  private def zoneless(style: FormatStyle): FormatStyle = style match
    case FormatStyle.LONG | FormatStyle.FULL => FormatStyle.MEDIUM
    case other                               => other

  // Locale.ROOT: under a Turkish default locale "MEDIUM" would lower-case to "medıum". A value
  // longer than "medium" is none of the four and is not lower-cased at all.
  private def parseFormatStyle(style: String): Option[FormatStyle] =
    Option(style).filter(_.length <= 6).map(_.toLowerCase(java.util.Locale.ROOT)).collect:
      case "short"  => FormatStyle.SHORT
      case "medium" => FormatStyle.MEDIUM
      case "long"   => FormatStyle.LONG
      case "full"   => FormatStyle.FULL

end PlatformDateTimeFormatter
