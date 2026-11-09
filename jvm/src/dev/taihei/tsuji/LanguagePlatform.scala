package dev.taihei.tsuji

import java.util.Locale

/**
 * What the companion `Language` has on the JVM only: the factory from a JDK locale and the way
 * back. A companion cannot be split across source sets, so `object Language` extends this trait,
 * which Scala.js defines empty.
 */
private[tsuji] trait LanguagePlatform:

  /**
   * The language of a JDK locale: `Language.parse(locale.toLanguageTag)`, except that a locale
   * without a language (`Locale.ROOT`, any tag `und` or `und-…`) is a `Left`. Extensions stay,
   * as in `parse`: `th_TH_TH` gives
   * `th-TH-u-nu-thai-x-lvariant-th`. The JDK maps its legacy forms first
   * (`iw` gives `he`).
   *
   * {{{
   * Language.fromLocale(Locale.forLanguageTag("nl-BE")) // Right(nl-BE)
   * Language.fromLocale(Locale.ROOT)                    // Left(...)
   * }}}
   *
   * The other way round is `language.locale`.
   */
  def fromLocale(locale: Locale): Either[String, Language] =
    Language.fromPlatformTag(locale.toLanguageTag)

  // An extension in the companion rather than a member of the class, so that the shared class
  // holds no JDK type, and rather than a top-level extension in the package, which every caller
  // outside the package would have to import: the companion is in the implicit scope of
  // `Language`, so `language.locale` resolves with no import at all.
  extension (language: Language)

    /**
     * The JDK locale of this language's tag, `Locale.forLanguageTag(language.tag)`: the way back
     * from `fromLocale`, which gives the language again for any tag the JDK keeps as it is
     * (`und`, which names no language, is refused). JVM only; on Scala.js read `language.tag`.
     * Java calls it as `Language.locale(language)`.
     *
     * Each call makes a JDK Locale, and the JDK keeps the Locale objects it makes. Call it on the
     * languages an application supports, not on every language a reader or a message may carry;
     * this library's own formatting and naming never call it.
     *
     * {{{
     * Language("nl-BE").locale                      // nl_BE
     * Language.fromLocale(Language("nl-BE").locale) // Right(nl-BE)
     * }}}
     */
    def locale: Locale = Locale.forLanguageTag(language.tag)

  end extension

end LanguagePlatform
