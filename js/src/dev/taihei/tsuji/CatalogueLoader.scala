package dev.taihei.tsuji

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.timers
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
 * the next language; this library does not prefetch. A server that answers slowly or not at all
 * keeps the load waiting, unless it is given a timeout:
 *
 * {{{
 * import scala.concurrent.duration.*
 * CatalogueLoader.load(summon[Catalogues], Language("de"), "/i18n/de.json", timeout = Some(10.seconds))
 * }}}
 */
object CatalogueLoader:

  // UTF-8 takes at most four bytes per character of a catalogue.
  private val MaxBytes = Catalogue.MaxLength.toLong * 4

  // The longest delay a JavaScript timer keeps; a longer one fires at once, so it is cut to this.
  private val MaxTimerMillis = Int.MaxValue.toDouble

  // What a load with a timeout gives up on: `expired` completes when the time is up, and `signal`
  // is the AbortSignal handed to fetch, where the runtime has AbortController.
  final private case class Cutoff(expired: Future[Unit], signal: Option[js.Dynamic])

  private val NoCutoff = Cutoff(Future.never, None)

  /**
   * Fetches the file at `url`, checks that it is a catalogue of `language`, and adds it. The
   * `Future` never fails: what went wrong, from the network to a template, is in the list.
   *
   * With a `timeout`, a file that has not arrived in full by then is a problem: the request is
   * aborted, its body is let go, and nothing is added. Without one, the load waits as long as the
   * server and the runtime let it. A timeout above the longest delay a JavaScript timer keeps
   * (about 24 days) counts as that delay.
   */
  def load(
    catalogues: Catalogues,
    language: Language,
    url: String,
    timeout: Option[FiniteDuration] = None,
  )(
    using ExecutionContext
  ): Future[List[CatalogueProblem]] =
    def problem(message: String) = List(CatalogueProblem(message, Some(language)))
    val fetched = timeout.fold(fetchText(url, NoCutoff))(within(_)(fetchText(url, _)))
    fetched
      .map: text =>
        Catalogue
          .refuseByteOrderMark(s"the file at ${Printable(url)}", text)
          .left
          .map(CatalogueProblem(_, Some(language)))
          .flatMap(Catalogue.parse(_).left.map(_.copy(language = Some(language))))
          .flatMap(catalogue =>
            Either.cond(
              catalogue.language == language,
              catalogue,
              CatalogueProblem(
                s"the file at ${Printable(url)} is a catalogue of ${catalogue.language.tag}",
                Some(language),
              ),
            )
          )
          .fold(List(_), catalogues.add)
      .recover:
        case NonFatal(e) =>
          problem(s"cannot fetch ${Printable(url)}: ${Printable(String.valueOf(e.getMessage))}")
  end load

  // Code compiled against 6.0.x calls this signature; package-private keeps it in the binary
  // and out of users' source, where the defaulted `timeout` above serves the same call.
  private[tsuji] def load(
    catalogues: Catalogues,
    language: Language,
    url: String,
  )(
    using ExecutionContext
  ): Future[List[CatalogueProblem]] = load(catalogues, language, url, None)

  // Gives up on the time limit first, so that the problem names it, then aborts the request
  // and lets the timer go once the load ends either way.
  private def within(
    limit: FiniteDuration
  )(
    read: Cutoff => Future[String]
  )(
    using ExecutionContext
  ): Future[String] =
    val expired    = Promise[Unit]()
    val result     = Promise[String]()
    val controller = abortController()
    val timer      = timers.setTimeout(limit.toMillis.max(0L).toDouble.min(MaxTimerMillis)):
      result.tryFailure(new IllegalStateException(s"timed out after $limit"))
      expired.trySuccess(())
      controller.foreach(c => Try(c.abort()))
    result.completeWith(read(Cutoff(expired.future, controller.map(_.signal))))
    result.future.andThen(_ => timers.clearTimeout(timer))

  end within

  // A runtime without AbortController still has the timer: the load gives up and the body is
  // let go, only the request itself is not aborted.
  private def abortController(): Option[js.Dynamic] =
    Option(js.Dynamic.global.AbortController)
      .filterNot(js.isUndefined)
      .flatMap(constructor => Try(js.Dynamic.newInstance(constructor)()).toOption)

  private def fetchText(
    url: String,
    cutoff: Cutoff,
  )(
    using ExecutionContext
  ): Future[String] =
    try
      val fetch = js.Dynamic.global.fetch
      if js.isUndefined(fetch) then Future.failed(new IllegalStateException("there is no fetch here"))
      else
        cutoff
          .signal
          .fold(fetch(url))(signal => fetch(url, js.Dynamic.literal(signal = signal)))
          .asInstanceOf[js.Promise[js.Dynamic]]
          .toFuture
          .flatMap: response =>
            // A response that arrives after the time is up, or a body still being read then, is
            // let go, so that its connection can close.
            cutoff.expired.foreach(_ => cancel(response))
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
            else readBounded(response, cutoff)
      end if
    catch case NonFatal(e) => Future.failed(e)

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

  // A decoder that refuses malformed UTF-8, as the JVM's reader does, instead of replacing it, and
  // keeps a leading byte order mark as U+FEFF, as the JVM's reader does, so that the shared check
  // refuses it on both. A decoder that ignores either option (an old polyfill) would replace
  // malformed bytes or drop the mark silently, so it is refused.
  private def strictDecoder(): Try[js.Dynamic] =
    Try(
      js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)(
        "utf-8",
        js.Dynamic.literal(fatal = true, ignoreBOM = true),
      )
    ).flatMap(decoder =>
      Either
        .cond(
          decoder.fatal.asInstanceOf[Any] == true && decoder.ignoreBOM.asInstanceOf[Any] == true,
          decoder,
          new IllegalStateException("there is no strict UTF-8 decoder here"),
        )
        .toTry
    )

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
    response: js.Dynamic,
    cutoff: Cutoff,
  )(
    using ExecutionContext
  ): Future[String] =
    val body = response.body
    if js.isUndefined(body) || body == null then whole(response)
    else
      // The decoder first: if it cannot be built, no reader holds the stream locked.
      Future
        .fromTry(strictDecoder())
        .flatMap: decoder =>
          val reader                              = body.getReader()
          // A cancelled reader ends the pending read, so a body that trickles stops here.
          cutoff.expired.foreach(_ => quietly(reader.cancel()))
          val out                                 = new StringBuilder
          def next(total: Double): Future[String] =
            reader
              .read()
              .asInstanceOf[js.Promise[js.Dynamic]]
              .toFuture
              .flatMap: chunk =>
                if chunk.done.asInstanceOf[Boolean] then
                  Future.fromTry(decode(
                    decoder,
                    js.undefined,
                    stream = false,
                  ).map(out.append(_).result()))
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
            .flatMap(_ => strictDecoder())
            .flatMap(decode(_, new Uint8Array(buffer), stream = false))
        )

end CatalogueLoader
