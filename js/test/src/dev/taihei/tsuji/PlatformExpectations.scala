package dev.taihei.tsuji

// What the shared specs expect of the platform they run on: the Scala.js side.
object PlatformExpectations:

  /** The medium Dutch date of 20 October 2026, as `Intl` writes it. */
  val mediumDutchDates: Set[String] = Set("20 okt 2026")

  /** 14:30 in the long and the full time style: `Intl` names the zone the value is read in. */
  val longTime: String = "2:30:00 PM UTC"
  val fullTime: String = "2:30:00 PM Coordinated Universal Time"

  /** `Intl` has names of its own for some languages with a region. */
  val dutchOfBelgium: String   = "Flemish"
  val englishOfBritain: String = "British English"

  /** 1234.5 in a currency no platform knows, in English: the code where the symbol would be. */
  val unknownCurrency: String = "ZZZ\u00a01,234.50"

end PlatformExpectations
