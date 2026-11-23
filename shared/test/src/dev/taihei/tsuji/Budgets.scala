package dev.taihei.tsuji

import scala.annotation.tailrec
import scala.util.Try

// A message's budget counts its work in units, whatever the machine: these read how much it spends.
object Budgets:

  /** A budget with exactly `left` units left, or already spent when `left` is negative. */
  def withLeft(left: Long): Budget =
    val budget = Budget.fresh()
    // Charging past the budget throws once it is spent; what is left is what counts.
    Try(budget.charge((Budget.PerMessage - left).toInt))
    budget

  /** The fewest units the message formats within, for `en`. */
  def needed(message: MessageFormat2.Message): Long =
    def fits(left: Long): Boolean = MessageFormat2.formatted(message, Map.empty, "en", withLeft(left)).isRight
    @tailrec def search(low: Long, high: Long): Long = (low + high) / 2 match
      case _ if low >= high       => low
      case middle if fits(middle) => search(low, middle)
      case middle                 => search(middle + 1, high)
    search(0, Budget.PerMessage)

end Budgets
