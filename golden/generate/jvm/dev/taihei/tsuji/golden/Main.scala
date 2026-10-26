package dev.taihei.tsuji.golden

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import dev.taihei.tsuji.tools.ExportCatalogues

// The golden files of the JVM, with the catalogue files 5.0.1's taihei-tsuji-tools exports for
// the corpus's families.
object Main:

  def main(args: Array[String]): Unit =
    val dir    = Files.createTempDirectory("golden-export")
    val output = new ByteArrayOutputStream()
    val stream = new PrintStream(output, true, StandardCharsets.UTF_8)
    val status = ExportCatalogues.run(
      List("--out", dir.toString, "--family", "dev.taihei.tsuji.golden.GoldenText", "--family", "dev.taihei.tsuji.golden.GoldenArgs"),
      stream,
      stream,
    )
    val files  = Files.list(dir).iterator.asScala.toList.sortBy(_.getFileName.toString)
    val exported = files.map(f => s"export|${f.getFileName}" -> Files.readString(f, StandardCharsets.UTF_8))
    files.foreach(Files.delete)
    Files.delete(dir)
    // The status only: what the export prints names the temporary directory.
    val extra  = ("export status|" -> status.toString) :: exported
    Generate.source("the JVM", Generate.entries(extra)).linesIterator.foreach(println)
