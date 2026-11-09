package dev.taihei.tsuji

import java.time.{LocalDate, LocalDateTime, LocalTime}

import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * A problem in a MessageFormat 2.0 template: the offset of the character where it was found, and
 * what is wrong there.
 */
final case class TemplateError private[tsuji] (position: Int, message: String):
  override def toString: String = s"at $position: $message"

/**
 * ICU MessageFormat 2.0 templates, in the dialect taihei-i18n has always read: placeholders
 * `{$name}`, functions `{$n :number}`, declarations `.input` and `.local` (or `.let`), and
 * `.match {$n :plural}` with variants `when one {...}` and a default `when * {...}`.
 *
 * {{{
 * MessageFormat2.format("Hello {$name}", Map("name" -> "Alice"), "en")   // Hello Alice
 * MessageFormat2.validate("Hello {$name")                                 // one TemplateError
 * }}}
 */
object MessageFormat2:

  /**
   * The template formatted with `args` in the locale given by the BCP 47 tag `locale`. A variable
   * without a value shows as `$name`; it never throws: a template that cannot be formatted, or
   * whose text would pass a million characters, comes back as it was written.
   */
  def format(
    template: String,
    args: Map[String, Any] = Map.empty,
    locale: String = "en",
  ): String =
    tryFormat(parse(template).message, args, locale).getOrElse(template)

  /**
   * What is wrong with the template, or nothing when it is valid: syntax, a function this library
   * does not know, a `.match` without a `*` variant, a variant with the wrong number of keys. Which
   * variables exist is not known here; a message definition checks that against its arguments.
   */
  def validate(template: String): List[TemplateError] =
    if template.length > MaxTemplateLength then
      List(TemplateError(0, s"the template is longer than $MaxTemplateLength characters"))
    else
      val parsed = parse(template)
      (parsed.errors ++ structureErrors(parsed.message)).take(MaxErrors).map(e =>
        e.copy(message = Printable(e.message, MaxErrorLength))
      )

  // A catalogue is input the application did not compile: a template longer than this is refused
  // before it is parsed. Parsing is linear and never recursive, so this bounds time and memory.
  private[tsuji] val MaxTemplateLength = 65536

  // Errors past this many tell nothing new and only cost memory. An error echoes parts of the
  // template, escaped and cut short, so that it can be logged as it is.
  private val MaxErrors      = 100
  private val MaxErrorLength = 200

  // ---------------------------------------------------------------------------------------------
  // AST
  // ---------------------------------------------------------------------------------------------

  sealed private[tsuji] trait Expression
  final private[tsuji] case class Variable(name: String, position: Int = 0) extends Expression
  final private[tsuji] case class Literal(value: String) extends Expression

  final private[tsuji] case class FunctionAnnotation(
    expr: Expression,
    function: String,
    options: Map[String, String] = Map.empty,
    position: Int = 0)
    extends Expression

  sealed private[tsuji] trait Pattern
  final private[tsuji] case class Text(value: String) extends Pattern
  final private[tsuji] case class Placeholder(expr: Expression) extends Pattern
  final private[tsuji] case class PatternSequence(patterns: List[Pattern]) extends Pattern

  sealed private[tsuji] trait Declaration:
    def name: String
    def expr: Expression
    def position: Int

  final private[tsuji] case class InputDeclaration(
    name: String,
    expr: Expression,
    position: Int = 0)
    extends Declaration

  final private[tsuji] case class LocalDeclaration(
    name: String,
    expr: Expression,
    position: Int = 0)
    extends Declaration

  final private[tsuji] case class MatchCase(
    keys: List[String],
    pattern: Pattern,
    position: Int = 0)

  final private[tsuji] case class MatchStatement(
    selectors: List[Expression],
    cases: List[MatchCase],
    position: Int = 0)

  final private[tsuji] case class Message(
    declarations: List[Declaration] = Nil,
    body: Either[Pattern, MatchStatement])

  final private[tsuji] case class Parsed(message: Message, errors: List[TemplateError])

  private[tsuji] def parse(template: String): Parsed =
    val parser = new Parser(template)
    val msg    = parser.parse()
    Parsed(msg, parser.errors.toList)

  // The functions this library formats. Anything else is an error in validate, and formats as
  // the value itself.
  private[tsuji] val Functions: Set[String] =
    Set("number", "integer", "datetime", "date", "time", "plural", "select", "string")

  // ---------------------------------------------------------------------------------------------
  // Parser
  // ---------------------------------------------------------------------------------------------

  private val KeywordInput = ".input"
  private val KeywordLet   = ".let"
  private val KeywordLocal = ".local"
  private val KeywordMatch = ".match"
  private val KeywordWhen  = "when"

  // An imperative recursive-descent parser that never recurses into itself: a variant's pattern is
  // parsed in place within its braces, and nothing nests deeper than a placeholder. It recovers
  // from every error the way earlier versions did, so format keeps its output for any template,
  // and records each recovery as a TemplateError for validate.
  final private[tsuji] class Parser(template: String):
    private var pos   = 0
    private var limit = template.length

    val errors: mutable.ListBuffer[TemplateError] = mutable.ListBuffer.empty

    private def error(at: Int, message: String): Unit =
      if errors.size < MaxErrors then errors += TemplateError(at, Printable(message, MaxErrorLength))

    private def hasMore: Boolean = pos < limit

    private def peek(): Option[Char] =
      if hasMore then Some(template(pos)) else None

    private def advance(): Char =
      val c = template(pos)
      pos += 1
      c

    private def skipWhitespace(): Unit =
      while hasMore && template(pos).isWhitespace do pos += 1

    // The first position from `from` that String.trim would keep (a char above U+0020).
    private def firstNonBlank(from: Int): Int =
      var i = from
      while i < limit && template(i) <= ' ' do i += 1
      i

    private def startsWithKeyword(keyword: String): Boolean =
      template.startsWith(keyword, pos) && pos + keyword.length <= limit

    private def parseIdentifier(): String =
      skipWhitespace()
      val start = pos
      while hasMore && (template(pos).isLetterOrDigit || template(pos) == '_') do pos += 1
      template.substring(start, pos)

    private def parseVariable(): Variable =
      val at = pos
      advance() // $
      val name = parseIdentifier()
      if name.isEmpty then error(at, "a variable needs a name after $")
      Variable(name, at)

    private def parseLiteral(): Literal =
      val at = pos
      advance() // (
      val start = pos
      var depth = 1
      while hasMore && depth > 0 do
        template(pos) match
          case '(' => depth += 1
          case ')' => depth -= 1
          case _   =>
        if depth > 0 then pos += 1
      val value = template.substring(start, pos)
      if hasMore then advance() // )
      else error(at, "a literal is not closed with )")
      Literal(value.trim)

    end parseLiteral

    private def parseFunctionOptions(): Map[String, String] =
      val options = mutable.LinkedHashMap.empty[String, String]
      skipWhitespace()
      while hasMore && peek().exists(_ != '}') do
        val startPos = pos
        val key      = parseIdentifier()
        if key.isEmpty then error(startPos, "expected an option name")
        skipWhitespace()
        if peek().contains('=') then
          advance()
          skipWhitespace()
          val valueAt = pos
          val value   =
            if peek().contains('"') then
              advance()
              val start = pos
              while hasMore && template(pos) != '"' do pos += 1
              val v     = template.substring(start, pos)
              if hasMore then advance()
              else error(valueAt, "a quoted option value is not closed")
              v
            else parseIdentifier()
          if value.isEmpty then error(valueAt, s"the option '$key' needs a value")
          if key.nonEmpty then options(key) = value
        else if key.nonEmpty then error(pos, s"the option '$key' needs = and a value")
        end if
        skipWhitespace()
        // Always move on: a character that starts nothing is skipped.
        if pos == startPos && hasMore && template(pos) != '}' then pos += 1
      end while
      options.toMap
    end parseFunctionOptions

    private def parseExpression(): Expression =
      skipWhitespace()
      val expr = parseSimpleExpression()
      skipWhitespace()
      if peek().contains(':') then
        val at       = pos
        advance()
        val function = parseIdentifier()
        if function.isEmpty then error(at, "a function needs a name after :")
        val options  = parseFunctionOptions()
        FunctionAnnotation(expr, function, options, at)
      else expr

    private def parseSimpleExpression(): Expression =
      skipWhitespace()
      peek() match
        case Some('$') => parseVariable()
        case Some('(') => parseLiteral()
        case _         =>
          // Earlier versions read a bare name as a variable; format still does, validate refuses.
          val at   = pos
          val name = parseIdentifier()
          error(
            at,
            if name.isEmpty then "expected $variable or (literal)"
            else s"write {$$$name} for a variable or {($name)} for text",
          )
          Variable(name, at)

    end parseSimpleExpression

    private def parsePlaceholder(): Placeholder =
      val at = pos
      advance() // {
      val expr = parseExpression()
      skipWhitespace()
      if peek().contains('}') then advance()
      else error(at, "a placeholder is not closed with }")
      Placeholder(expr)

    // Text and {placeholders} up to `limit`, or, when terminateAtNewline, up to a line break or |
    // (the pattern after declarations ends there in this dialect).
    private def parsePatternUntil(terminateAtNewline: Boolean): Pattern =
      val patterns = mutable.ListBuffer[Pattern]()

      def atTerminator: Boolean =
        terminateAtNewline && peek().exists(c => c == '\n' || c == '|')

      while hasMore && !atTerminator do
        peek() match
          case Some('{') =>
            patterns += parsePlaceholder()
          case _         =>
            val start = pos
            while hasMore && template(pos) != '{' &&
              !(terminateAtNewline && (template(pos) == '\n' || template(pos) == '|'))
            do pos += 1
            if pos > start then patterns += Text(template.substring(start, pos))
            else if hasMore then pos += 1

      if patterns.isEmpty then Text("")
      else if patterns.size == 1 then patterns.head
      else PatternSequence(patterns.toList)
    end parsePatternUntil

    private def parseDeclaredName(at: Int): String =
      skipWhitespace()
      if peek().contains('$') then advance()
      else error(pos, "expected $ before the declared name")
      val name = parseIdentifier()
      if name.isEmpty then error(at, "a declaration needs a name")
      name

    private def expectClosingBrace(at: Int): Unit =
      skipWhitespace()
      if peek().contains('}') then advance()
      else error(at, "a declaration is not closed with }")

    private def parseDeclaration(): Option[Declaration] =
      skipWhitespace()
      val at = pos
      if !hasMore then None
      else if startsWithKeyword(KeywordInput) then
        pos += KeywordInput.length
        skipWhitespace()
        val hadBrace = peek().contains('{')
        if hadBrace then advance() else error(pos, "expected { after .input")
        val name     = parseDeclaredName(at)
        skipWhitespace()
        val expr     =
          if hadBrace && peek().contains(':') then
            val fnAt     = pos
            advance()
            val function = parseIdentifier()
            if function.isEmpty then error(fnAt, "a function needs a name after :")
            val options  = parseFunctionOptions()
            expectClosingBrace(at)
            FunctionAnnotation(Variable(name, at), function, options, fnAt)
          else
            if hadBrace then expectClosingBrace(at)
            Variable(name, at)
        Some(InputDeclaration(name, expr, at))
      else if startsWithKeyword(KeywordLocal) || startsWithKeyword(KeywordLet) then
        pos += (if startsWithKeyword(KeywordLocal) then KeywordLocal.length else KeywordLet.length)
        skipWhitespace()
        val hadBrace = peek().contains('{')
        if hadBrace then advance()
        val name     = parseDeclaredName(at)
        skipWhitespace()
        if peek().contains('=') then advance()
        else error(pos, s"expected = after $$$name")
        skipWhitespace()
        val expr     =
          if !hadBrace && peek().contains('{') then
            // The MF2 form `.local $x = {expression}`.
            val exprAt = pos
            advance()
            val e      = parseExpression()
            expectClosingBrace(exprAt)
            e
          else parseExpression()
        if hadBrace then expectClosingBrace(at)
        Some(LocalDeclaration(name, expr, at))
      else None
      end if
    end parseDeclaration

    private def parseKey(): String =
      if peek().contains('*') then
        advance()
        "*"
      else if peek().contains('=') then
        advance()
        "=" + parseIdentifier()
      else
        val id = parseIdentifier()
        if id.nonEmpty then id
        else
          val numStart = pos
          if peek().exists(c => c == '-' || c == '+') then advance()
          while hasMore && template(pos).isDigit do pos += 1
          if pos > numStart then template.substring(numStart, pos) else ""

    private def parseMatchStatement(): MatchStatement =
      val at        = pos
      pos += KeywordMatch.length
      val selectors = mutable.ListBuffer[Expression]()
      var more      = true
      while more do
        skipWhitespace()
        if peek().contains('{') then
          val selectorAt = pos
          advance()
          val expr       = parseExpression()
          skipWhitespace()
          if peek().contains('}') then advance()
          else error(selectorAt, "a selector is not closed with }")
          selectors += expr
        else more = false
      if selectors.isEmpty then error(at, ".match needs at least one {selector}")

      val cases    = mutable.ListBuffer[MatchCase]()
      var continue = true
      while hasMore && continue do
        skipWhitespace()
        if hasMore && startsWithKeyword(KeywordWhen) then
          val caseAt = pos
          pos += KeywordWhen.length
          skipWhitespace()
          val keys   = mutable.ListBuffer[String]()
          while hasMore && peek().exists(c => c != '{' && c != '\n') do
            val startPos = pos
            val key      = parseKey()
            if key.nonEmpty then keys += key
            skipWhitespace()
            if pos == startPos then
              if hasMore && !peek().exists(c => c == '{' || c == '\n') then
                error(pos, s"'${template(pos)}' cannot start a key")
                pos += 1
              else pos = limit
          if keys.isEmpty then error(caseAt, "a variant needs at least one key")

          skipWhitespace()
          val pattern =
            if peek().contains('{') then
              val open  = pos
              advance()
              val start = pos
              var depth = 1
              var end   = pos
              while end < limit && depth > 0 do
                template(end) match
                  case '{' => depth += 1
                  case '}' => depth -= 1
                  case _   =>
                if depth > 0 then end += 1
              if depth > 0 then error(open, "a variant's pattern is not closed with }")
              // The pattern is parsed in place, within its braces.
              val outer = limit
              limit = end
              pos = start
              val p     = parsePatternUntil(terminateAtNewline = false)
              limit = outer
              pos = math.min(end + 1, limit)
              p
            else
              error(pos, "a variant needs a {pattern}")
              Text("")
          cases += MatchCase(keys.toList, pattern, caseAt)
        else continue = false
        end if
      end while
      MatchStatement(selectors.toList, cases.toList, at)
    end parseMatchStatement

    def parse(): Message =
      val declarations = mutable.ListBuffer[Declaration]()
      var more         = true
      while hasMore && more do
        val savedPos = pos
        parseDeclaration() match
          case Some(decl) => declarations += decl
          case None       =>
            pos = savedPos
            more = false

      val body =
        if hasMore && template.startsWith(KeywordMatch, firstNonBlank(pos)) then
          skipWhitespace()
          Right(parseMatchStatement())
        else if declarations.isEmpty && pos == 0 then
          Left(parsePatternUntil(terminateAtNewline = false))
        else
          skipWhitespace()
          Left(parsePatternUntil(terminateAtNewline = true))

      if firstNonBlank(pos) < limit then
        error(
          pos,
          body.fold(
            _ => "text after the pattern: after declarations a pattern ends at the line break",
            _ => "text after the last variant",
          ),
        )
      Message(declarations.toList, body)
    end parse

  end Parser

  // ---------------------------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------------------------

  private def expressions(message: Message): List[Expression] =
    def ofPattern(p: Pattern): List[Expression] = p match
      case Text(_)             => Nil
      case Placeholder(e)      => List(e)
      case PatternSequence(ps) => ps.flatMap(ofPattern)
    message.declarations.map(_.expr) ++ message.body.fold(
      ofPattern,
      m => m.selectors ++ m.cases.flatMap(c => ofPattern(c.pattern)),
    )

  private def structureErrors(message: Message): List[TemplateError] =
    val functions = expressions(message).collect:
      case FunctionAnnotation(_, f, _, at) if f.nonEmpty && !Functions(f) =>
        TemplateError(at, s"unknown function :$f")
    val matches   = message.body.toOption.toList.flatMap: m =>
      val arity    = m.selectors.size
      val counts   = m.cases.collect:
        case c if arity > 0 && c.keys.nonEmpty && c.keys.size != arity =>
          TemplateError(
            c.position,
            s"a variant needs $arity keys, one per selector, not ${c.keys.size}",
          )
      val fallback =
        if m.cases.exists(c => c.keys.nonEmpty && c.keys.forall(_ == "*")) then Nil
        else
          List(TemplateError(m.position, ".match needs a default variant with * for every selector"))
      counts ++ fallback
    functions ++ matches

  end structureErrors

  // What a function accepts and what it gives, per kind of value.
  private def accepts(function: String, kind: ArgKind): Boolean = kind match
    case ArgKind.Unknown => true
    case _               =>
      function match
        case "number" | "integer" | "plural" => kind == ArgKind.Number
        case "datetime" => kind == ArgKind.Date || kind == ArgKind.DateTime || kind == ArgKind.Time
        case "date"     => kind == ArgKind.Date || kind == ArgKind.DateTime
        case "time"     => kind == ArgKind.Time || kind == ArgKind.DateTime
        case _          => true

  private def kindAfter(function: String, kind: ArgKind): ArgKind = function match
    case "number" | "integer" | "plural" => ArgKind.Number
    case "datetime" | "date" | "time"    => ArgKind.Text
    case _                               => kind

  private def describe(kind: ArgKind): String = kind match
    case ArgKind.Text     => "text"
    case ArgKind.Number   => "a number"
    case ArgKind.Date     => "a date"
    case ArgKind.DateTime => "a date and time"
    case ArgKind.Time     => "a time"
    case ArgKind.Unknown  => "a value"

  /**
   * The template checked against a message's arguments: everything validate checks, plus that
   * each variable is an argument or declared before its use, and that each function fits the
   * kind of its value. The second list names the arguments the template does not use.
   */
  private[tsuji] def check(template: String, args: Map[String, ArgKind])
    : (List[TemplateError], List[String]) =
    if template.length > MaxTemplateLength then (validate(template), Nil)
    else
      val parsed = parse(template)
      val errors = mutable.ListBuffer.empty[TemplateError]
      errors ++= parsed.errors
      errors ++= structureErrors(parsed.message)
      val used   = mutable.Set.empty[String]
      val scope  = mutable.Map.empty[String, ArgKind] ++ args

      def kindOf(expr: Expression): ArgKind = expr match
        case Variable(name, at)                   =>
          if name.isEmpty then ArgKind.Unknown
          else
            scope.get(name) match
              case Some(k) =>
                used += name
                k
              case None    =>
                errors += TemplateError(at, s"$$$name is not an argument of this message")
                ArgKind.Unknown
        case Literal(value)                       =>
          if value.toDoubleOption.isDefined then ArgKind.Number else ArgKind.Text
        case FunctionAnnotation(inner, fn, _, at) =>
          val k = kindOf(inner)
          if Functions(fn) && !accepts(fn, k) then
            errors += TemplateError(at, s":$fn does not apply to ${describe(k)}")
          kindAfter(fn, k)

      parsed.message.declarations.foreach:
        case InputDeclaration(name, expr, at) =>
          val base = args.get(name) match
            case Some(k) =>
              used += name
              k
            case None    =>
              errors +=
                TemplateError(at, s".input names $$$name, which is not an argument of this message")
              ArgKind.Unknown
          scope(name) = expr match
            case FunctionAnnotation(_, fn, _, fnAt) =>
              if Functions(fn) && !accepts(fn, base) then
                errors += TemplateError(fnAt, s":$fn does not apply to ${describe(base)}")
              kindAfter(fn, base)
            case _                                  => base
        case LocalDeclaration(name, expr, at) =>
          val k = kindOf(expr)
          if scope.contains(name) then errors += TemplateError(at, s"$$$name is declared twice")
          scope(name) = k

      val rest      = parsed.message.body.fold(
        p => patternExpressions(p),
        m => m.selectors ++ m.cases.flatMap(c => patternExpressions(c.pattern)),
      )
      rest.foreach(kindOf)
      val printable = errors.toList.distinct.take(MaxErrors).map(e =>
        e.copy(message = Printable(e.message, MaxErrorLength))
      )
      (printable, args.keys.filterNot(used).toList.sorted)
    end if
  end check

  private def patternExpressions(p: Pattern): List[Expression] = p match
    case Text(_)             => Nil
    case Placeholder(e)      => List(e)
    case PatternSequence(ps) => ps.flatMap(patternExpressions)

  // ---------------------------------------------------------------------------------------------
  // Evaluation
  // ---------------------------------------------------------------------------------------------

  // `pluralLocale` is the language the template is written in, when that is not the reader's:
  // a plural category is grammar of the template's text, while numbers and dates are formatted
  // for the reader. An English template shown to a Russian reader says "21 items", not "21 item".
  final private[tsuji] case class Context(
    variables: Map[String, Any] = Map.empty,
    locale: String = "en",
    budget: Budget = Budget.fresh(),
    pluralLocale: Option[String] = None)

  // None when evaluating threw, which no input should make it do; the caller decides what to show.
  private[tsuji] def tryFormat(
    message: Message,
    args: Map[String, Any],
    locale: String,
    budget: Budget = Budget.fresh(),
  ): Option[String] =
    formatted(message, args, locale, budget).toOption

  // As tryFormat, with why it failed on the left, for a registry to report.
  private[tsuji] def formatted(
    message: Message,
    args: Map[String, Any],
    locale: String,
    budget: Budget,
    pluralLocale: Option[String] = None,
  ): Either[String, String] =
    try Right(evaluate(message, Context(args, locale, budget, pluralLocale)))
    catch case NonFatal(e) => Left(failure(e))

  // The kind of cause only: an exception's message can hold text from the input, and a problem
  // is meant to be logged as it is.
  private[tsuji] def failure(e: Throwable): String = e match
    case _: TooLong    => s"formatted text past $MaxOutputLength characters"
    case _: OverBudget =>
      s"formatted text past ${Budget.PerMessage} characters for one message, nested messages included"
    case other         => s"formatting threw ${other.getClass.getName}"

  private def evaluate(message: Message, initialContext: Context): String =
    val context = message.declarations.foldLeft(initialContext): (ctx, decl) =>
      ctx.copy(variables = ctx.variables + (decl.name -> evaluateExpression(decl.expr, ctx)))
    message.body match
      case Left(pattern)    => evaluatePattern(pattern, context)
      case Right(matchStmt) => evaluateMatch(matchStmt, context)

  private def evaluateExpression(expr: Expression, context: Context): Any =
    expr match
      case Variable(name, _) => context.variables.getOrElse(name, s"$$${name}")
      case Literal(value)    => value
      case FunctionAnnotation(innerExpr, function, options, _) =>
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
      case "integer"  => formatInteger(value)
      case "datetime" => PlatformDateTimeFormatter.formatDateTime(value, options, context.locale)
      case "date"     =>
        val date = value match
          case ldt: LocalDateTime => ldt.toLocalDate
          case other              => other
        date match
          case d: LocalDate =>
            PlatformDateTimeFormatter.formatDateTime(d, options - "timeStyle", context.locale)
          case other        => other.toString
      case "time"     =>
        val time = value match
          case ldt: LocalDateTime => ldt.toLocalTime
          case other              => other
        time match
          case t: LocalTime =>
            PlatformDateTimeFormatter.formatDateTime(t, options - "dateStyle", context.locale)
          case other        => other.toString
      case "plural"   => PluralSelector(value, context.pluralLocale.getOrElse(context.locale))
      case "string"   => show(value, context.locale)
      case _          => value

  private def formatNumber(
    value: Any,
    options: Map[String, String],
    locale: String,
  ): String =
    numberOf(value) match
      case Some(num) => PlatformNumberFormatter.formatNumber(num, options.get("style"), locale)
      case None      => value.toString

  // The longest text read as a number, the bound the JSON reader puts on a number.
  private val MaxNumberText = JsonTreeReader.MaxNumberLength

  // A value as a number, or None. The text is bounded and read with `toDoubleOption`, which checks
  // the syntax before it parses, so the read takes time linear in the text on both platforms.
  private[tsuji] def numberOf(value: Any): Option[Double] =
    Option(value).map(_.toString).filter(_.length <= MaxNumberText).flatMap(_.toDoubleOption)

  // Rounded half away from zero, digits only; a value that is no number shows as it is.
  private def formatInteger(value: Any): String =
    value.toString.toDoubleOption match
      case Some(d) if !d.isNaN && !d.isInfinite => math.round(d).toString
      case _                                    => value.toString

  final private[tsuji] case class PluralSelector(value: Any, locale: String)

  // How a value shows in a pattern: a language by its name in the reader's language.
  private[tsuji] def show(value: Any, locale: String): String = value match
    case PluralSelector(v, _) => v.toString
    case l: Language          => Language.nameIn(l, locale)
    case other                => other.toString

  private def evaluatePattern(pattern: Pattern, context: Context): String =
    val out                       = new StringBuilder
    def append(s: String): Unit   =
      if out.length + s.length > MaxOutputLength then throw new TooLong
      context.budget.charge(s.length)
      out ++= s
    def piece(p: Pattern): String = p match
      case Text(value)        => value
      case Placeholder(expr)  => show(evaluateExpression(expr, context), context.locale)
      case PatternSequence(_) => ""
    pattern match
      case PatternSequence(patterns) => patterns.foreach(p => append(piece(p)))
      case other                     => append(piece(other))
    out.result()

  // A template repeats its placeholders, and an argument may come from the wire: the text one
  // template makes is cut off here, and formatting falls back, rather than run out of memory.
  private[tsuji] val MaxOutputLength = 1000000

  final private[tsuji] class TooLong
    extends RuntimeException("formatted text too long", null, false, false)

  // A message and its nested messages together passed Budget.PerMessage.
  final private[tsuji] class OverBudget
    extends RuntimeException("over the budget of one message", null, false, false)

  // The elements of a list argument joined by ", ", held to the same limits.
  private[tsuji] def joined(parts: List[String], budget: Budget): String =
    val out = new StringBuilder
    parts.zipWithIndex.foreach: (p, i) =>
      val piece = if i == 0 then p else ", " + p
      if out.length + piece.length > MaxOutputLength then throw new TooLong
      budget.charge(piece.length)
      out ++= piece
    out.result()

  private def evaluateMatch(matchStmt: MatchStatement, context: Context): String =
    val values         = matchStmt.selectors.map(s => evaluateExpression(s, context))
    val pluralAt       = values.zipWithIndex.collect { case (_: PluralSelector, i) => i }
    val read           = pluralAt.take(MaxPluralSelectors).toSet
    val selectorValues = values.zipWithIndex.map((v, i) => selected(v, read(i)))
    val matchedCase    = matchStmt.cases.find: mcase =>
      mcase.keys.size == selectorValues.size &&
      mcase.keys.zip(selectorValues).forall:
        case (key, p: PluralChoice) => matchesPluralKey(p, key)
        case (key, l: Language)     => key == "*" || key == l.tag
        case (key, value)           => key == value.toString || key == "*"
    matchedCase match
      case Some(mcase) => evaluatePattern(mcase.pattern, context)
      case None        => "[no match]"

  // A plural selector's number and category, worked out once for all the variants.
  final private case class PluralChoice(operands: Option[Operands], category: String)

  // Reading a number costs up to a few hundred microseconds on Scala.js (a subnormal Double is a
  // 767-digit decimal), and a 64K template from a catalogue can name thousands of selectors. Past
  // this many plural selectors in one `.match`, a selector counts as no number, so only `other`
  // and `*` match it: no template a person writes comes near it.
  private[tsuji] val MaxPluralSelectors = 64

  private def selected(value: Any, read: Boolean): Any = value match
    case PluralSelector(v, locale) =>
      val operands = Option.when(read)(Operands.of(v)).flatten
      PluralChoice(operands, operands.fold("other")(PluralRules.category(locale, _)))
    case other                     => other

  // Variants are tried in the order the template gives them, as before CLDR's rules: an exact
  // key matches the number it names, a category key the category CLDR gives the number in the
  // template's language (Context.pluralLocale, else the locale), and `other` and `*` any value, as
  // the catch-all of ICU MessageFormat.
  private def matchesPluralKey(choice: PluralChoice, key: String): Boolean =
    key match
      case "other" | "*" => true
      case _             =>
        exactKey(key) match
          case Some(exact) => choice.operands.exists(_.equalsExactly(exact))
          case None        => choice.operands.isDefined && choice.category == key

  // The number an exact key names (`=0`, `0`, `=+1`), in Int range as it has always been; a key
  // such as `=abc`, `-1` or `99999999999` names none and matches nothing.
  private def exactKey(key: String): Option[BigInt] =
    val number   = key.stripPrefix("=")
    val unsigned = number.stripPrefix("-").stripPrefix("+")
    Option
      .when(
        (key.startsWith("=") || number == unsigned) && unsigned.nonEmpty &&
        unsigned.forall(c => c >= '0' && c <= '9')
      )(number)
      .flatMap(_.toIntOption)
      .map(BigInt(_))

  /**
   * What `validate` does not refuse, but a reader of `language` would never see: a key of a
   * `:plural` selector that cannot match a number there, because CLDR gives the language no such
   * category (`few` in English) or because it is no category and no number at all (`ones`).
   * These templates format, so 6.0's checks still pass them; the variant only stays unused.
   * Empty when every key can match.
   *
   * {{{
   * MessageFormat2.warnings(".match {$n :plural} when few {a few} when * {more}", Language.English)
   * // List(at 20: the plural key few never matches in en, whose categories are one, other)
   * }}}
   */
  def warnings(template: String, language: Language): List[TemplateError] =
    Option
      .when(template.length <= MaxTemplateLength)(parse(template).message)
      .toList
      .flatMap(m => pluralWarnings(m, language.tag))
      .distinct
      .take(MaxErrors)
      .map(e => e.copy(message = Printable(e.message, MaxErrorLength)))

  private def pluralWarnings(message: Message, locale: String): List[TemplateError] =
    val declared                          = message.declarations.map(d => d.name -> d.expr).toMap
    // One step through a declaration, never more: declarations can name each other in a circle.
    def annotated(e: Expression): Boolean = e match
      case FunctionAnnotation(_, "plural", _, _) => true
      case _                                     => false
    def plural(e: Expression): Boolean    = e match
      case Variable(name, _) => declared.get(name).exists(annotated)
      case other             => annotated(other)
    val categories                        = PluralRules.categories(locale)
    message.body.toOption.toList.flatMap: m =>
      val pluralAt = m.selectors.zipWithIndex.collect { case (s, i) if plural(s) => i }.toSet
      m.cases.flatMap: c =>
        c.keys.zipWithIndex.collect:
          case (key, i) if pluralAt(i) && neverMatches(key, categories) =>
            TemplateError(c.position, neverMessage(key, locale, categories))

  end pluralWarnings

  private def neverMatches(key: String, categories: List[String]): Boolean =
    key != "*" && !categories.contains(key) && exactKey(key).isEmpty

  private def neverMessage(
    key: String,
    locale: String,
    categories: List[String],
  ): String =
    key match
      case k if PluralRules.Categories.contains(k)       =>
        s"the plural key $k never matches in $locale, whose categories are ${categories.mkString(", ")}"
      case k if k.startsWith("=") || k.exists(_.isDigit) =>
        s"the plural key $k is no exact number key in Int range, so it never matches"
      case k                                             =>
        s"the plural key $k is no plural category (${PluralRules.Categories.mkString(", ")}) " +
        "and no number, so it never matches"

end MessageFormat2

/**
 * How many characters one message may still produce, its nested messages, list elements and
 * fallback attempts included. Arguments from the wire can be repeated by a template; past the
 * budget, formatting stops and the message shows its own template.
 */
final private[tsuji] class Budget private (private var left: Long):

  def charge(n: Int): Unit =
    left -= n
    if left < 0 then throw new MessageFormat2.OverBudget

  def spent: Boolean = left < 0

private[tsuji] object Budget:

  val PerMessage: Long = 4L * MessageFormat2.MaxOutputLength

  def fresh(): Budget = new Budget(PerMessage)

/** The kind of value an argument gives a template, which decides the functions that apply to it. */
private[tsuji] enum ArgKind:

  case Text, Number, Date, DateTime, Time, Unknown
