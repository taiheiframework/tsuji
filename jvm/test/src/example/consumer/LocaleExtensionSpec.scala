package example.consumer

import java.util.Locale

import zio.test.*

import dev.taihei.tsuji.Language

// A caller outside the library's package that imports only Language, as code written against 5.x
// did: `language.locale` has to resolve with no further import, from the companion's implicit
// scope. The two other import styles have files of their own, so no import here helps them.
object LocaleExtensionSpec extends ZIOSpecDefault:

  private val tags = Vector("en", "nl-BE", "zh-Hant-TW")

  def spec = suite("Language.locale on the JVM, outside the package")(
    test("is the JDK locale of the tag") {
      assertTrue(
        Language("nl-BE").locale == Locale.forLanguageTag("nl-BE"),
        Language("zh-Hant-TW").locale.getScript == "Hant",
        Language.English.locale == Locale.ENGLISH,
      )
    },
    test("fromLocale gives the language back") {
      val languages = tags.map(Language(_))
      assertTrue(
        languages.size == 3,
        languages.forall(l => Language.fromLocale(l.locale) == Right(l)),
      )
    },
    test("Java sees the static Language.locale(language) and no instance method") {
      val static = classOf[Language].getMethod("locale", classOf[Language])
      val names  = classOf[Language].getMethods.toVector.filter(_.getName == "locale")
      assertTrue(
        java.lang.reflect.Modifier.isStatic(static.getModifiers),
        static.invoke(null, Language("nl-BE")) == Locale.forLanguageTag("nl-BE"),
        names.size == 1,
      )
    },
  )

end LocaleExtensionSpec
