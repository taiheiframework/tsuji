package dev.taihei.i18n

import zio.test.*

// The JVM tests may run with a large stack, which would hide formatting that recurses per
// declaration. This formats on a thread with a small stack; threads exist on the JVM only.
object SmallStackSpec extends ZIOSpecDefault:

  private val SmallStack = 256L * 1024

  def spec = suite("Formatting on a small stack")(
    test("a chain of a thousand plural values formats on a 256 KB stack") {
      val template                             =
        ".let {$p0 = $n :plural}\n" + (1 to 1000).map(i => s".let {$$p$i = $$p${i - 1} :plural}")
          .mkString("\n") + "\n{$p1000 :number} {$p1000}"
      @volatile var out: Option[String]        = None
      @volatile var failure: Option[Throwable] = None
      val thread                               = new Thread(
        null,
        () =>
          try out = Some(MessageFormat2.format(template, Map("n" -> 7)))
          catch case t: Throwable => failure = Some(t),
        "small-stack",
        SmallStack,
      )
      thread.start()
      thread.join(120000)
      assertTrue(!thread.isAlive, failure.isEmpty, out.contains("7 7"))
    }
  )

end SmallStackSpec
