package dev.taihei.tsuji

import scala.annotation.tailrec

import zio.test.*

import zio.ZIO

// A timing test on a loaded machine can miss its budget for reasons that have nothing to do with
// the code under test. TimeBudget retries a run that was correct but slow, at most MaxAttempts
// times, and passes on the fastest run. The budget itself never moves. A run with a wrong result
// fails at once and is never retried, so a retry can only absorb slowness, never hide a fault.
// Every timing is logged, so a run that needed a retry stays visible in the test output.
object TimeBudget:

  val MaxAttempts = 3

  def within[A](
    label: String,
    budgetMillis: Long,
    nanos: () => Long = () => java.lang.System.nanoTime(),
    log: String => Unit = line => println(line),
  )(
    work: => A
  )(
    correct: A => TestResult
  ): TestResult =
    @tailrec def attempt(n: Int, timings: Vector[Long]): TestResult =
      val start  = nanos()
      val result = work
      val all    = timings :+ (nanos() - start) / 1000000
      log(s"$label: attempt $n of $MaxAttempts took ${all.last} ms, budget $budgetMillis ms")
      judge(n, all, budgetMillis, correct(result)) match
        case Left(verdict) => verdict
        case Right(())     => attempt(n + 1, all)
    attempt(1, Vector.empty)

  end within

  /** The same for work that ends later, such as a `Future` on Scala.js, which cannot be awaited. */
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
  // result is the verdict at once; a correct one passes on the fastest run.
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
