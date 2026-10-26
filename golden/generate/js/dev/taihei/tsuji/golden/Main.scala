package dev.taihei.tsuji.golden

import scala.scalajs.js

// The golden files of Scala.js: no catalogue export, which taihei-tsuji-tools does on the JVM only.
object Main:

  // Written synchronously: Node writes to a pipe asynchronously, and cut println's output short
  // when the process ended.
  def main(args: Array[String]): Unit =
    val fs = js.Dynamic.global.require("fs")
    Generate.source("Scala.js", Generate.entries(Nil)).linesIterator.foreach(line => fs.writeSync(1, line + "\n"))
