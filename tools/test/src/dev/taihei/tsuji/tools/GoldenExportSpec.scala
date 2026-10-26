package dev.taihei.tsuji.tools

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import zio.test.*

import dev.taihei.tsuji.golden.Golden501

// The catalogue files of the corpus's families, byte for byte as 5.0.1's taihei-tsuji-tools
// exported them (ADR 0006, section 2; golden/generate).
object GoldenExportSpec extends ZIOSpecDefault:

  def spec = suite("ExportCatalogues against 5.0.1")(
    test("writes the files 5.0.1 wrote, byte for byte") {
      val dir      = Files.createTempDirectory("golden-export")
      val sink     = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
      val status   = ExportCatalogues.run(
        List(
          "--out",
          dir.toString,
          "--family",
          "dev.taihei.tsuji.golden.GoldenText",
          "--family",
          "dev.taihei.tsuji.golden.GoldenArgs",
        ),
        sink,
        sink,
      )
      val files    = Files.list(dir).iterator.asScala.toList.sortBy(_.getFileName.toString)
      val written  = files.map(f => s"export|${f.getFileName}" -> Files.readAllBytes(f).toSeq).toMap
      val expected = Golden501.entries.collect:
        case (k, v) if k.startsWith("export|") => k -> v.getBytes(StandardCharsets.UTF_8).toSeq
      files.foreach(Files.delete)
      Files.delete(dir)
      assertTrue(
        Golden501.platform == "the JVM",
        expected.size == 2,
        status.toString == Golden501.entries("export status|"),
        written == expected,
      )
    }
  )

end GoldenExportSpec
