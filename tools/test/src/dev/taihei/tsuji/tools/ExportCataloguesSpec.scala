package dev.taihei.tsuji.tools

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.Language.*

object Shop extends MessageFamily("shop"):
  val open  = message("Open", Dutch -> "Open", Language("de") -> "Geöffnet")
  val items = message[(n: Int)]("{$n} items", Dutch -> "{$n} artikels")

object Broken extends MessageFamily("broken"):
  private val dutch = "{$nope}"
  val a             = message("A", Dutch -> dutch)

object ExportCataloguesSpec extends ZIOSpecDefault:

  private def run(args: String*): (Int, String, String) =
    val out    = new ByteArrayOutputStream()
    val err    = new ByteArrayOutputStream()
    val status =
      ExportCatalogues.run(args.toList, new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"))
    (status, out.toString("UTF-8"), err.toString("UTF-8"))

  private def messages(dir: Path, tag: String): Map[String, String] =
    Catalogue
      .parse(Files.readString(dir.resolve(s"$tag.json"), StandardCharsets.UTF_8))
      .map(_.messages.map((k, v) => k.value -> v))
      .getOrElse(Map.empty)

  def spec = suite("ExportCatalogues")(
    test("merged per-language files of the named families and those of a library") {
      val dir                = Files.createTempDirectory("taihei-export")
      val (status, out, err) =
        run("--out", dir.toString, "--family", "dev.taihei.tsuji.tools.Shop", "--scan", "dev.taihei.tsuji")
      val en                 = messages(dir, "en")
      val nl                 = messages(dir, "nl")
      val de                 = messages(dir, "de")
      assertTrue(
        en.get("shop.items").contains("{$n} items"),
        en.get("taihei.validation.stringMinLength").contains("length should be at least {$min}"),
        nl.get("shop.items").contains("{$n} artikels"),
        nl.get("taihei.validation.stringMinLength").contains("lengte moet minimaal {$min} zijn"),
        de == Map("shop.open" -> "Geöffnet"),
        Catalogue.parse(Files.readString(dir.resolve("fr.json"))).isRight,
        out.contains("messages in nl"),
        // Broken is found by the scan of dev.taihei.tsuji: its bad translation is a problem, and left out.
        status == 1,
        err.contains("broken.a"),
        !nl.contains("broken.a"),
      )
    },
    test("a family named twice and nested objects are found") {
      val dir            = Files.createTempDirectory("taihei-export")
      val (status, _, _) = run(
        "--out",
        dir.toString,
        "--family",
        "dev.taihei.tsuji.CoreMessages.Validation",
        "--family",
        "dev.taihei.tsuji.tools.Shop",
        "--family",
        "dev.taihei.tsuji.tools.Shop",
      )
      assertTrue(status == 0, messages(dir, "fr").contains("taihei.validation.nonEmpty"))
    },
    test("an output directory that cannot be written is a problem, not a crash") {
      val file           = Files.createTempFile("taihei-export", ".txt")
      val (status, _, e) = run("--out", file.toString, "--family", "dev.taihei.tsuji.tools.Shop")
      assertTrue(status == 1, e.contains("cannot write"))
    },
    test("usage errors") {
      assertTrue(
        run()._1 == 2,
        run("--out", "x")._1 == 2,
        run("--bogus")._1 == 2,
        run("--out", "x", "--scan", "")._1 == 2,
        run("--out", "x", "--scan", "../etc")._1 == 2,
        run("--out", Files.createTempDirectory("x").toString, "--family", "no.such.Family")._1 == 2,
      )
    },
  )

end ExportCataloguesSpec
