package dev.taihei.tsuji

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 * Fetches a language's catalogue file in a browser (or anywhere `fetch` exists) and adds it to a
 * registry, whose [[Catalogues.onAdded]] listeners then hear of it. No dependency on Laminar or
 * on an effect library: it uses the global `fetch` and a `Future`.
 *
 * {{{
 * given Catalogues = Catalogues(Seq(Orders))
 * CatalogueLoader.load(summon[Catalogues], Language("de"), "/i18n/de.json")
 * }}}
 *
 * Until the file arrives, messages show through the fallback chain. On a language switch, load
 * the next language; this library does not prefetch.
 */
object CatalogueLoader:

  // UTF-8 takes at most four bytes per character of a catalogue.
  private val MaxBytes = Catalogue.MaxLength.toLong * 4

  /**
   * Fetches the file at `url`, checks that it is a catalogue of `language`, and adds it. The
   * `Future` never fails: what went wrong, from the network to a template, is in the list.
   */
  def load(
    catalogues: Catalogues,
    language: Language,
    url: String,
  )(
    using ExecutionContext
  ): Future[List[CatalogueProblem]] =
    def problem(message: String) = List(CatalogueProblem(message, Some(language)))
    val fetched                  =
      try
        val fetch = js.Dynamic.global.fetch
        if js.isUndefined(fetch) then
          Future.failed(new IllegalStateException("there is no fetch here"))
        else
          fetch(url)
            .asInstanceOf[js.Promise[js.Dynamic]]
            .toFuture
            .flatMap: response =>
              // A declared length over the limit is refused before the body is read. The
              // declared length is what travels, which a compressed body exceeds once decoded,
              // so the body is also counted as it is read, and dropped past the limit.
              val length   = response.headers.get("content-length")
              val declared =
                if js.isUndefined(length) || length == null then None
                else String.valueOf(length).trim.toLongOption
              if !response.ok.asInstanceOf[Boolean] then
                cancel(response)
                Future.failed(new IllegalStateException(s"HTTP ${response.status}"))
              else if declared.exists(_ > MaxBytes) then
                cancel(response)
                Future.failed(tooLarge)
              else readBounded(response)
        end if
      catch case NonFatal(e) => Future.failed(e)
    fetched
      .map: text =>
        Catalogue.parse(text) match
          case Left(p) => List(p.copy(language = Some(language)))
          case Right(catalogue) if catalogue.language != language =>
            problem(s"the file at ${Printable(url)} is a catalogue of ${catalogue.language.tag}")
          case Right(catalogue)                                   => catalogues.add(catalogue)
      .recover:
        case NonFatal(e) =>
          problem(s"cannot fetch ${Printable(url)}: ${Printable(String.valueOf(e.getMessage))}")
  end load

  // A cancellation's promise may reject; nothing waits for it, so it is caught here.
  private def quietly(promise: js.Dynamic): Unit =
    if !js.isUndefined(promise) && promise != null && !js.isUndefined(promise.selectDynamic("catch"))
    then
      promise.applyDynamic("catch")((_: js.Any) => ())
      ()

  // Lets go of a body that will not be read, so that its connection can close.
  private def cancel(response: js.Dynamic): Unit =
    try
      val body = response.body
      if !js.isUndefined(body) && body != null then
        quietly(body.cancel())
        ()
    catch case NonFatal(_) => ()

  private def tooLarge = new IllegalStateException(s"larger than $MaxBytes bytes")

  // A decoder that refuses malformed UTF-8, as the JVM's reader does, instead of replacing it.
  private def strictDecoder(): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8", js.Dynamic.literal(fatal = true))

  // With `stream`, a character split over two chunks waits for the next one; without it, a
  // character the bytes leave unfinished is malformed too.
  private def decode(
    decoder: js.Dynamic,
    bytes: js.Any,
    stream: Boolean,
  ): Try[String] =
    Try(decoder.decode(bytes, js.Dynamic.literal(stream = stream)).asInstanceOf[String])
      .orElse(Failure(new IllegalStateException("not UTF-8")))

  // The body as text, read chunk by chunk and given up as soon as it passes MaxBytes or holds a
  // byte that is not UTF-8.
  private def readBounded(
    response: js.Dynamic
  )(
    using ExecutionContext
  ): Future[String] =
    val body = response.body
    if js.isUndefined(body) || body == null then whole(response)
    else
      val reader                              = body.getReader()
      val decoder                             = strictDecoder()
      val out                                 = new StringBuilder
      def next(total: Double): Future[String] =
        reader
          .read()
          .asInstanceOf[js.Promise[js.Dynamic]]
          .toFuture
          .flatMap: chunk =>
            if chunk.done.asInstanceOf[Boolean] then
              Future.fromTry(decode(decoder, js.undefined, stream = false).map(out.append(_).result()))
            else
              val read = total + chunk.value.length.asInstanceOf[Double]
              Future
                .fromTry(
                  Either
                    .cond(read <= MaxBytes, (), tooLarge)
                    .toTry
                    .flatMap(_ => decode(decoder, chunk.value, stream = true))
                )
                .transformWith:
                  case Success(text) =>
                    out ++= text
                    next(read)
                  case Failure(e)    =>
                    quietly(reader.cancel())
                    Future.failed(e)
      next(0.0)
    end if

  end readBounded

  // A response without a body stream (a runtime or a polyfill without one) arrives whole, so it
  // cannot be dropped while it is read; it is counted and decoded as a streamed body is, so that
  // nothing over MaxBytes or malformed reaches the parser.
  private def whole(
    response: js.Dynamic
  )(
    using ExecutionContext
  ): Future[String] =
    response
      .arrayBuffer()
      .asInstanceOf[js.Promise[ArrayBuffer]]
      .toFuture
      .flatMap: buffer =>
        Future.fromTry(
          Either
            .cond(buffer.byteLength <= MaxBytes, (), tooLarge)
            .toTry
            .flatMap(_ => decode(strictDecoder(), new Uint8Array(buffer), stream = false))
        )

end CatalogueLoader
