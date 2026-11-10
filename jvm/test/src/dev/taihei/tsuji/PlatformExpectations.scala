package dev.taihei.tsuji

// What the shared specs expect of the platform they run on: the JVM side.
object PlatformExpectations:

  /**
   * The medium Dutch date of 20 October 2026. It comes from the JDK's CLDR data: Java 17 writes
   * the abbreviated month as "okt.", Java 21 and later as "okt"; the releases between are not
   * tested and may write either.
   */
  val mediumDutchDates: Set[String] = Runtime.version.feature match
    case 17          => Set("20 okt. 2026")
    case v if v < 21 => Set("20 okt. 2026", "20 okt 2026")
    case _           => Set("20 okt 2026")

  /** 14:30 in the long and the full time style: no zone to print, so the medium style. */
  val longTime: String = "2:30:00 PM"
  val fullTime: String = "2:30:00 PM"

  /** The JDK names the language and the region. */
  val dutchOfBelgium: String   = "Dutch (Belgium)"
  val englishOfBritain: String = "English (United Kingdom)"

  /** 1234.5 in a currency no platform knows, in English: the code where the symbol would be. */
  val unknownCurrency: String = "ZZZ1,234.50"

end PlatformExpectations
