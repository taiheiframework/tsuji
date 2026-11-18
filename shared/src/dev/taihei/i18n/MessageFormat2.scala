package dev.taihei.i18n

import scala.collection.mutable

object MessageFormat2:

  // AST types for MessageFormat 2.0
  sealed trait Expression
  case class Variable(name: String) extends Expression
  case class Literal(value: String) extends Expression

  case class FunctionAnnotation(
    expr: Expression,
    function: String,
    options: Map[String, String] = Map.empty)
    extends Expression

  sealed trait Pattern
  case class Text(value: String) extends Pattern
  case class Placeholder(expr: Expression) extends Pattern
  case class PatternSequence(patterns: List[Pattern]) extends Pattern

  sealed trait Declaration
  case class InputDeclaration(name: String, expr: Expression) extends Declaration
  case class LocalDeclaration(name: String, expr: Expression) extends Declaration

  case class MatchCase(keys: List[String], pattern: Pattern)
  case class MatchStatement(selectors: List[Expression], cases: List[MatchCase])

  case class Message(
    declarations: List[Declaration] = Nil,
    body: Either[Pattern, MatchStatement])

  // Declaration and match keywords
  private val KeywordInput = ".input"
  private val KeywordLet   = ".let"
  private val KeywordMatch = ".match"
  private val KeywordWhen  = "when"

  // Imperative recursive-descent parser for ICU MessageFormat 2.0.
  // Uses var/return intentionally for parser performance and simplicity.
  class Parser(template: String):
    private var pos = 0

    private def hasMore: Boolean = pos < template.length

    private def peek(): Option[Char] =
      if hasMore then Some(template(pos)) else None

    private def advance(): Char =
      val c = template(pos)
      pos += 1
      c

    private def skipWhitespace(): Unit =
      while hasMore && template(pos).isWhitespace do
        pos += 1

    // The first position from `from` that String.trim would keep (a char above U+0020). The
    // keyword checks look at the template in place instead of copying the rest of it per check,
    // which made parsing quadratic in the number of declarations and cases.
    private def firstNonBlank(from: Int): Int =
      var i = from
      while i < template.length && template(i) <= ' ' do i += 1
      i

    private def isPatternTerminator(c: Char): Boolean =
      c == '{' || c == '\n' || c == '|'

    private def parseIdentifier(): String =
      skipWhitespace()
      val start = pos
      while hasMore && (template(pos).isLetterOrDigit || template(pos) == '_') do
        pos += 1
      template.substring(start, pos)

    private def parseVariable(): Variable =
      advance() // skip $
      Variable(parseIdentifier())

    private def parseLiteral(): Literal =
      advance() // skip (
      val start = pos
      var depth = 1
      while hasMore && depth > 0 do
        template(pos) match
          case '(' => depth += 1
          case ')' => depth -= 1
          case _   =>
        if depth > 0 then pos += 1
      val value = template.substring(start, pos)
      if hasMore then advance() // skip closing )
      Literal(value.trim)

    private def parseFunctionOptions(): Map[String, String] =
      val options = mutable.Map[String, String]()
      skipWhitespace()

      while hasMore && peek().exists(_ != '}') do
        val startPos = pos
        val key      = parseIdentifier()
        skipWhitespace()
        if peek().contains('=') then
          advance() // skip =
          skipWhitespace()
          val value = if peek().contains('"') then
            advance() // skip opening quote
            val start = pos
            while hasMore && template(pos) != '"' do pos += 1
            val v     = template.substring(start, pos)
            if hasMore then advance() // skip closing quote
            v
          else
            parseIdentifier()
          if key.nonEmpty then
            options(key) = value
        skipWhitespace()
        // Prevent infinite loop - if we didn't advance, break
        if pos == startPos then
          if hasMore && template(pos) != '}' then
            pos += 1 // Force advance to avoid infinite loop
      end while

      options.toMap

    end parseFunctionOptions

    private def parseExpression(): Expression =
      skipWhitespace()
      val expr = parseSimpleExpression()
      skipWhitespace()
      if peek().contains(':') then
        advance() // skip :
        val function = parseIdentifier()
        val options  = parseFunctionOptions()
        FunctionAnnotation(expr, function, options)
      else
        expr

    private def parseSimpleExpression(): Expression =
      skipWhitespace()
      peek() match
        case Some('$') => parseVariable()
        case Some('(') => parseLiteral()
        case _         => Variable(parseIdentifier())

    private def parsePlaceholder(): Placeholder =
      advance() // skip {
      val expr = parseExpression()
      skipWhitespace()
      if peek().contains('}') then advance() // skip }
      Placeholder(expr)

    // Parses a pattern (text interspersed with {placeholders}).
    // When terminateAtNewline=true, stops at \n or | (used after declarations).
    // When terminateAtNewline=false, consumes the entire remaining input.
    private def parsePatternUntil(terminateAtNewline: Boolean): Pattern =
      val patterns = mutable.ListBuffer[Pattern]()

      def atTerminator: Boolean =
        if terminateAtNewline then peek().exists(c => c == '\n' || c == '|')
        else false

      while hasMore && !atTerminator do
        peek() match
          case Some('{') =>
            patterns += parsePlaceholder()
          case _         =>
            val start = pos
            while hasMore && template(pos) != '{' &&
              !(terminateAtNewline && isPatternTerminator(template(pos)))
            do
              pos += 1
            if pos > start then
              patterns += Text(template.substring(start, pos))
            else if hasMore then
              pos += 1 // Force advance to avoid infinite loop

      if patterns.isEmpty then Text("")
      else if patterns.size == 1 then patterns.head
      else PatternSequence(patterns.toList)

    end parsePatternUntil

    private def parseDeclaration(): Option[Declaration] =
      skipWhitespace()
      if !hasMore then return None

      if hasMore && template.startsWith(KeywordInput, pos) then
        pos += KeywordInput.length
        skipWhitespace()

        // Parse the declaration which may or may not be in braces
        val hadBrace = peek().contains('{')
        if hadBrace then advance()

        // Parse variable name
        skipWhitespace()
        val name = if peek().contains('$') then
          advance() // skip $
          parseIdentifier()
        else
          parseIdentifier()

        // Parse expression (which may include :function)
        skipWhitespace()
        val expr = if hadBrace && peek().contains(':') then
          // Parse function annotation
          advance() // skip :
          val function = parseIdentifier()
          val options  = parseFunctionOptions()
          skipWhitespace()
          if peek().contains('}') then advance() // skip closing brace
          FunctionAnnotation(Variable(name), function, options)
        else if hadBrace then
          // Just the variable itself
          skipWhitespace()
          if peek().contains('}') then advance()
          Variable(name)
        else
          // No brace, just variable
          Variable(name)

        Some(InputDeclaration(name, expr))
      else if hasMore && template.startsWith(KeywordLet, pos) then
        pos += KeywordLet.length
        skipWhitespace()

        val hadBrace = peek().contains('{')
        if hadBrace then advance()

        val name = if peek().contains('$') then
          advance()
          parseIdentifier()
        else
          parseIdentifier()

        skipWhitespace()
        if peek().contains('=') then advance()
        skipWhitespace()

        val expr = parseExpression()

        if hadBrace then
          skipWhitespace()
          if peek().contains('}') then advance()

        Some(LocalDeclaration(name, expr))
      else
        None

      end if

    end parseDeclaration

    private def parseMatchStatement(): MatchStatement =
      pos += KeywordMatch.length
      skipWhitespace()

      // Parse selectors (should be in braces)
      val selectors = mutable.ListBuffer[Expression]()

      // Look for expressions in braces
      while hasMore && peek().exists(_ != '\n') do
        skipWhitespace()
        if peek().contains('{') then
          advance() // skip {
          val expr = parseExpression()
          skipWhitespace()
          if peek().contains('}') then advance() // skip }
          selectors += expr
        else
          // No more selectors
          if peek().exists(_ != '\n') then
            // Advance to end of line
            while hasMore && template(pos) != '\n' do pos += 1

      if peek().contains('\n') then advance()

      // Parse cases
      val cases    = mutable.ListBuffer[MatchCase]()
      var continue = true
      while hasMore && continue do
        skipWhitespace()
        if hasMore && template.startsWith(KeywordWhen, pos) then
          pos += KeywordWhen.length
          skipWhitespace()

          // Parse keys
          val keys = mutable.ListBuffer[String]()
          while hasMore && peek().exists(c => c != '{' && c != '\n') do
            val startPos = pos
            // Parse match key (can be identifier, number, or special like * or =N)
            val key      = if peek().contains('*') then
              advance()
              "*"
            else if peek().contains('=') then
              advance() // skip =
              val num = parseIdentifier()
              "=" + num
            else
              // Parse regular identifier or number
              val id = parseIdentifier()
              if id.nonEmpty then id
              else
                // Try to parse a number directly
                val numStart = pos
                if peek().exists(c => c == '-' || c == '+') then advance()
                while hasMore && template(pos).isDigit do pos += 1
                if pos > numStart then template.substring(numStart, pos) else ""
            if key.nonEmpty then
              keys += key
            skipWhitespace()
            // Prevent infinite loop
            if pos == startPos then
              if hasMore && !peek().exists(c => c == '{' || c == '\n') then
                pos += 1
              else
                pos = template.length
          end while

          // Parse pattern
          skipWhitespace()
          if peek().contains('{') then advance()
          val patternStart = pos
          var depth        = 1
          while hasMore && depth > 0 do
            template(pos) match
              case '{' => depth += 1
              case '}' => depth -= 1
              case _   =>
            if depth > 0 then pos += 1
          val patternStr   = template.substring(patternStart, pos)
          if hasMore then advance() // skip closing }

          // Parse the pattern inline to avoid recursive parser creation
          val pattern = if patternStr.isEmpty then
            Text("")
          else
            // Simple pattern parsing - just handle text and placeholders
            val parts = mutable.ListBuffer[Pattern]()
            var i     = 0
            while i < patternStr.length do
              if patternStr(i) == '{' && i + 1 < patternStr.length && patternStr(i + 1) == '$'
              then
                // Found a placeholder
                val start = i
                i += 2 // skip {$
                while i < patternStr.length && patternStr(i) != '}' do i += 1
                if i < patternStr.length then
                  val varName = patternStr.substring(start + 2, i)
                  parts += Placeholder(Variable(varName))
                  i += 1 // skip }
              else
                // Regular text
                val textStart = i
                while i < patternStr.length && patternStr(i) != '{' do i += 1
                if i > textStart then
                  parts += Text(patternStr.substring(textStart, i))
                else if i < patternStr.length then
                  // We hit a { that isn't followed by $, treat it as text
                  i += 1 // Skip the {
                  parts += Text("{")
            end while
            if parts.isEmpty then Text("")
            else if parts.size == 1 then parts.head
            else PatternSequence(parts.toList)
          cases += MatchCase(keys.toList, pattern)

          skipWhitespace()
          if peek().contains('\n') then advance()
        else
          // No more when clauses
          continue = false
        end if
      end while

      MatchStatement(selectors.toList, cases.toList)

    end parseMatchStatement

    def parse(): Message =
      val declarations = mutable.ListBuffer[Declaration]()

      // Parse declarations
      var continueDeclarations = true
      while hasMore && continueDeclarations do
        val savedPos = pos
        parseDeclaration() match
          case Some(decl) =>
            declarations += decl
            skipWhitespace()
            if peek().contains('\n') then advance()
          case None       =>
            pos = savedPos
            continueDeclarations = false

      // Parse body (pattern or match statement)
      // Check for .match without skipping whitespace first (to preserve whitespace-only messages)
      val body =
        if hasMore && template.startsWith(KeywordMatch, firstNonBlank(pos)) then
          skipWhitespace()
          Right(parseMatchStatement())
        else if declarations.isEmpty && pos == 0 then
          Left(parsePatternUntil(terminateAtNewline = false))
        else
          Left(parsePatternUntil(terminateAtNewline = true))

      Message(declarations.toList, body)

    end parse

  end Parser

  // Runtime evaluation
  case class Context(
    variables: Map[String, Any] = Map.empty,
    locale: String = "en")

  /**
   * The template formatted with `args` in the locale given by the BCP 47 tag `locale`. A variable
   * without a value shows as `$name`. The text one template formats to is at most a million
   * characters; a template whose text would be longer comes back as it was written.
   */
  def format(
    template: String,
    args: Map[String, Any] = Map.empty,
    locale: String = "en",
  ): String =
    val parser  = new Parser(template)
    val message = parser.parse()
    val context = Context(args, locale)
    // A sequence stops as soon as it would pass the limit; a lone placeholder is checked here.
    try
      val text = evaluate(message, context)
      if text.length > MaxOutputLength then template else text
    catch case _: TooLong => template

  // A template can repeat a placeholder, and its arguments may come with the message from
  // outside the program: the text of one template stops here rather than grow with the product
  // of the two. A 4.x message formats exactly one template, so this also bounds a message.
  private[i18n] val MaxOutputLength = 1000000

  // Thrown past MaxOutputLength and caught in format; it carries no stack trace, being control
  // flow rather than an error.
  final private class TooLong extends RuntimeException("formatted text too long", null, false, false)

  private def evaluate(message: Message, initialContext: Context): String =
    // Process declarations to build context
    val context = message.declarations.foldLeft(initialContext) { (ctx, decl) =>
      decl match
        case InputDeclaration(name, expr) =>
          val value = evaluateExpression(expr, ctx)
          ctx.copy(variables = ctx.variables + (name -> value))
        case LocalDeclaration(name, expr) =>
          val value = evaluateExpression(expr, ctx)
          ctx.copy(variables = ctx.variables + (name -> value))
    }

    // Evaluate body
    message.body match
      case Left(pattern)    => evaluatePattern(pattern, context)
      case Right(matchStmt) => evaluateMatch(matchStmt, context)

  end evaluate

  private def evaluateExpression(expr: Expression, context: Context): Any =
    expr match
      case Variable(name) => context.variables.getOrElse(name, s"$$${name}")
      case Literal(value) => value
      case FunctionAnnotation(innerExpr, function, options) =>
        val value = evaluateExpression(innerExpr, context)
        applyFunction(value, function, options, context)

  private def applyFunction(
    value: Any,
    function: String,
    options: Map[String, String],
    context: Context,
  ): Any =
    function match
      case "number"   => formatNumber(value, options, context.locale)
      case "datetime" => formatDateTime(value, options, context.locale)
      case "plural"   => PluralSelector(value, context.locale)
      case "select"   => value
      case _          => value

  private def formatNumber(
    value: Any,
    options: Map[String, String],
    locale: String,
  ): String =
    numberOf(value) match
      case Some(num) => PlatformNumberFormatter.formatNumber(num, options.get("style"), locale)
      case None      => value.toString

  // The longest text read as a number: 1100 characters, enough for any Double written out in full.
  private val MaxNumberText = 1100

  // A value as a number, or None. The text is bounded and read with `toDoubleOption`, which checks
  // the syntax before it parses, so the read takes time linear in the text on both platforms.
  private[i18n] def numberOf(value: Any): Option[Double] =
    Option(value).map(_.toString).filter(_.length <= MaxNumberText).flatMap(_.toDoubleOption)

  private def formatDateTime(
    value: Any,
    options: Map[String, String],
    locale: String,
  ): String =
    PlatformDateTimeFormatter.formatDateTime(value, options, locale)

  case class PluralSelector(value: Any, locale: String)

  private def evaluatePattern(pattern: Pattern, context: Context): String =
    pattern match
      case Text(value)               => value
      case Placeholder(expr)         =>
        val value = evaluateExpression(expr, context)
        value match
          case PluralSelector(v, _) => v.toString
          case other                => other.toString
      case PatternSequence(patterns) =>
        // Checked before each piece is added, so the text never grows past the limit.
        val out = new StringBuilder
        patterns.foreach: p =>
          val piece = evaluatePattern(p, context)
          if piece.length > MaxOutputLength - out.length then throw new TooLong
          out ++= piece
        out.result()

  private def evaluateMatch(matchStmt: MatchStatement, context: Context): String =
    // Evaluate selectors; a plural selector's argument is read as a number once, and its
    // language worked out once, not once per variant
    val selectorValues = matchStmt.selectors.map(s => evaluateExpression(s, context)).map:
      case PluralSelector(value, locale) =>
        PluralCount(numberOf(value).map(_.toInt), languageOf(locale))
      case other                         => other

    // Find matching case
    val matchedCase = matchStmt.cases.find { mcase =>
      // Check if all keys match their corresponding selector values
      if mcase.keys.size == selectorValues.size then
        mcase.keys.zip(selectorValues).forall {
          case (key, PluralCount(count, language)) =>
            matchesPluralKey(count, key, language)
          case (key, value)                        =>
            key == value.toString || key == "*"
        }
      else
        false
    }

    matchedCase match
      case Some(mcase) => evaluatePattern(mcase.pattern, context)
      case None        => "[no match]"

  end evaluateMatch

  // A plural selector's value read as a number, None when it is no number, and the language of
  // its locale, which decides what `one` takes.
  final private case class PluralCount(count: Option[Int], locale: String)

  // The first subtag of a locale, in lower case. No language subtag is longer than eight letters,
  // so no more of the locale than that is read.
  private def languageOf(locale: String): String =
    locale.take(9).takeWhile(_ != '-').toLowerCase(java.util.Locale.ROOT)

  private def matchesPluralKey(
    count: Option[Int],
    key: String,
    language: String,
  ): Boolean =
    count match
      case Some(count) =>
        key match
          // Exact numeric matches (work for all locales)
          case "=0"                   => count == 0
          case "=1"                   => count == 1
          case "0"                    => count == 0
          case "1"                    => count == 1
          case "2"                    => count == 2
          // A key that is not a number in Int range (`=abc`, `99999999999`) matches no count.
          case s if s.startsWith("=") => s.drop(1).toIntOption.contains(count)
          case s if s.matches("\\d+") => s.toIntOption.contains(count)

          // Plural category matching (locale-specific)
          case "one" =>
            language match
              case "fr" => count == 0 || count == 1 // French (any region): one = 0 or 1
              case _    => count == 1               // English/Dutch: one = 1

          case "other" => true // "other" matches everything
          case "*"     => true // wildcard matches everything
          case _       => false
      case None        => key == "other" || key == "*"

end MessageFormat2
