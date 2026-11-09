package dev.taihei.tsuji.ziojson

import scala.annotation.nowarn

import zio.json.*

import dev.taihei.tsuji.*

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

/**
 * zio-json codecs for Tsuji's types. They write the same bytes as the jsoniter-scala codecs in the
 * types' companions, and read through them, with the same bounds and the same errors' meaning: a
 * decoding error is zio-json's, a `Left` from `fromJson` and the like.
 *
 * {{{
 * import dev.taihei.tsuji.ziojson.TsujiZioJson.given
 *
 * given Catalogues = Catalogues(Seq(Orders))
 * Orders.cancelled.toJson
 * json.fromJson[Message]
 * }}}
 *
 * A codec object of your own makes them its own with `export TsujiZioJson.given`. Decoding a
 * [[dev.taihei.tsuji.Message]] or a [[dev.taihei.tsuji.Displayable]] needs a `Catalogues` in
 * scope, to read a message's arguments with their types; encoding needs none.
 *
 * Reading follows the JSON grammar strictly, as the core does. The value is read as it stands in
 * the input, and what follows it is the caller's: zio-json's `fromJson` ignores it. A value that
 * is already a zio-json AST (`.as[Message]`) is checked against the same bounds before it is
 * decoded, and refused when it stands for more than 4194304 values and characters, a part placed
 * in several places of the AST counted in each. Read from bytes, malformed UTF-8 reaches Tsuji as U+FFFD, the replacement character:
 * zio-json's reader of bytes replaces it before any decoder sees the text, where the core's codecs
 * refuse it. Values are always written compactly, whatever indentation the caller asks for.
 *
 * A value of Tsuji's types is read whole, members it does not use included, and held at a few
 * times its length while it is decoded; no string in it has a length bound of its own. Bound the
 * input you read: a limit on a request's body, or on what a stream may deliver.
 */
object TsujiZioJson:

  // Writing needs no catalogues, but the core's codec is one type for both directions.
  private lazy val NoCatalogues: Catalogues = Catalogues()

  private val language: Capture.Route[Language] =
    Capture.Route(summon[JsonValueCodec[Language]], Language.fromTree)

  private val messageKey: Capture.Route[MessageKey] =
    Capture.Route(summon[JsonValueCodec[MessageKey]], MessageKey.fromTree)

  private val text: Capture.Route[Text] = Capture.Route(summon[JsonValueCodec[Text]], Text.decode)

  private def message(
    using catalogues: Catalogues
  ): Capture.Route[Message] =
    Capture.Route(Message.codec(), Message.decode(_, MessageArg.Context(catalogues, 0)))

  private def displayable(
    using catalogues: Catalogues
  ): Capture.Route[Displayable] =
    Capture.Route(
      summon[JsonValueCodec[Displayable]],
      Displayable.decode(_, MessageArg.Context(catalogues, 0)),
    )

  @nowarn("cat=deprecation")
  private val legacyMessage: Capture.Route[legacy.Message] =
    Capture.Route(summon[JsonValueCodec[legacy.Message]], legacy.Message.fromTree)

  /** A language as a JSON string, its name for a built-in and its tag otherwise. */
  given JsonEncoder[Language] = Capture.encoder(language.codec)

  /** A language from a JSON string, in either form. */
  given JsonDecoder[Language] = Capture.decoder(language)

  /** Writes and reads a language. */
  given JsonCodec[Language] = JsonCodec(Capture.encoder(language.codec), Capture.decoder(language))

  /** A language as the name of a JSON object's field, in the forms of its value codec. */
  given JsonFieldEncoder[Language] = Capture.fieldEncoder(_.toString)

  /** A language from the name of a JSON object's field. */
  given JsonFieldDecoder[Language] = Capture.fieldDecoder(language)

  /** A key as the name of a JSON object's field. */
  given JsonFieldEncoder[MessageKey] = Capture.fieldEncoder(_.value)

  /** A key from the name of a JSON object's field, checked as `MessageKey.parse` does. */
  given JsonFieldDecoder[MessageKey] = Capture.fieldDecoder(messageKey)

  /** A key as a JSON string of its text. */
  given JsonEncoder[MessageKey] = Capture.encoder(messageKey.codec)

  /** A key from a JSON string, checked as `MessageKey.parse` does. */
  given JsonDecoder[MessageKey] = Capture.decoder(messageKey)

  /** A text as `{"text":...,"language":...}`. */
  given JsonEncoder[Text] = Capture.encoder(text.codec)

  /** A text from `{"text":...,"language":...}`. */
  given JsonDecoder[Text] = Capture.decoder(text)

  /** Writes and reads a text. */
  given JsonCodec[Text] = JsonCodec(Capture.encoder(text.codec), Capture.decoder(text))

  /**
   * Writes a message: its key and arguments and, with `withText`, the default, also its text in
   * the source language. As the core's `Message.codec(withText)` writes it.
   */
  def messageEncoder(withText: Boolean = true): JsonEncoder[Message] =
    Capture.encoder(Message.codec(withText)(
      using NoCatalogues
    ))

  /** Writes a message with its text in the source language: `messageEncoder()`. */
  given JsonEncoder[Message] = messageEncoder()

  /**
   * Reads a message, its key looked up in `catalogues` to read the arguments with their types; a
   * key it does not know reads as the message's `text`. The 4.x shape is read too.
   */
  given (
    using Catalogues
  ): JsonDecoder[Message] = Capture.decoder(message)

  /** Writes a message with its text, and reads one. */
  given (
    using Catalogues
  ): JsonCodec[Message] = JsonCodec(messageEncoder(), Capture.decoder(message))

  /** Writes a message, with its source-language text, or a text. */
  given JsonEncoder[Displayable] = Capture.encoder(displayable(
    using NoCatalogues
  ).codec)

  /** Reads a message, a text or a 4.x message, a message's key looked up in `catalogues`. */
  given (
    using Catalogues
  ): JsonDecoder[Displayable] = Capture.decoder(displayable)

  /** Writes and reads a deprecated 4.x message. */
  @nowarn("cat=deprecation")
  given JsonCodec[legacy.Message] =
    JsonCodec(Capture.encoder(legacyMessage.codec), Capture.decoder(legacyMessage))

end TsujiZioJson
