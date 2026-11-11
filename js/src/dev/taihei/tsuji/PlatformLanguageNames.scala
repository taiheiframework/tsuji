package dev.taihei.tsuji

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.Try

@js.native
@JSGlobal("Intl.DisplayNames")
private[tsuji] class DisplayNames(
  @unused locales: js.Array[String],
  @unused options: js.Dictionary[Any])
  extends js.Object:
  def of(code: String): js.UndefOr[String] = js.native

private[tsuji] object PlatformLanguageNames:

  /**
   * The name of the language `tag` in the language `in`, from `Intl.DisplayNames`, or `None`
   * when the runtime does not know it (it then returns the code itself, or nothing).
   */
  def displayName(tag: String, in: String): Option[String] =
    Try(names(in).of(tag).toOption).toOption.flatten.filter(n => n.nonEmpty && n != tag)

  // Building an Intl.DisplayNames costs far more than asking it for a name, and a list of
  // languages asks the same one for every element. The latest few readers' are kept, the oldest
  // going first: a reader's language can come from a request. One that cannot be built throws,
  // and nothing is kept for it. JavaScript runs one thing at a time, so a plain map is enough.
  private val Kept   = 64
  private val byName = js.Dictionary.empty[DisplayNames]

  private def names(in: String): DisplayNames =
    byName.getOrElse(
      in, {
        val made = new DisplayNames(js.Array(in), js.Dictionary[Any]("type" -> "language"))
        if byName.size >= Kept then byName.remove(byName.keys.head)
        byName(in) = made
        made
      },
    )

end PlatformLanguageNames
