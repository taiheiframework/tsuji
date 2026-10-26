package dev.taihei.tsuji

import scala.util.control.NonFatal

import com.github.plokhotnyuk.jsoniter_scala.core.*

// The tests' way to write and read JSON with Tsuji's codecs: `value.toJson` and
// `json.fromJson[A]`, a decoding error as Left with its message. Anything but a
// JsonReaderException is let through, so that a test sees a codec that throws something else.
// Codecs for the plain values and maps the tests need, written by hand as the library's are.
object TestJson:

  extension [A](a: A)

    def toJson(
      using codec: JsonValueCodec[A]
    ): String = writeToString(a)

  extension (json: String)

    def fromJson[A](
      using codec: JsonValueCodec[A]
    ): Either[String, A] =
      try Right(readFromString(json, NoHexDump))
      catch case e: JsonReaderException => Left(e.getMessage)

  // jsoniter-scala appends a hex dump of the input to an error by default; without it, an error
  // holds only what Tsuji's codecs say, which the tests check.
  val NoHexDump: ReaderConfig = ReaderConfig.withAppendHexDumpToParseException(false)

  // As fromJson, but anything thrown is a Left naming it: for inputs that must never make a
  // decoder throw.
  def guarded[A](read: => Either[String, A]): Either[String, A] =
    try read
    catch case NonFatal(e) => Left(s"threw ${e.getClass.getName}")

  given JsonValueCodec[Int] = new JsonValueCodec[Int]:
    def decodeValue(in: JsonReader, default: Int): Int = in.readInt()
    def encodeValue(x: Int, out: JsonWriter): Unit     = out.writeVal(x)
    def nullValue: Int                                 = 0

  given JsonValueCodec[String] = new JsonValueCodec[String]:
    def decodeValue(in: JsonReader, default: String): String = in.readString(null)
    def encodeValue(x: String, out: JsonWriter): Unit        = out.writeVal(x)
    def nullValue: String                                    = null

  // An object of K to V, its fields in the map's order; a name given twice keeps its last value.
  given [K, V](
    using key: JsonKeyCodec[K],
    value: JsonValueCodec[V],
  ): JsonValueCodec[Map[K, V]] = new JsonValueCodec[Map[K, V]]:
    def decodeValue(in: JsonReader, default: Map[K, V]): Map[K, V] =
      if !in.isNextToken('{') then in.decodeError("expected an object")
      val read = Map.newBuilder[K, V]
      if !in.isNextToken('}') then
        in.rollbackToken()
        var more = true
        while more do
          val k = key.decodeKey(in)
          read += k -> value.decodeValue(in, value.nullValue)
          more = in.isNextToken(',')
        if !in.isCurrentToken('}') then in.objectEndOrCommaError()
      read.result()
    def encodeValue(x: Map[K, V], out: JsonWriter): Unit           =
      out.writeObjectStart()
      x.foreach: (k, v) =>
        key.encodeKey(k, out)
        value.encodeValue(v, out)
      out.writeObjectEnd()
    def nullValue: Map[K, V]                                       = null

  // A list of A, for composing Tsuji's codecs in a caller's own.
  given [A](
    using element: JsonValueCodec[A]
  ): JsonValueCodec[List[A]] = new JsonValueCodec[List[A]]:
    def decodeValue(in: JsonReader, default: List[A]): List[A] =
      if !in.isNextToken('[') then in.decodeError("expected an array")
      val read = List.newBuilder[A]
      if !in.isNextToken(']') then
        in.rollbackToken()
        var more = true
        while more do
          read += element.decodeValue(in, element.nullValue)
          more = in.isNextToken(',')
        if !in.isCurrentToken(']') then in.arrayEndOrCommaError()
      read.result()
    def encodeValue(x: List[A], out: JsonWriter): Unit         =
      out.writeArrayStart()
      x.foreach(element.encodeValue(_, out))
      out.writeArrayEnd()
    def nullValue: List[A]                                     = null

end TestJson
