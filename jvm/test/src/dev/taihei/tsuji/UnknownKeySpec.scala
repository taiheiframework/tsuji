package dev.taihei.tsuji

import zio.test.*

import dev.taihei.tsuji.TestJson.*

// A thread with a small stack exists on the JVM only.
object UnknownKeySpec extends ZIOSpecDefault:

  private def onSmallStack[A](f: => A): Either[Throwable, A] =
    var result: Either[Throwable, A] = Left(new IllegalStateException("did not run"))
    val body: Runnable               = () =>
      result =
        try Right(f)
        catch case e: Throwable => Left(e)
    val thread                       = new Thread(null, body, "small", 256 * 1024)
    thread.start()
    thread.join()
    result

  def spec = suite("a message with a key this program does not know")(
    test("arguments nested too deep are refused, and what is kept writes and hashes on a small stack") {
      given Catalogues = Catalogues()
      def nested(n: Int) =
        s"""{"key":"newer.message","args":{"a":${"[" * n}1${"]" * n}},"text":"From a newer server"}"""
      // Deeper than the kept arguments' bound of 16, within the reader's bound of 64.
      val deep           = nested(20).fromJson[Message]
      // Deeper than any JSON the reader takes.
      val deeper         = nested(5000).fromJson[Message]
      val kept           = nested(14).fromJson[Message]
      val used           = onSmallStack(kept.map(m => (m.toJson, m.hashCode)))
      assertTrue(
        deep.left.exists(_.contains("nest deeper than 16")),
        deeper.left.exists(_.contains("deeper than 64")),
        kept.isRight,
        used.exists(_.isRight),
      )
    },
    test("arguments with too many values are refused") {
      given Catalogues = Catalogues()
      val wide = s"""{"key":"newer.message","args":{"a":[${List.fill(100001)("1").mkString(",")}]},"text":"x"}"""
      assertTrue(wide.fromJson[Message].left.exists(_.contains("more than 100000 values")))
    },
  )

end UnknownKeySpec
