package dev.taihei.tsuji

import java.math.RoundingMode
import java.text.{DecimalFormat, NumberFormat}
import java.util.Currency

import scala.util.Try

private[tsuji] object PlatformNumberFormatter:

  /**
   * The number with the plain decimal digits `digits`, written as the language of `locale`
   * writes numbers. Without `minimum` and `maximum` a number has up to three decimals, a
   * percentage none and an amount as many as its currency has. A half is rounded away from zero,
   * as `Intl` rounds on Scala.js: the JDK's own default, half to even, would write 2.5 as 2.
   */
  def format(
    digits: String,
    style: NumberStyle,
    minimum: Option[Int],
    maximum: Option[Int],
    grouped: Boolean,
    locale: String,
  ): String =
    val javaLocale               = PlatformLocales.javaLocale(locale)
    val (formatter, least, most) = style match
      case NumberStyle.Plain          => (NumberFormat.getInstance(javaLocale), 0, 3)
      case NumberStyle.Percent        => (NumberFormat.getPercentInstance(javaLocale), 0, 0)
      case NumberStyle.Currency(code) =>
        val amounts  = NumberFormat.getCurrencyInstance(javaLocale)
        val decimals = currencyDecimals(amounts, code)
        (amounts, decimals, decimals)
    // What Intl does with one of the two given: fewer decimals at most than the style has at
    // least lowers the least, more at least than it has at most raises the most.
    val atMost                   = maximum.getOrElse(math.max(most, minimum.getOrElse(0)))
    val atLeast                  = minimum.getOrElse(math.min(least, atMost))
    formatter.setMinimumFractionDigits(atLeast)
    formatter.setMaximumFractionDigits(math.max(atMost, atLeast))
    formatter.setGroupingUsed(grouped)
    formatter.setRoundingMode(RoundingMode.HALF_UP)
    formatter.format(new java.math.BigDecimal(digits))

  end format

  // Sets the currency and gives its number of decimals. A code the JDK does not know is written
  // in the currency's place with two decimals, as Intl writes it.
  private def currencyDecimals(amounts: NumberFormat, code: String): Int =
    Try(Currency.getInstance(code)).toOption match
      case Some(currency) =>
        amounts.setCurrency(currency)
        // -1 for a pseudo-currency such as XXX, which has no decimals of its own.
        Some(currency.getDefaultFractionDigits).filter(_ >= 0).getOrElse(2)
      case None           =>
        amounts match
          case decimal: DecimalFormat =>
            val symbols = decimal.getDecimalFormatSymbols
            symbols.setCurrencySymbol(code)
            symbols.setInternationalCurrencySymbol(code)
            decimal.setDecimalFormatSymbols(symbols)
          case _                      => ()
        2

end PlatformNumberFormatter
