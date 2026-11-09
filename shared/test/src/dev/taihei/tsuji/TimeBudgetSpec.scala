package dev.taihei.tsuji

import scala.collection.mutable.ListBuffer

import zio.test.*

import zio.ZIO

// TimeBudget retries a slow but correct run and nothing else. A fake clock makes each attempt
// take exactly the time the test says.
object TimeBudgetSpec extends ZIOSpecDefault:

  final private class Run(durationsMillis: Long*):
    private val ticks = durationsMillis.iterator.flatMap(ms => Iterator(0L, ms * 1000000))
    val lines         = ListBuffer.empty[String]
    var runs          = 0
    val nanos         = () => ticks.next()

    def log(line: String): Unit =
      lines.append(line)

    def work(): Int =
      runs += 1
      runs

  private def run(run: Run, correct: Int => Boolean = _ => true): TestResult =
    TimeBudget.within("work", 100, run.nanos, run.log)(run.work())(n => assertTrue(correct(n)))

  def spec = suite("TimeBudget")(
    test("a run within the budget passes at once") {
      val r = Run(40)
      assertTrue(run(r).isSuccess, r.runs == 1, r.lines.toList == List("work: attempt 1 of 3 took 40 ms, budget 100 ms"))
    },
    test("a correct run over the budget is retried, and passes on a later run within it") {
      val r = Run(150, 120, 60)
      assertTrue(run(r).isSuccess, r.runs == 3, r.lines.size == 3)
    },
    test("three correct runs over the budget fail, with every timing logged") {
      val r = Run(150, 120, 100)
      assertTrue(
        run(r).isFailure,
        r.runs == 3,
        r.lines.toList.map(_.split(" took ")(1)) ==
        List("150 ms, budget 100 ms", "120 ms, budget 100 ms", "100 ms, budget 100 ms"),
      )
    },
    test("a wrong result fails at once and is never retried, slow or not") {
      val fast = Run(40, 40, 40)
      val slow = Run(150, 40, 40)
      assertTrue(run(fast, _ => false).isFailure, fast.runs == 1, run(slow, _ => false).isFailure, slow.runs == 1)
    },
    test("work that ends later is judged the same way") {
      val slow                                   = Run(150, 120, 60)
      val wrong                                  = Run(150, 40, 40)
      def later(r: Run, correct: Int => Boolean) =
        TimeBudget.withinZIO("work", 100, r.nanos, r.log)(ZIO.succeed(r.work()))(n => assertTrue(correct(n)))
      for
        passed <- later(slow, _ => true)
        failed <- later(wrong, _ => false)
      yield assertTrue(passed.isSuccess, slow.runs == 3, failed.isFailure, wrong.runs == 1)
    },
    test("a wrong result on a retry fails, though the first run was correct") {
      val r = Run(150, 40, 40)
      assertTrue(run(r, _ == 1).isFailure, r.runs == 2)
    },
  )

end TimeBudgetSpec
