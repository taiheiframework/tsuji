package dev.taihei.tsuji

import scala.compiletime.testing.typeCheckErrors

import zio.test.*

// Since 6.0.0 the shared Language holds no JDK type: `locale` is a JVM-only extension, so on
// Scala.js the call does not compile. The tag compiles in the same place, so the snippet fails
// for that reason and not another.
object LocaleGoneSpec extends ZIOSpecDefault:

  def spec = suite("Language.locale on Scala.js")(
    test("does not compile, while the tag does") {
      val locale = typeCheckErrors("""Language("nl-BE").locale""")
      val tag    = typeCheckErrors("""Language("nl-BE").tag""")
      assertTrue(
        locale.nonEmpty,
        locale.exists(_.message.contains("locale")),
        tag.isEmpty,
      )
    }
  )
