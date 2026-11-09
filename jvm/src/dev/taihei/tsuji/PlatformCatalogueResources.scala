package dev.taihei.tsuji

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import scala.util.control.Exception.catching
import scala.util.control.NonFatal

// Catalogue files on the classpath, read with the class loader of the family that owns them.
private[tsuji] object PlatformCatalogueResources:

  /** Whether there is a classpath to read catalogues from. */
  val available: Boolean = true

  // UTF-8 takes at most four bytes per character. An Int, as readNBytes takes one: it stays well
  // below Int.MaxValue as long as a catalogue is at most some 500 million characters.
  private val MaxBytes = Catalogue.MaxLength * 4

  def path(prefix: String, tag: String): String = s"META-INF/taihei-tsuji/$prefix/$tag.json"

  /**
   * The family's catalogue file for the language tag: None when there is none, Left when it
   * cannot be read (too large, not UTF-8, an I/O error), Right with its text.
   */
  def read(family: MessageFamily, tag: String): Option[Either[String, String]] =
    val name   = path(family.prefix, tag)
    val loader = Option(
      family.getClass.getClassLoader
    ).orElse(Option(Thread.currentThread.getContextClassLoader))
    try
      loader.flatMap(l => Option(l.getResourceAsStream(name))).map: in =>
        try readBounded(in, name)
        finally in.close()
    catch
      case NonFatal(e) => Some(Left(s"cannot read $name: ${Printable(String.valueOf(e.getMessage))}"))

  /**
   * The text of `in`: Left when it holds more than MaxBytes bytes, of which it reads one past
   * the limit and no more, or when they are not UTF-8.
   */
  private[tsuji] def readBounded(in: InputStream, name: String): Either[String, String] =
    Right(in.readNBytes(MaxBytes + 1))
      .filterOrElse(_.length <= MaxBytes, s"$name is larger than $MaxBytes bytes")
      .flatMap(bytes => strictUtf8(bytes).toRight(s"$name is not UTF-8"))

  // The text of `bytes`, or None when they are not UTF-8; a malformed byte is never replaced.
  private def strictUtf8(bytes: Array[Byte]): Option[String] =
    catching(classOf[CharacterCodingException]).opt(
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    )

end PlatformCatalogueResources
