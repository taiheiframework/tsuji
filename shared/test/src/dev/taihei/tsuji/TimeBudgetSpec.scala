package dev.taihei.tsuji

import scala.collection.mutable.ListBuffer

import zio.test.*

import zio.ZIO

// TimeBudget retries a slow but correct run and nothing else, and scales the budget by how slow the
// machine is. A fake clock makes each attempt take exactly the time the test says, and a fake
// reference makes the machine exactly as slow as the test says.
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

  private def machine(referenceMillis: Double) = TimeBudget.Speed(() => referenceMillis, idleMillis = 10)

  private val idle = machine(10)

  private def run(
    run: Run,
    correct: Int => Boolean = _ => true,
    speed: TimeBudget.Speed = idle,
  ): TestResult =
    TimeBudget.within("work", 100, run.nanos, run.log, speed)(run.work())(n => assertTrue(correct(n)))

  def spec = suite("TimeBudget")(
    test("a run within the budget passes at once") {
      val r = Run(40)
      assertTrue(
        run(r).isSuccess,
        r.runs == 1,
        r.lines.toList == List("work: attempt 1 of 3 took 40 ms, budget 100 ms times 1.0"),
      )
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
        List("150 ms, budget 100 ms times 1.0", "120 ms, budget 100 ms times 1.0", "100 ms, budget 100 ms times 1.0"),
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
    test("on a slow machine the budget grows as much as the reference took longer, at most MaxScale times") {
      val busy   = Run(290)
      val busier = Run(301, 301, 301)
      val stuck  = Run(2001, 2001, 2001)
      assertTrue(
        run(busy, speed = machine(30)).isSuccess,
        busy.lines.toList == List("work: attempt 1 of 3 took 290 ms, budget 100 ms times 3.0"),
        run(busier, speed = machine(30)).isFailure,
        TimeBudget.MaxScale == 20.0,
        run(stuck, speed = machine(1000)).isFailure,
        stuck.lines.forall(_.endsWith("times 20.0")),
      )
    },
    test("on a fast machine the budget stays as named") {
      val r = Run(100, 100, 100)
      assertTrue(run(r, speed = machine(2)).isFailure, r.lines.forall(_.endsWith("times 1.0")))
    },
    test("the reference is timed on both sides of the work, and the slower side counts") {
      val readings = Iterator(10.0, 10.0, 10.0, 20.0, 20.0, 20.0)
      val speed    = TimeBudget.Speed(() => readings.next(), idleMillis = 10)
      val r        = Run(150)
      assertTrue(run(r, speed = speed).isSuccess, r.lines.head.endsWith("times 2.0"))
    },
    test("one slow reading of the reference does not move the scale") {
      val readings = Iterator(10.0, 500.0, 10.0, 10.0, 10.0, 500.0)
      val speed    = TimeBudget.Speed(() => readings.next(), idleMillis = 10)
      val r        = Run(40)
      assertTrue(run(r, speed = speed).isSuccess, r.lines.head.endsWith("times 1.0"))
    },
    test("the real reference takes some time on this platform") {
      val taken = Vector.fill(3)(TimeBudget.Speed.machine.millis())
      assertTrue(taken.forall(_ > 0.0), TimeBudget.Speed.machine.scale() >= 1.0)
    },
    test("a wrong result on a retry fails, though the first run was correct") {
      val r = Run(150, 40, 40)
      assertTrue(run(r, _ == 1).isFailure, r.runs == 2)
    },
  )

end TimeBudgetSpec
