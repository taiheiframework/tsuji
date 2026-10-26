package example.consumer

import java.util.Locale

import zio.test.*

import dev.taihei.tsuji.*

// The same call under the package's wildcard import.
object LocaleWildcardImportSpec extends ZIOSpecDefault:

  def spec = suite("Language.locale under import dev.taihei.tsuji.*")(
    test("resolves and is the JDK locale of the tag") {
      val flemish: Language = Language("nl-BE")
      assertTrue(
        flemish.locale == Locale.forLanguageTag("nl-BE"),
        Language.fromLocale(flemish.locale) == Right(flemish),
      )
    }
  )
