package dev.taihei.tsuji

import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * The stable key of a message: its family's prefix and its name, joined by dots
 * (`order.shipped`, `taihei.validation.nonEmpty`). Each part starts with an ASCII letter and holds
 * letters, digits and `_`; a key has at most 255 characters.
 *
 * A key is a contract between the writer and the reader of a message: never reuse one for a
 * different set of arguments.
 */
opaque type MessageKey = String

object MessageKey:

  private val MaxLength = 255

  /** The key for `value`, or why it is not one. */
  def parse(value: String): Either[String, MessageKey] =
    if value.length > MaxLength then Left(s"Not a message key: longer than $MaxLength characters")
    else if isPrefix(value) && value.contains('.') then Right(value)
    else Left(s"Not a message key: '${Printable(value)}'")

  extension (key: MessageKey)
    /** The key as text. */
    def value: String = key

  // One or more parts joined by dots: a family prefix, or a whole key.
  private[tsuji] def isPrefix(value: String): Boolean =
    value.length <= MaxLength && value.nonEmpty && value.split("\\.", -1).forall(isPart)

  private[tsuji] def isPart(part: String): Boolean =
    part.nonEmpty && isLetter(part.head) &&
    part.forall(c => isLetter(c) || (c >= '0' && c <= '9') || c == '_')

  private def isLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  // For keys the library builds from a checked prefix and a checked name.
  private[tsuji] def trusted(value: String): MessageKey = value

  // The key in a JSON tree: a string that parses as one.
  private[tsuji] def fromTree(json: JsonTree): Either[String, MessageKey] = json match
    case JsonTree.Str(s) => parse(s)
    case _               => Left("a message key is a string")

  /** A key travels as a JSON string of its text; reading checks it, as [[parse]] does. */
  given JsonValueCodec[MessageKey] = new JsonValueCodec[MessageKey]:
    def decodeValue(in: JsonReader, default: MessageKey): MessageKey =
      parse(in.readString(null)).fold(in.decodeError, identity)
    def encodeValue(x: MessageKey, out: JsonWriter): Unit            = out.writeVal(x)
    def nullValue: MessageKey                                        = null.asInstanceOf[MessageKey]

  /** A key as the name of a JSON object's field. */
  given JsonKeyCodec[MessageKey] = new JsonKeyCodec[MessageKey]:
    def decodeKey(in: JsonReader): MessageKey           =
      parse(in.readKeyAsString()).fold(in.decodeError, identity)
    def encodeKey(x: MessageKey, out: JsonWriter): Unit = out.writeKey(x)

  /** Keys in the order of their text. */
  given Ordering[MessageKey] = Ordering.String

end MessageKey

// Text from input the application did not write, as it may safely appear in an error: anything but
// printable ASCII as \uXXXX, and cut short after 80 characters.
private[tsuji] object Printable:

  def apply(s: String, max: Int = 80): String =
    val cut = if s.length > max then s.take(max) + "..." else s
    cut.flatMap(c => if c >= ' ' && c <= '~' then c.toString else "\\u%04x".format(c.toInt))
