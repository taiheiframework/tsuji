package dev.taihei.i18n

import scala.annotation.tailrec

import zio.test.*

import zio.ZIO

// A time budget bounds the work that some input causes: the input is sized so that work beyond
// linear, or done again for every use, takes far longer than the budget. Elapsed time measures the
// machine as much as the work, though: on a busy machine every core is shared and slower, and a
// test took three times as long as on an idle one. So each attempt also times a fixed piece of
// plain Scala, the reference, just before and just after the work, and the budget grows by as much
// as the reference took longer than it does on an idle machine (`Speed.idleMillis`). The budget
// never shrinks: on an idle or fast machine it is the number the test names. The reference uses
// none of the library's code, so a slower library cannot make its own budget larger.
//
// A run that was correct but over its budget is retried, at most MaxAttempts times, and passes on
// the fastest run. A run with a wrong result fails at once and is never retried, so a retry can
// only absorb slowness, never hide a fault. Every timing is logged, with the factor the budget was
// scaled by, so a run that needed a retry or a larger budget stays visible in the test output.
object TimeBudget:

  val MaxAttempts = 3

  /**
   * How slow the machine is now, against a quiet one: the reference's time over `idleMillis`, at
   * least 1 and at most `maxScale`.
   */
  final case class Speed(
    millis: () => Double,
    idleMillis: Double,
    maxScale: Double):

    def scale(): Double =
      val samples = Vector.fill(Speed.Samples)(millis()).sorted
      math.min(maxScale, math.max(1.0, samples(Speed.Samples / 2) / idleMillis))

  object Speed:

    val Samples = 3

    private val js = java.lang.System.getProperty("java.vm.name") == "Scala.js"

    // What the reference takes on a quiet machine of the family (a 2019 32-core desktop).
    private val IdleMillisJs  = 20.0
    private val IdleMillisJvm = 14.0

    // The most a budget grows, so that a reference that ran slow for a reason of its own cannot
    // make a budget meaningless, and work that grows with the square of its input still misses
    // it. Scala.js runs one spec at a time, and in full test runs with every core busy its work
    // took up to three times as long as on a quiet machine. The JVM runs its specs side by side
    // and collects their garbage together: one spec took from 0.7 to 6.5 seconds.
    val MaxScaleJs  = 5.0
    val MaxScaleJvm = 20.0

    val machine: Speed = Speed(
      () =>
        val start = java.lang.System.nanoTime()
        reference()
        (java.lang.System.nanoTime() - start) / 1e6
      ,
      if js then IdleMillisJs else IdleMillisJvm,
      if js then MaxScaleJs else MaxScaleJvm,
    )

    // 256 KB of numbers, read in an order each step decides: arithmetic and memory that a core
    // and its own caches serve, as they serve most of the work. It allocates nothing, so it never
    // pays for collecting what the work left behind. A table larger than those caches made the
    // reference slow down several times more than the work.
    private val table = Array.tabulate(1 << 16)(i => i * 0x9e3779b1)

    private def reference(): Int =
      var x = 1
      var i = 0
      while i < 4000000 do
        x = x * 1103515245 + table((x >>> 16) & 0xffff)
        i += 1
      x

  end Speed

  def within[A](
    label: String,
    budgetMillis: Long,
    nanos: () => Long = () => java.lang.System.nanoTime(),
    log: String => Unit = line => println(line),
    speed: Speed = Speed.machine,
  )(
    work: => A
  )(
    correct: A => TestResult
  ): TestResult =
    @tailrec def attempt(n: Int, timings: Vector[Long]): TestResult =
      val before  = speed.scale()
      val start   = nanos()
      val result  = work
      val elapsed = (nanos() - start) / 1000000
      val scale   = math.max(before, speed.scale())
      val all     = timings :+ math.round(elapsed / scale)
      val tenths  = math.round(scale * 10)
      // Written by hand: a format string follows the default locale, and Scala.js writes 3.0 as 3.
      log(s"$label: attempt $n of $MaxAttempts took $elapsed ms, budget $budgetMillis ms times ${tenths / 10}.${tenths %
        10}")
      judge(n, all, budgetMillis, correct(result)) match
        case Left(verdict) => verdict
        case Right(())     => attempt(n + 1, all)
    attempt(1, Vector.empty)

  end within

  /**
   * The same for work that ends later, such as a `Future` on Scala.js, which cannot be awaited.
   * Such work waits for a timer or a server rather than computes, so its budget is not scaled.
   */
  def withinZIO[R, E, A](
    label: String,
    budgetMillis: Long,
    nanos: () => Long = () => java.lang.System.nanoTime(),
    log: String => Unit = line => println(line),
  )(
    work: ZIO[R, E, A]
  )(
    correct: A => TestResult
  ): ZIO[R, E, TestResult] =
    def attempt(n: Int, timings: Vector[Long]): ZIO[R, E, TestResult] =
      for
        start   <- ZIO.succeed(nanos())
        result  <- work
        all     <- ZIO.succeed(timings :+ (nanos() - start) / 1000000)
        _       <- ZIO.succeed(log(s"$label: attempt $n of $MaxAttempts took ${all.last} ms, budget $budgetMillis ms"))
        verdict <- judge(n, all, budgetMillis, correct(result)).fold(ZIO.succeed(_), _ => attempt(n + 1, all))
      yield verdict
    attempt(1, Vector.empty)

  end withinZIO

  // Left is the verdict; Right a correct run over the budget that has an attempt left. A wrong
  // result is the verdict at once; a correct one passes on the fastest run. A timing is the
  // elapsed time divided by how slow the machine was, so it compares with the budget as named.
  private def judge(
    n: Int,
    timings: Vector[Long],
    budgetMillis: Long,
    checked: TestResult,
  ): Either[TestResult, Unit] =
    Either.cond(
      checked.isSuccess && timings.last >= budgetMillis && n < MaxAttempts,
      (),
      if checked.isFailure then checked else checked && assertTrue(timings.min < budgetMillis),
    )

end TimeBudget
