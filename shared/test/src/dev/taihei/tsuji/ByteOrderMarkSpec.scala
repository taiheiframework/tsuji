package dev.taihei.tsuji

import zio.test.*

// The check both platforms' readers make once a catalogue file is decoded: a file whose text
// starts with U+FEFF, the decoded UTF-8 byte order mark, is refused in the same words on the JVM
// and on Scala.js. The readers themselves are tested in ClasspathCatalogueSpec (JVM) and
// CatalogueLoaderSpec (Scala.js).
object ByteOrderMarkSpec extends ZIOSpecDefault:

  private val File = """{"format":1,"language":"de","messages":{"order.cancelled":"Storniert"}}"""

  def spec = suite("a byte order mark")(
    test("at the start of a catalogue file is refused, by name") {
      assertTrue(
        Catalogue.refuseByteOrderMark("de.json", "﻿" + File) ==
        Left("de.json starts with a byte order mark; save it as UTF-8 without one")
      )
    },
    test("the same file without it passes and parses") {
      val checked = Catalogue.refuseByteOrderMark("de.json", File)
      assertTrue(
        checked == Right(File),
        checked.flatMap(Catalogue.parse(_).left.map(_.message)).map(_.messages.size) == Right(1),
      )
    },
    test("elsewhere in the file is not this refusal: the parser judges it as any other character") {
      val inTemplate    = File.replace("Storniert", "Stor﻿niert")
      val betweenTokens = File.replace("{\"format\"", "{﻿\"format\"")
      assertTrue(
        Catalogue.refuseByteOrderMark("de.json", inTemplate) == Right(inTemplate),
        Catalogue.parse(inTemplate).map(_.messages.values.toList) == Right(List("Stor﻿niert")),
        Catalogue.refuseByteOrderMark("de.json", betweenTokens) == Right(betweenTokens),
        Catalogue.parse(betweenTokens).left.exists(_.message.startsWith("not JSON")),
      )
    },
  )

end ByteOrderMarkSpec
