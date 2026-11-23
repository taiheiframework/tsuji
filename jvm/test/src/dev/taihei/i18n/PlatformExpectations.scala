package dev.taihei.i18n

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
