package dev.taihei.tsuji.tools.exploding

import dev.taihei.tsuji.*

// A family object whose initialiser throws, for the scan that must report it. In a package of its
// own, so that a scan can name it alone.
object Exploding extends MessageFamily("exploding"):
  val a = message("A")
  require(a eq null, "boom")
