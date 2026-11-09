package dev.taihei.tsuji

/**
 * The reader of an HTTP `Accept-Language` value (RFC 9110 section 12.5.4) behind
 * [[Language.fromAcceptLanguage]]. The value comes straight from a request, so it is bounded
 * before anything else is done with it, and nothing in it makes the reader throw.
 */
private[tsuji] object AcceptLanguage:

  // Browsers send a few dozen characters and a handful of entries; servers commonly refuse a
  // whole header block of 8 KB. A longer value is cut to these limits rather than refused, so a
  // reader whose browser sends too much still gets the languages it put first.
  val MaxLength: Int  = 4096
  val MaxEntries: Int = 32

  // RFC 9110 qvalue: "0" with up to three decimals, or "1" with up to three zeros.
  private val QValue = """([01])(?:\.([0-9]{0,3}))?""".r

  /** The languages of `header`, most preferred first; see [[Language.fromAcceptLanguage]]. */
  def parse(header: String): Vector[Language] =
    entries(Option(header).getOrElse(""))
      .flatMap(entry)
      .filter((_, weight) => weight > 0)
      .sortBy((_, weight) => -weight) // stable: equal weights keep the header's order
      .map((range, _) => range)
      .takeWhile(_.isDefined)         // a `*` stands for the default, so it ends the list
      .flatten
      .distinct

  // The entries of the value, cut to MaxLength characters and MaxEntries non-empty entries. A
  // cut inside an entry drops that entry: "en-GB" cut to "en" would name another language.
  private def entries(header: String): Vector[String] =
    val cut = Option
      .when(header.length > MaxLength)(header.take(MaxLength + 1))
      .fold(header)(h => h.take(h.lastIndexOf(',') max 0))
    cut.split(',').iterator.map(_.trim).filter(_.nonEmpty).take(MaxEntries).toVector

  // One entry, `range` or `range;q=weight`, as the language (None for `*`) and its weight in
  // thousandths. Anything else, a malformed tag or weight or another parameter, is skipped.
  private def entry(s: String): Option[(Option[Language], Int)] =
    val parts = s.split(";", -1).toVector.map(_.trim)
    for
      range  <- range(parts.head)
      weight <- parts.tail match
        case Vector()  => Some(1000)
        case Vector(p) => weightOf(p)
        case _         => None
    yield (range, weight)

  private def range(s: String): Option[Option[Language]] = s match
    case "*" => Some(None)
    case tag => Language.fromPlatformTag(tag).toOption.map(Some(_))

  private def weightOf(parameter: String): Option[Int] =
    parameter.split("=", 2).map(_.trim) match
      case Array(name, value) if name == "q" || name == "Q" =>
        value match
          case QValue(whole, fraction) =>
            val thousandths = Option(fraction).getOrElse("").padTo(3, '0').toInt
            Some(whole.toInt * 1000 + thousandths).filter(_ <= 1000)
          case _                       => None
      case _                                                => None

end AcceptLanguage
