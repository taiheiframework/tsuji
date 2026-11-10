package dev.taihei.tsuji

/** How `:number` writes a number: as it is, as a percentage, or as an amount in a currency. */
private[tsuji] enum NumberStyle:
  case Plain, Percent
  case Currency(code: String)

/**
 * A number as its plain decimal digits (`-1234.5`), which is what both platforms' formatters are
 * given: through a `Double` a `Long` past 2^53 and a `BigDecimal` would lose digits, and
 * `Double.toString` writes 1.0 as `1.0` on the JVM and as `1` in JavaScript.
 */
private[tsuji] object Decimal:

  /** Whether the value is of a number type, as opposed to text that may read as a number. */
  def isNumber(value: Any): Boolean = value match
    case _: Byte | _: Short | _: Int | _: Long | _: Float | _: Double => true
    case _: BigInt | _: BigDecimal                                    => true
    case _: java.math.BigInteger | _: java.math.BigDecimal            => true
    case _                                                            => false

  /**
   * The digits of a finite number of at most [[Operands.MaxNumberText]] digits, or None: a whole
   * number as it is, a `Float` or `Double` as the fewest digits that read back as it (1.0 is `1`,
   * 1.1f is `1.1` on both platforms), a `BigDecimal` with the digits it has, and text that is a
   * plain decimal as it is written, or else as the `Double` it reads as (`1e3`, ` 5 `).
   */
  def plain(value: Any): Option[String] = value match
    case null                                  => None
    case _: Byte | _: Short | _: Int | _: Long => Some(value.toString)
    case n: BigInt               => Option.when(n.bitLength <= Operands.MaxBits)(n.toString)
    case n: java.math.BigInteger => Option.when(n.bitLength <= Operands.MaxBits)(n.toString)
    case f: Float                => ofFloat(f)
    case d: Double               => ofDouble(d)
    case d: BigDecimal           => ofDecimal(d.bigDecimal)
    case d: java.math.BigDecimal => ofDecimal(d)
    case other                   => ofText(other.toString)

  /** Whether a number and the key of a `.match` variant are the same number: 1.0 and `1` are. */
  def sameNumber(plain: String, key: String): Boolean =
    Operands.parse(key).isDefined && Operands.parse(plain).isDefined &&
    new java.math.BigDecimal(plain).compareTo(new java.math.BigDecimal(key)) == 0

  private def ofText(text: String): Option[String] =
    Option
      .when(text.length <= Operands.MaxNumberText)(text)
      .flatMap(t =>
        Operands.parse(t).map(_ => t.stripPrefix("+")).orElse(t.toDoubleOption.flatMap(ofDouble))
      )

  private def ofDouble(d: Double): Option[String] =
    Option
      .when(!d.isNaN && !d.isInfinite)(d)
      .flatMap(d => ofDecimal(Operands.shortest(d, 17, _.toDouble == d)))

  // A whole Float is read as the Double it is, as Operands reads it: Scala.js has one number
  // type, so a whole Double arrives here too.
  private def ofFloat(f: Float): Option[String] =
    Option
      .when(!f.isNaN && !f.isInfinite)(f)
      .flatMap(f =>
        if f.isWhole then ofDouble(f.toDouble)
        else ofDecimal(Operands.shortest(f.toDouble, 9, _.toDouble.toFloat == f))
      )

  // Its digits are counted before the plain text is made: 1E+1000000000 is a billion digits.
  private def ofDecimal(d: java.math.BigDecimal): Option[String] =
    Option
      .when(
        d.scale <= Operands.MaxNumberText && d.precision.toLong - d.scale <= Operands.MaxNumberText
      )(d.toPlainString)

end Decimal
