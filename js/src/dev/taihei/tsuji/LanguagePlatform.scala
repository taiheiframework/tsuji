package dev.taihei.tsuji

/**
 * What the companion `Language` has on Scala.js only: nothing. On the JVM this trait holds
 * `fromLocale` and the extension `language.locale`; JavaScript has no locale type Tsuji takes or
 * gives, so an `Intl.Locale` is read by its tag, `Language.parse(intlLocale.toString)`, and made
 * from one, `new Intl.Locale(language.tag)`.
 */
private[tsuji] trait LanguagePlatform
