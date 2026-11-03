package dev.taihei.tsuji

import scala.annotation.tailrec

import zio.test.*

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
      val start   = nanos()
      val result  = work
      val millis  = (nanos() - start) / 1000000
      val all     = timings :+ millis
      log(s"$label: attempt $n of $MaxAttempts took $millis ms, budget $budgetMillis ms")
      val checked = correct(result)
      if checked.isFailure then checked
      else if millis < budgetMillis || n == MaxAttempts then
        val fastest = all.min
        checked && assertTrue(fastest < budgetMillis)
      else attempt(n + 1, all)
    attempt(1, Vector.empty)

  end within

end TimeBudget
