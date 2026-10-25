package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.DeepNesting.*

// The JVM tests run with a large stack (-Xss16M in build.mill), which would hide a reader that
// recurses per level. This runs the same inputs on a thread with a small stack; threads exist on
// the JVM only. On Scala.js DeepNestingSpec runs under Node at its default stack.
object SmallStackSpec extends ZIOSpecDefault:

  private val SmallStack = 256L * 1024

  def spec = suite("Deeply nested messages on a small stack")(
    test("depths of 5000 and 100000 are a decoding error on a 256 KB stack") {
      @volatile var missed: Option[List[String]] = None
      @volatile var failure: Option[Throwable]   = None
      val thread                                 = new Thread(
        null,
        () =>
          try missed = Some(Depths.flatMap(notRefused))
          catch case t: Throwable => failure = Some(t),
        "small-stack",
        SmallStack,
      )
      thread.start()
      thread.join(120000)
      assertTrue(!thread.isAlive, failure.isEmpty, missed.contains(Nil))
    }
  )

end SmallStackSpec
