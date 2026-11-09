package dev.taihei.tsuji

/**
 * The plural operands of a number, as CLDR defines them (UTS #35, Language Plural Rules): `n` is
 * the absolute value, `i` its integer digits, `v` the number of visible fraction digits and `w`
 * that number without trailing zeros, `f` and `t` the visible fraction digits with and without
 * trailing zeros, and `e` (also `c`) the exponent of a compact notation such as `1.2c6`. A value
 * Tsuji formats is never compact, so `e` is 0 outside CLDR's own samples. `negative` keeps the
 * sign, which the operands leave out, for an exact key such as `=-1`.
 */
final private[tsuji] case class Operands(
  negative: Boolean,
  i: BigInt,
  v: Int,
  w: Int,
  f: BigInt,
  t: BigInt,
  e: Int = 0):

  // `n` is a whole number when its visible fraction digits are all zero: 1.0 is 1.
  def whole: Option[BigInt] = Option.when(f == 0)(i)

  // Whether the number is exactly `key`, as `=1` asks: 1 and 1.0 are, 1.5 and -1 are not.
  def equalsExactly(key: BigInt): Boolean =
    whole.exists(n => (if negative then -n else n) == key)

end Operands

private[tsuji] object Operands:

  /**
   * The longest text read as a number. A value comes from the caller and may come from the wire;
   * its digits are read whole, so longer text counts as no number and selects only `other`.
   */
  val MaxNumberText = 1100

  // About MaxNumberText decimal digits.
  private val MaxBits = 3700

  /**
   * The operands of a value given to `:plural`: an integer as it is, a `Float` or `Double` as the
   * fewest digits that read back as the same value, without trailing fraction zeros (1.1f is 1.1,
   * 1.0 is 1), a `BigDecimal` and a numeric text with the fraction digits they show, so `"1.0"`
   * has `v = 1`. Scala.js has one type for both, so there a `Double` that is exactly a `Float`
   * with a fraction reads as that `Float`: the same for every value a program writes as a
   * decimal, and differs only for a widened `Float` such as `1.1f.toDouble`.
   * None when the value is no finite number within [[MaxNumberText]] digits.
   */
  def of(value: Any): Option[Operands] = value match
    case n: Int    => parse(n.toString)
    case n: Long   => parse(n.toString)
    case n: Short  => parse(n.toString)
    case n: Byte   => parse(n.toString)
    case n: BigInt => Option.when(n.bitLength <= MaxBits)(n).flatMap(n => parse(n.toString))
    case n: java.math.BigInteger => of(BigInt(n))
    case d: Float                => ofFloat(d)
    case d: Double               => ofDouble(d)
    case d: BigDecimal           => ofDecimal(d.bigDecimal)
    case d: java.math.BigDecimal => ofDecimal(d)
    case other                   => ofText(other.toString)

  // A text is read as written when it is a plain decimal; otherwise as a Double, as 6.0 read
  // every value, so that `1e3` and ` 5 ` still count.
  private def ofText(text: String): Option[Operands] =
    Option
      .when(text.length <= MaxNumberText)(text)
      .flatMap(t => parse(t).orElse(t.toDoubleOption.flatMap(ofDouble)))

  private def ofDouble(d: Double): Option[Operands] =
    Option
      .when(!d.isNaN && !d.isInfinite)(d)
      .flatMap(d => ofDecimal(shortest(d, 17, _.toDouble == d)))

  // A whole Float is read as the Double it is: Scala.js has one number type, so a whole Double
  // such as 8589934592.0 arrives here too, and must stay that integer, not 8589935000.
  private def ofFloat(f: Float): Option[Operands] =
    Option
      .when(!f.isNaN && !f.isInfinite)(f)
      .flatMap(f =>
        if f.isWhole then ofDouble(f.toDouble)
        else ofDecimal(shortest(f.toDouble, 9, _.toDouble.toFloat == f))
      )

  // The fewest significant digits that read back as the value, found by the same arithmetic on
  // both platforms: Double.toString differs between them (Scala.js prints 1.0 as 1, and Java 17
  // does not always print the shortest digits), and a Float's text even more. 17 digits always
  // read back as a Double, 9 as a Float.
  private def shortest(
    d: Double,
    maxDigits: Int,
    readsBack: String => Boolean,
  ): java.math.BigDecimal =
    val exact = new java.math.BigDecimal(d)
    (1 to maxDigits).iterator
      .map(p => exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN)))
      .find(c => readsBack(c.toString))
      .getOrElse(exact)
      .stripTrailingZeros

  // Its digits are counted before the plain text is made: 1E+1000000000 is a billion digits.
  private def ofDecimal(d: java.math.BigDecimal): Option[Operands] =
    Option
      .when(d.scale <= MaxNumberText && d.precision.toLong - d.scale <= MaxNumberText)(d)
      .flatMap(d => parse(d.toPlainString))

  /**
   * The operands of a plain decimal: an optional sign, ASCII digits, and an optional point with
   * at least one digit after it (`-12.50`). None for anything else, or past [[MaxNumberText]].
   */
  def parse(text: String): Option[Operands] =
    val negative       = text.startsWith("-")
    val unsigned       = if negative || text.startsWith("+") then text.drop(1) else text
    val (integer, dot) = unsigned.span(isDigit)
    val fraction       = dot.drop(1)
    val plain          = text.length <= MaxNumberText && integer.nonEmpty &&
      (dot.isEmpty || (dot.head == '.' && fraction.nonEmpty && fraction.forall(isDigit)))
    Option.when(plain):
      val trimmed = fraction.reverse.dropWhile(_ == '0').reverse
      Operands(
        negative = negative,
        i = BigInt(integer),
        v = fraction.length,
        w = trimmed.length,
        f = digits(fraction),
        t = digits(trimmed),
      )

  end parse

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

  private def digits(s: String): BigInt = if s.isEmpty then BigInt(0) else BigInt(s)

end Operands

/**
 * CLDR's cardinal plural rules ([[CldrPlurals]]), read once into conditions: which category
 * (`zero`, `one`, `two`, `few`, `many` or `other`) a number takes in a language.
 */
private[tsuji] object PluralRules:

  /** The six categories CLDR knows. */
  val Categories: List[String] = List("zero", "one", "two", "few", "many", "other")

  /**
   * The category of the number in the language of the BCP 47 tag `locale`, found as Tsuji falls
   * back: the whole tag, then without its last subtag, step by step (`pt-PT`, then `pt`); a
   * language CLDR has no rules for takes the rules of `root`, where every number is `other`.
   */
  def category(locale: String, operands: Operands): String =
    rulesFor(locale).collectFirst { case (c, condition) if holds(condition, operands) => c }
      .getOrElse("other")

  /** The categories a number can take in the language of `locale`, `other` always among them. */
  def categories(locale: String): List[String] = rulesFor(locale).map(_._1) :+ "other"

  // A locale's rules, from its longest prefix CLDR names. Only the subtags before an extension
  // count, and only the first few: a tag can come from a request, and no CLDR locale has more.
  private def rulesFor(locale: String): List[(String, Condition)] =
    val subtags = locale.take(64).split("[-_]").toList.takeWhile(_.length > 1).take(3)
    (subtags.length to 1 by -1).iterator
      .map(n => subtags.take(n).mkString("-").toLowerCase(java.util.Locale.ROOT))
      .collectFirst(Function.unlift(table.get))
      .getOrElse(table("root"))

  // An `or` of `and`s of relations, as CLDR writes a condition.
  private type Condition = List[List[Relation]]

  final private[tsuji] case class Relation(
    operand: Char,
    modulus: Option[Int],
    equal: Boolean,
    ranges: List[(Int, Int)])

  private lazy val table: Map[String, List[(String, Condition)]] =
    CldrPlurals.RuleSets.flatMap: (locales, rules) =>
      val parsed = rules.map((c, text) => c -> condition(text))
      locales.split(' ').toList.map(l =>
        l.replace('_', '-').toLowerCase(java.util.Locale.ROOT) -> parsed
      )
    .toMap

  // The generated table is the only text read here, and a test reads every relation in it: a
  // relation that does not parse is a bug in the table, and holds for no number.
  private def condition(text: String): Condition =
    text.split(" or ").toList.map(_.split(" and ").toList.map(r => relation(r).getOrElse(Never)))

  private val Never = Relation('?', None, equal = true, Nil)

  private[tsuji] def relation(text: String): Option[Relation] =
    text.trim.split(' ').toList match
      case List(op, "%", m, cmp, rs) =>
        m.toIntOption.filter(_ > 0).flatMap(m => relation(op, Some(m), cmp, rs))
      case List(op, cmp, rs)         => relation(op, None, cmp, rs)
      case _                         => None

  private def relation(
    op: String,
    modulus: Option[Int],
    cmp: String,
    rs: String,
  )
    : Option[Relation] =
    val list = rs.split(",", -1).toList.map(range)
    Option.when(
      op.length == 1 && "nivwftce".contains(op.head) && (cmp == "=" || cmp == "!=") &&
      list.forall(_.isDefined)
    )(Relation(op.head, modulus, cmp == "=", list.flatten))

  private def range(text: String): Option[(Int, Int)] =
    text.split("\\.\\.", -1).toList.map(_.toIntOption) match
      case List(Some(a))          => Some(a -> a)
      case List(Some(a), Some(b)) => Some(a -> b)
      case _                      => None

  private def holds(condition: Condition, o: Operands): Boolean =
    condition.exists(_.forall(r => holds(r, o)))

  // A relation on `n` holds only for a whole `n` (or its remainder): 2.5 is in no range 2..4,
  // so `n = 2..4` is false for it and `n != 2..4` true.
  private def holds(r: Relation, o: Operands): Boolean =
    val value   = r.operand match
      case 'n'       => o.whole
      case 'i'       => Some(o.i)
      case 'v'       => Some(BigInt(o.v))
      case 'w'       => Some(BigInt(o.w))
      case 'f'       => Some(o.f)
      case 't'       => Some(o.t)
      case 'e' | 'c' => Some(BigInt(o.e))
      case _         => None
    val reduced = value.map(x => r.modulus.fold(x)(m => x % m))
    val within  = reduced.exists(x => r.ranges.exists((a, b) => x >= a && x <= b))
    within == r.equal

end PluralRules
