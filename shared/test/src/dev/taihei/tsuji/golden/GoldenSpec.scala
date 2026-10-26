package dev.taihei.tsuji.golden

import java.nio.charset.StandardCharsets.UTF_8

import scala.annotation.nowarn
import scala.util.control.NonFatal

import zio.test.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.TestJson.{*, given}

import com.github.plokhotnyuk.jsoniter_scala.core.*

// The gate of the wire format (ADR 0006, sections 2 and 7): this version writes, byte for byte,
// what the released 5.0.1 wrote for every value of the corpus, and reads what it read from every
// input, on the JVM and on Scala.js (Golden501, written by golden/generate). The only differences
// are those ADR 0006 lists, each named below; changing any other byte is
// a wire break and needs the CTO's yes.
@nowarn("cat=deprecation")
object GoldenSpec extends ZIOSpecDefault:

  given Catalogues = Corpus.catalogues

  private def withoutText(m: Message): String = writeToString(m)(
    using Message.codec(withText = false)
  )

  // What this version writes and reads, in the generator's form.
  private def written(name: String, w: Written): List[(String, String)] = w match
    case Written.Msg(m, withText) =>
      List(s"write|$name" -> writeToString(m)(
        using Message.codec(withText)
      ))
    case Written.Disp(d)          => List(s"write|$name" -> writeToString(d))
    case Written.Txt(t)           => List(s"write|$name" -> writeToString(t))
    case Written.Legacy(m)        => List(s"write|$name" -> writeToString(m))
    case Written.Lang(l) => List(s"write|$name" -> writeToString(l), s"write key|$name" -> writeToString(Map(l -> 1)))
    case Written.Key(k)  =>
      val key = MessageKey.parse(k).toOption.get
      List(s"write|$name" -> writeToString(key), s"write key|$name" -> writeToString(Map(key -> 1)))
    case Written.Cat(c)  => List(s"write|$name" -> c.toJson)

  private def displayable(d: Displayable): String = d match
    case m: Message        => "message " + withoutText(m)
    case t: Text           => "text " + writeToString(t)
    case l: legacy.Message => "legacy " + writeToString(l)
    case other             => "other " + other.toString

  private def result[A](read: => Either[String, A])(show: A => String): String =
    try read.fold(_ => "refused", show)
    catch case NonFatal(e) => s"threw ${e.getClass.getName}"

  private val actual: Map[String, String] =
    val writes = Corpus.writes.flatMap(written)
    val reads  = Corpus.reads.flatMap: (name, input) =>
      List(
        s"message|$name" -> result(input.fromJson[Message])(withoutText),
        s"displayable|$name" -> result(input.fromJson[Displayable])(displayable),
        s"text|$name" -> result(input.fromJson[Text])(writeToString(_)),
        s"legacy|$name" -> result(input.fromJson[legacy.Message])(writeToString(_)),
      )
    val values = Corpus.values.flatMap: (name, input) =>
      List(
        s"language|$name" -> result(input.fromJson[Language])(writeToString(_)),
        s"key|$name" -> result(input.fromJson[MessageKey])(writeToString(_)),
      )
    val keys   = Corpus.keys.flatMap: (name, input) =>
      List(
        s"language key|$name" -> result(input.fromJson[Map[Language, Int]])(writeToString(_)),
        s"key key|$name" -> result(input.fromJson[Map[MessageKey, Int]])(writeToString(_)),
      )
    val parsed = Corpus.catalogueInputs.map: (name, input) =>
      s"catalogue|$name" -> result(Catalogue.parse(input).left.map(_.toString)): c =>
        (c.toJson :: c.problems.map(_.toString)).mkString("\n")
    (writes ++ reads ++ values ++ keys ++ parsed).toMap

  end actual

  // The differences ADR 0006 lists, by case.

  // 1. An unpaired surrogate is written as U+FFFD.
  private def unpaired(s: String, by: Char): String =
    val out = new java.lang.StringBuilder
    var i   = 0
    while i < s.length do
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) then
        out.append(c).append(s.charAt(i + 1))
        i += 1
      else if Character.isSurrogate(c) then out.append(by)
      else out.append(c)
      i += 1
    out.toString

  // 2. A number whose unscaled value is Long.MinValue is written as java.math.BigDecimal.toString
  // writes it; 5.0.1 wrote it differently on the JVM, and on Scala.js at a scale other than 0.
  private val LongMinValue = "(-0\\.0?)?-9223372036854775808(E[+-][0-9]+)?".r

  private def number(n: String): String = s"number ${Corpus.numbers.indexOf(n)} ($n)"

  private val longMinValue: Map[String, String] = Map(
    "scalars other extremes" -> "-9223372036854775808",
    "long min" -> "-9223372036854775808",
    number("-9223372036854775808") -> "-9223372036854775808",
    number("-922337203685477580.8") -> "-922337203685477580.8",
    number("-92233720368547758.08") -> "-92233720368547758.08",
    number("-9223372036854775808e2") -> "-9.223372036854775808E+20",
  )

  // 3 to 5. Input 6.0.0 refuses where 5.x read it: escapes of unpaired surrogates, numbers that
  // are not JSON, content after the top-level value.
  private val lenient = Set("01", "-01", "00", "1.", "-.5", "1.e5")

  private val refusedNow: Set[String] = Set(
    "say high escaped",
    "say low escaped",
    "say high at end escaped",
    "say reversed escaped",
    "text high escaped",
    "high escaped",
    "int leading zero",
    "number 01",
    "trailing",
    "two values",
  ) ++ lenient.map(number)

  // 7. An unpaired surrogate char in the String read becomes '?'.
  private val rawSurrogate = Set("say raw high surrogate", "say raw low surrogate", "text raw high surrogate")

  private def expected(entry: String, golden: String): String =
    val what  = entry.takeWhile(_ != '|')
    val name  = entry.drop(what.length + 1)
    val fixed = longMinValue.get(name).fold(golden)(n => LongMinValue.replaceAllIn(golden, n))
    if what == "write" || what == "write key" then unpaired(fixed, '\ufffd')
    else if refusedNow(name) then "refused"
    else if rawSurrogate(name) then unpaired(fixed, '?')
    else fixed

  // The JVM's catalogue export is compared in taihei-tsuji-tools' tests.
  private val golden = Golden501.entries.filterNot(_._1.startsWith("export"))

  def spec = suite(s"The wire format of 5.0.1 on ${Golden501.platform}")(
    test("every case of the corpus has its golden entry, and none is left over") {
      assertTrue(golden.size > 1000, golden.keySet == actual.keySet)
    },
    test("writing gives 5.0.1's bytes, but for the listed differences") {
      val writes = golden.filter(_._1.startsWith("write"))
      val wrong  = writes.collect {
        case (k, v) if actual(k) != expected(k, v) => s"$k:\n  want ${expected(k, v)}\n  got  ${actual(k)}"
      }
      val bytes  = Corpus.writes.collect:
        case (name, Written.Txt(t))
          if writeToArray(t).toSeq != expected(s"write|$name", golden(s"write|$name")).getBytes(UTF_8).toSeq => name
      assertTrue(writes.size > 150, wrong.isEmpty, bytes.isEmpty)
    },
    test("reading gives 5.0.1's values, but for the listed differences") {
      val reads = golden.filterNot(_._1.startsWith("write"))
      val wrong = reads.collect {
        case (k, v) if actual(k) != expected(k, v) => s"$k:\n  want ${expected(k, v)}\n  got  ${actual(k)}"
      }
      assertTrue(reads.size > 700, wrong.isEmpty)
    },
    test("each listed difference is one, on this platform or the other") {
      def differs(names: Set[String]) =
        golden.exists((k, v) => names(k.dropWhile(_ != '|').drop(1)) && expected(k, v) != v)
      val unpairedWrites              = golden.exists((k, v) => k.startsWith("write") && unpaired(v, '\ufffd') != v)
      assertTrue(
        unpairedWrites,
        refusedNow.forall(n => differs(Set(n))),
        differs(rawSurrogate),
        differs(longMinValue.keySet) || Golden501.platform == "Scala.js",
        // On Scala.js 5.0.1 wrote Long.MinValue right at scale 0 and wrong at the others.
        differs(Set(number("-922337203685477580.8"))),
      )
    },
  )

end GoldenSpec
