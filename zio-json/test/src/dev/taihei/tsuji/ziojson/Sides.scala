package dev.taihei.tsuji.ziojson

import java.nio.charset.StandardCharsets.UTF_8

import scala.annotation.nowarn
import scala.util.control.NonFatal

import zio.json.*

import dev.taihei.tsuji.*
import dev.taihei.tsuji.TestJson.given
import dev.taihei.tsuji.ziojson.TsujiZioJson.given

import com.github.plokhotnyuk.jsoniter_scala.core.*

// The two ways to read the same bytes: with the core's jsoniter-scala codecs, and with this
// module's zio-json decoders. Each outcome is "refused", the value written again by the core's
// codec (so that two values compare by their JSON), or what was thrown, which no codec may do.
@nowarn("cat=deprecation")
object Sides:

  val Refused = "refused"

  def bytes(s: String): Array[Byte] = s.getBytes(UTF_8)

  private def threw(e: Throwable) = s"threw ${e.getClass.getName}"

  def core[A](
    input: Array[Byte]
  )(
    using codec: JsonValueCodec[A]
  ): String =
    try writeToString(readFromArray(input, TestJson.NoHexDump))
    catch
      case _: JsonReaderException => Refused
      case e: StackOverflowError  => threw(e)
      case NonFatal(e)            => threw(e)

  def module[A](
    input: Array[Byte]
  )(
    using decoder: JsonDecoder[A],
    codec: JsonValueCodec[A],
  ): String =
    try decoder.decodeJson(input).fold(_ => Refused, writeToString(_))
    catch
      case e: StackOverflowError => threw(e)
      case NonFatal(e)           => threw(e)

  /** Both sides on one input, for every type that reads a JSON object: (type, core, module). */
  def objects(
    input: Array[Byte]
  )(
    using Catalogues
  ): List[(String, String, String)] = List(
    ("message", core[Message](input), module[Message](input)),
    ("displayable", core[Displayable](input), module[Displayable](input)),
    ("text", core[Text](input), module[Text](input)),
    ("legacy", core[legacy.Message](input), module[legacy.Message](input)),
  )

  /** Both sides on one input, for the types that read a JSON string. */
  def values(input: Array[Byte]): List[(String, String, String)] = List(
    ("language", core[Language](input), module[Language](input)),
    ("key", core[MessageKey](input), module[MessageKey](input)),
  )

  /** Both sides on one input, read as an object of languages or of keys. */
  def keys(input: Array[Byte]): List[(String, String, String)] = List(
    ("language key", core[Map[Language, Int]](input), module[Map[Language, Int]](input)),
    ("key key", core[Map[MessageKey, Int]](input), module[Map[MessageKey, Int]](input)),
  )

end Sides
