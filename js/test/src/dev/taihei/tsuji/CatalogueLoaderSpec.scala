package dev.taihei.tsuji

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.URIUtils
import scala.scalajs.js.typedarray.Uint8Array

import zio.test.*

import zio.ZIO

// fetch and Future-based loading exist in Scala.js only; Node's fetch reads data: URLs.
object CatalogueLoaderSpec extends ZIOSpecDefault:

  private val German = Language("de")

  private def dataUrl(json: String) = "data:application/json," + URIUtils.encodeURIComponent(json)

  private def load(
    c: Catalogues,
    l: Language,
    url: String,
  ) =
    ZIO.fromFuture(ec =>
      CatalogueLoader.load(c, l, url)(
        using ec: ExecutionContext
      )
    )

  private val Url = "https://example.invalid/de.json"

  private val Limit = Catalogue.MaxLength.toLong * 4

  // Runs `use` with the global `name` replaced by `stub` (js.undefined removes it), and puts the
  // original back however `use` ends, interrupted too.
  private def withGlobal[R, E, A](name: String, stub: js.Any)(use: ZIO[R, E, A]) =
    val global = js.Dynamic.global.globalThis
    ZIO.acquireReleaseWith(
      ZIO.succeed:
        val original = global.selectDynamic(name)
        global.updateDynamic(name)(stub)
        original
    )(original => ZIO.succeed(global.updateDynamic(name)(original)))(_ => use)

  private def withFetch[R, E, A](stub: js.Any)(use: ZIO[R, E, A]) = withGlobal("fetch", stub)(use)

  private def answering(response: => js.Any): js.Function1[String, js.Promise[js.Any]] =
    _ => js.Promise.resolve[js.Any](response)

  private def bytes(values: Int*) = new Uint8Array(values.map(_.toShort).toJSArray)

  private def utf8(text: String) = bytes(text.getBytes("UTF-8").map(_ & 0xff).toSeq*)

  // A body stream that hands out `chunks` one by one and records whether it was cancelled.
  final private class Body(chunks: Uint8Array*):
    var cancelled = false
    var pulled    = 0

    val stream = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
      js.Dynamic.literal(
        pull = (controller: js.Dynamic) =>
          if pulled < chunks.length then controller.enqueue(chunks(pulled))
          else controller.close()
          pulled += 1
        ,
        cancel = (_: js.Any) => cancelled = true,
      )
    )

  // A TextDecoder as an old polyfill may be: it takes the options and ignores `fatal`.
  private class LenientDecoder(label: String, options: js.Any) extends js.Object:
    val fatal                                          = false
    def decode(bytes: js.Any, options: js.Any): String = ""

  private def response(
    body: Body,
    status: Int = 200,
    headers: js.Dictionary[String] = js.Dictionary(),
  ) =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      body.stream,
      js.Dynamic.literal(status = status, headers = headers),
    )

  // A response without a body stream, as a runtime or a polyfill without streams gives.
  private def streamless(content: Uint8Array) = js.Dynamic.literal(
    ok = true,
    status = 200,
    headers = js.Dynamic.newInstance(js.Dynamic.global.Headers)(),
    body = null,
    arrayBuffer = () => js.Promise.resolve[js.Any](content.buffer),
    text = () => js.Promise.reject("text() is not bounded"),
  )

  private val GermanFile = """{"format":1,"language":"de","messages":{"order.cancelled":"Storniert"}}"""

  def spec = suite("CatalogueLoader")(
    test("a fetched catalogue is added and its listeners hear of it") {
      given c: Catalogues = Catalogues(Seq(Orders))
      var heard  = List.empty[Language]
      c.onAdded(l => heard = l :: heard)
      val before = Orders.cancelled.localize(German)
      for problems <-
          load(c, German, dataUrl("""{"format":1,"language":"de","messages":{"order.cancelled":"Storniert"}}"""))
      yield assertTrue(
        problems.isEmpty,
        before == "Your order was cancelled",
        Orders.cancelled.localize(German) == "Storniert",
        heard == List(German),
      )
    },
    test("CoreMessages show in English until their exported Dutch file is loaded") {
      given c: Catalogues = Catalogues(CoreMessages.families)
      val min    = CoreMessages.Validation.stringMinLength(min = 8)
      val before = min.localize(Language.Dutch)
      val file   =
        """{"format":1,"language":"nl","messages":{"taihei.validation.stringMinLength":"lengte moet minimaal {$min} zijn"}}"""
      for problems <- load(c, Language.Dutch, dataUrl(file))
      yield assertTrue(
        problems.isEmpty,
        before == "length should be greater than 8",
        min.localize(Language.Dutch) == "lengte moet minimaal 8 zijn",
      )
    },
    test("a body larger than the limit is dropped while it is read, also without a declared length") {
      val c   = Catalogues(Seq(Orders))
      val big = "x" * (Catalogue.MaxLength * 4 + 10)
      withFetch(answering(js.Dynamic.newInstance(js.Dynamic.global.Response)(big)))(load(c, German, Url))
        .map(problems => assertTrue(problems.exists(_.message.contains("larger than"))))
    },
    test("a file in another language, a file that is no catalogue, and a failed fetch are problems") {
      val c = Catalogues(Seq(Orders))
      for
        other   <- load(c, German, dataUrl("""{"format":1,"language":"fr","messages":{}}"""))
        garbage <- load(c, German, dataUrl("[[[["))
        failed  <- load(c, German, "nosuchscheme://nowhere")
      yield assertTrue(
        other.exists(_.message.contains("catalogue of fr")),
        garbage.exists(_.message.contains("not JSON")),
        failed.exists(_.message.contains("cannot fetch")),
        c.languages.isEmpty,
      )
    },
    test("without fetch in the runtime, loading is a problem and nothing throws") {
      val c = Catalogues(Seq(Orders))
      withFetch(js.undefined)(load(c, German, Url)).map(problems =>
        assertTrue(
          problems.map(_.message) == List(s"cannot fetch $Url: there is no fetch here"),
          problems.forall(_.language.contains(German)),
          c.languages.isEmpty,
        )
      )
    },
    test("a status that is not OK is a problem, and its body is let go unread") {
      val c    = Catalogues(Seq(Orders))
      val body = Body(utf8(GermanFile))
      withFetch(answering(response(body, status = 404)))(load(c, German, Url)).map(problems =>
        assertTrue(
          problems.map(_.message) == List(s"cannot fetch $Url: HTTP 404"),
          body.cancelled,
          c.languages.isEmpty,
        )
      )
    },
    test("a declared length over the limit is refused before the body is read") {
      val c        = Catalogues(Seq(Orders))
      val body     = Body(utf8(GermanFile))
      val declared = js.Dictionary("content-length" -> (Limit + 1).toString)
      withFetch(answering(response(body, headers = declared)))(load(c, German, Url)).map(problems =>
        assertTrue(
          problems.map(_.message) == List(s"cannot fetch $Url: larger than $Limit bytes"),
          body.cancelled,
          c.languages.isEmpty,
        )
      )
    },
    test("a declared length at the limit is read") {
      val c        = Catalogues(Seq(Orders))
      val declared = js.Dictionary("content-length" -> Limit.toString)
      withFetch(answering(response(Body(utf8(GermanFile)), headers = declared)))(load(c, German, Url))
        .map(problems => assertTrue(problems.isEmpty, c.languages == Set(German)))
    },
    test("malformed UTF-8 in a streamed body is refused, as on the JVM, and the stream is let go") {
      val c      = Catalogues(Seq(Orders))
      // 0xFF is never UTF-8; the TextDecoder used to replace it with U+FFFD and load the file.
      val prefix = utf8("""{"format":1,"language":"de","messages":{"order.cancelled":"Storn""")
      val body   = Body(prefix, bytes(0xff), utf8("""iert"}}"""))
      withFetch(answering(response(body)))(load(c, German, Url)).map(problems =>
        assertTrue(
          problems.map(_.message) == List(s"cannot fetch $Url: not UTF-8"),
          body.cancelled,
          c.languages.isEmpty,
        )
      )
    },
    test("a body that ends inside a character is refused") {
      val c = Catalogues(Seq(Orders))
      // 0xC3 opens a two-byte character that never comes.
      withFetch(answering(response(Body(utf8(GermanFile), bytes(0xc3)))))(load(c, German, Url))
        .map(problems =>
          assertTrue(problems.map(_.message) == List(s"cannot fetch $Url: not UTF-8"), c.languages.isEmpty)
        )
    },
    test("a decoder that ignores the fatal option is refused before the body is read") {
      val c    = Catalogues(Seq(Orders))
      val body = Body(utf8(GermanFile))
      withGlobal("TextDecoder", js.constructorOf[LenientDecoder])(
        withFetch(answering(response(body)))(load(c, German, Url))
      ).map(problems =>
        assertTrue(
          problems.map(_.message) == List(s"cannot fetch $Url: there is no strict UTF-8 decoder here"),
          // No reader was taken; the stream's own first pull fills its queue regardless.
          !body.stream.locked.asInstanceOf[Boolean],
          c.languages.isEmpty,
        )
      )
    },
    test("a character split over two chunks is decoded") {
      given c: Catalogues = Catalogues(Seq(Orders))
      val text    = """{"format":1,"language":"de","messages":{"order.cancelled":"Storniert é"}}"""
      val encoded = utf8(text)
      // Right after the first byte of the two that encode é.
      val split   = text.getBytes("UTF-8").indexOf(0xc3.toByte) + 1
      val body    = Body(encoded.subarray(0, split), encoded.subarray(split))
      withFetch(answering(response(body)))(load(c, German, Url)).map(problems =>
        assertTrue(problems.isEmpty, Orders.cancelled.localize(German) == "Storniert é")
      )
    },
    test("a body without a stream is counted and decoded strictly, and never read as text") {
      val c        = Catalogues(Seq(Orders))
      val tooLarge = new Uint8Array(Limit.toInt + 1) // zero bytes: UTF-8, but one too many
      // Zero bytes are UTF-8, so at the limit the bytes pass and the parser judges the text.
      val atLimit  = new Uint8Array(Limit.toInt)
      for
        large     <- withFetch(answering(streamless(tooLarge)))(load(c, German, Url))
        limit     <- withFetch(answering(streamless(atLimit)))(load(c, German, Url))
        malformed <- withFetch(answering(streamless(bytes(0x7b, 0xff, 0x7d))))(load(c, German, Url))
        empty     <- ZIO.succeed(c.languages)
        fine      <- withFetch(answering(streamless(utf8(GermanFile))))(load(c, German, Url))
      yield assertTrue(
        large.map(_.message) == List(s"cannot fetch $Url: larger than $Limit bytes"),
        limit.map(_.message) == List(s"a catalogue is at most ${Catalogue.MaxLength} characters"),
        malformed.map(_.message) == List(s"cannot fetch $Url: not UTF-8"),
        empty.isEmpty,
        fine.isEmpty,
        c.languages == Set(German),
      )
    },
  ) @@ TestAspect.sequential // one test replaces the global fetch for a while

end CatalogueLoaderSpec
