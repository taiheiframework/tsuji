package example.consumer

import java.util.Locale

import zio.test.*

import dev.taihei.tsuji.Language
import dev.taihei.tsuji.Language.*

// The same call under `import Language.*`, the usual import for English, Dutch and French. It
// also brings `locale` into scope as a plain name; the extension call still resolves.
object LocaleCompanionImportSpec extends ZIOSpecDefault:

  def spec = suite("Language.locale under import Language.*")(
    test("resolves and is the JDK locale of the tag") {
      assertTrue(
        Dutch.locale == Locale.forLanguageTag("nl"),
        Language("nl-BE").locale == Locale.forLanguageTag("nl-BE"),
        fromLocale(Language("zh-Hant-TW").locale) == Right(Language("zh-Hant-TW")),
      )
    }
  )
