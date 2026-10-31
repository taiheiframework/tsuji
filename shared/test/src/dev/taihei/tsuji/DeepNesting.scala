package dev.taihei.tsuji

import zio.json.*

// Inputs that nest arrays or objects far deeper than a message can, and the decodes that must
// refuse them, shared by DeepNestingSpec (both platforms) and the JVM's SmallStackSpec.
object DeepNesting:

  val Depths = List(5000, 100000)

  def arrays(n: Int): String  = "[" * n + "]" * n
  def objects(n: Int): String = "{\"a\":" * n + "1" + "}" * n

  // The nest of `n` levels in each place a reader reaches: the top level, under an unknown field
  // of a text, inside the arguments of an unknown key and of a known one.
  def inputs(n: Int): List[(String, String)] =
    List("arrays" -> arrays(n), "objects" -> objects(n)).flatMap: (shape, nest) =>
      List(
        s"$shape at the top level" -> nest,
        s"$shape under an unknown field of a text" -> s"""{"text":"x","language":"en","j":$nest}""",
        s"$shape inside the args of an unknown key" -> s"""{"key":"no.such","args":{"a":$nest},"text":"t"}""",
        s"$shape inside the args of a known key" -> s"""{"key":"order.greeting","args":{"name":$nest}}""",
        s"$shape under an unknown field of a 4.x message" ->
        s"""{"args":[],"translationTemplates":{"en":"x"},"j":$nest}""",
      )

  // Every decoder that reads a message or a text, each catching whatever it might throw: a
  // decoder whose contract is Either must return Left, never throw.
  def decodes(input: String): List[(String, Either[String, Any])] =
    given Catalogues = Catalogues(Seq(Orders))
    def guarded(decode: => Either[String, Any]): Either[String, Any] =
      try decode
      catch case t: Throwable => Right(s"threw ${t.getClass.getName}")
    List(
      "Message" -> guarded(input.fromJson[Message]),
      "Displayable" -> guarded(input.fromJson[Displayable]),
      "Text" -> guarded(input.fromJson[Text]),
    )

  // A refusal for depth, which names the bound, and not some other error the input also has.
  def refusedForDepth(result: Either[String, Any]): Boolean =
    result.left.exists(_.contains(s"deeper than ${BoundedJson.MaxDepth}"))

  // The decodes that did not refuse the input for its depth, by input and decoder: empty when all
  // did.
  def notRefused(n: Int): List[String] =
    for
      (place, input)  <- inputs(n)
      (which, result) <- decodes(input)
      if !refusedForDepth(result)
    yield s"$which, $place at depth $n: ${result.fold(_.take(100), _.toString.take(100))}"

end DeepNesting
