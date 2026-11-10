package dev.taihei.tsuji

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal("Intl.NumberFormat")
private[tsuji] class NumberFormat(@unused locale: String, @unused options: js.Dictionary[Any])
  extends js.Object:
  // A number, or the decimal digits of one as text, which Intl formats without rounding them to
  // a Double first.
  def format(value: js.Any): String = js.native

private[tsuji] object PlatformNumberFormatter:

  /**
   * The number with the plain decimal digits `digits`, written as the language of `locale`
   * writes numbers. Without `minimum` and `maximum` a number has up to three decimals, a
   * percentage none and an amount as many as its currency has; `Intl` rounds a half away from
   * zero. A runtime from before `Intl` took digits as text reads them as a `Double` first, and
   * may lose the last digits of a very long number.
   */
  def format(
    digits: String,
    style: NumberStyle,
    minimum: Option[Int],
    maximum: Option[Int],
    grouped: Boolean,
    locale: String,
  ): String =
    // "always", which true stands for in every runtime: the default "auto" leaves 1234 without a
    // separator in the languages that group from five digits on (Spanish, Polish), which the JDK
    // does not do.
    val options = js.Dictionary[Any]("useGrouping" -> grouped)
    style match
      case NumberStyle.Plain          => ()
      case NumberStyle.Percent        => options("style") = "percent"
      case NumberStyle.Currency(code) =>
        options("style") = "currency"
        options("currency") = code
    minimum.foreach(options("minimumFractionDigits") = _)
    maximum.foreach(options("maximumFractionDigits") = _)
    formatter(s"$locale|$style|$minimum|$maximum|$grouped", locale, options).format(digits)

  end format

  // Building an Intl.NumberFormat costs some fifty times what formatting with one does, and a
  // message with a list of numbers, or with nested messages that each show one, formats
  // thousands of them the same way. The formatters of the latest few ways are kept; a reader's
  // language can come from a request, so past Kept they are all let go at once. JavaScript runs
  // one thing at a time, so a plain map is enough.
  private val Kept       = 64
  private val formatters = js.Dictionary.empty[NumberFormat]

  private def formatter(
    key: String,
    locale: String,
    options: js.Dictionary[Any],
  ): NumberFormat =
    formatters.getOrElse(
      key, {
        if formatters.size >= Kept then formatters.clear()
        val made = new NumberFormat(PlatformLocales.jsLocale(locale), options)
        formatters(key) = made
        made
      },
    )

end PlatformNumberFormatter
