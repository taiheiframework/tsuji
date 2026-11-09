package dev.taihei.tsuji

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import scala.annotation.nowarn
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * An application's translations: the catalogues it has loaded, the message families it knows, and
 * the fallbacks it chose. Messages are shown through it ([[Displayable.localize]]), and messages
 * read from JSON look their key up in it.
 *
 * To show a message in language L, the first of these that has its key is used:
 *
 *  1. L exactly (`zh-Hant-TW`);
 *  1. L without its extensions and private use (`-u-...`, `-x-...`), then with its last subtag
 *     removed, step by step (`zh-Hant`, then `zh`);
 *  1. the fallbacks configured here for L, and for each of those steps, each walked the same way
 *     (`lb` to `de`, or `pt-BR` to `pt` to `es`);
 *  1. the family's source language, which always has it.
 *
 * Numbers and dates are always formatted for L, also when the template comes from a fallback;
 * a plural variant is chosen by the rules of the language the template is written in. A template that does not pass the checks, or cannot be formatted, is skipped, with a
 * problem in [[problems]]. Showing a message never throws.
 *
 * In each language a template from a catalogue file wins over an inline translation; in the
 * family's source language the template in the code wins.
 *
 * On the JVM a family's catalogue for a language is read from the classpath, at
 * `META-INF/taihei-tsuji/<prefix>/<language tag>.json`, the first time that language is needed.
 * In Scala.js the application adds catalogues itself, for example with `CatalogueLoader`; until
 * a language arrives its messages show through the fallback chain, and [[onAdded]] says when it
 * did.
 *
 * {{{
 * given Catalogues = Catalogues(Seq(Orders, Billing), fallbacks = Map(Language("lb") -> Seq(Language("de"))))
 * }}}
 *
 * It is safe to use from several threads.
 */
final class Catalogues private (
  fallbacks: Map[Language, Seq[Language]],
  fromResources: Boolean,
  registers: Boolean):

  import Catalogues.*

  private val state     = new AtomicReference(State.empty)
  private val listeners = new AtomicReference(Vector.empty[(Long, Language => Unit)])
  private val ids       = new AtomicLong(0)

  /**
   * Adds families, so that their messages can be read from JSON and their catalogue entries are
   * checked when they are added. A family whose message is shown is added by itself.
   */
  def register(families: MessageFamily*): Unit =
    families.foreach(ensureRegistered)

  /** The families this registry knows. */
  def families: List[MessageFamily] = state.get.families.values.toList.sortBy(_.prefix)

  /**
   * Adds a catalogue: its templates are used from now on, over those of an earlier catalogue in
   * the same language. The templates of known families are checked now; a template that fails is
   * dropped and its key falls back. Returns what was wrong, also kept in [[problems]], and tells
   * the [[onAdded]] listeners.
   */
  def add(catalogue: Catalogue): List[CatalogueProblem] =
    val found = merge(catalogue, overrideExisting = true)
    notifyAdded(catalogue.language)
    found

  /** The languages of the catalogues held, from files or the classpath. */
  def languages: Set[Language] = state.get.files.keySet

  /**
   * Calls `listener` with the language each time a catalogue is added, and returns the call that
   * stops it. A Laminar application turns this into a signal and re-renders.
   */
  def onAdded(listener: Language => Unit): () => Unit =
    val id = ids.incrementAndGet()
    listeners.updateAndGet(_ :+ (id -> listener))
    () =>
      listeners.updateAndGet(_.filterNot(_._1 == id)); ()

  /**
   * What was wrong with the catalogues and translations so far, the first 1000, each once: a
   * template that failed its checks; a template that could not be formatted, with the language it
   * came from and the kind of cause (the text it made was too long, or formatting threw); and an
   * [[onAdded]] listener that threw.
   */
  def problems: List[CatalogueProblem] = state.get.problems.toList

  /** The languages tried, in order, to show a message of a family in `source` to a reader of `language`. */
  def fallbackChain(language: Language, source: Language): List[Language] =
    chains.get.get(language -> source) match
      case Some(chain) => chain
      case None        =>
        val chain = computeChain(language, source)
        chains.updateAndGet: m =>
          (if m.size >= MaxChains then Map.empty else m).updated(language -> source, chain)
        chain

  // Chains are asked for on every message shown: they are kept, up to MaxChains, then dropped
  // all at once. A reader's language can come from a request header.
  private val chains = new AtomicReference(Map.empty[(Language, Language), List[Language]])

  private def computeChain(language: Language, source: Language): List[Language] =
    val seen                    = mutable.LinkedHashSet.empty[Language]
    def walk(l: Language): Unit =
      val steps = truncations(l).filter(seen.add)
      steps.foreach(step =>
        fallbacks.getOrElse(step, Nil).foreach(f => if seen.size < MaxChain then walk(f))
      )
    walk(language)
    (seen.toList.take(MaxChain).filterNot(_ == source)) :+ source

  // ---------------------------------------------------------------------------------------------

  private[tsuji] def definition(key: MessageKey): Option[MessageDefinition[?]] =
    state.get.definitions.get(key)

  // Whether the family's file for the language was looked for and is still remembered: a miss
  // the registry forgot is looked for again. The tests of MaxMisses read it; nothing else does.
  private[tsuji] def lookedFor(prefix: String, language: Language): Boolean =
    state.get.attempted(prefix -> language)

  // A nested message or text, within the budget of the message it is an argument of.
  @nowarn("cat=deprecation")
  private[tsuji] def renderWithin(
    d: Displayable,
    language: Language,
    budget: Budget,
  ): String = d match
    case m: Message        => render(m, language, budget)
    case t: Text           => t.value
    case l: legacy.Message => l.translateWithin(language, budget)
    case other             => other.localize(language)(
        using this
      )

  @nowarn("cat=deprecation")
  private[tsuji] def render(
    message: Message,
    language: Language,
    budget: Budget = Budget.fresh(),
  ): String =
    message.body match
      case Message.Body.Legacy(legacyMessage) => legacyMessage.translateWithin(language, budget)
      case Message.Body.Unresolved(_, text)   => text
      case Message.Body.Defined(d, args)      =>
        try
          ensureRegistered(d.family)
          val values = d.arguments
            .zip(args)
            .map:
              case ((name, arg), value) => name -> arg.toFormat(value, language, this, budget)
            .toMap
          // A spent budget fails every later language too: it is named once, for the first
          // template tried, which is tried even when the budget was spent on entry (by a 4.x
          // nested message, which reports nothing itself).
          fallbackChain(language, d.family.sourceLanguage).iterator
            .flatMap(candidate => template(d, candidate).map(candidate -> _))
            .zipWithIndex
            .takeWhile((_, tried) => tried == 0 || !budget.spent)
            .flatMap { case ((candidate, t), _) =>
              formatted(d, candidate, t, values, language, budget)
            }
            .nextOption()
            .getOrElse(d.template)
        catch
          case NonFatal(e) =>
            report(List(CatalogueProblem(MessageFormat2.failure(e), Some(language), Some(d.key))))
            d.template

  // A valid template that cannot be formatted falls back to the next language, as a missing one
  // does; the problem tells the two apart, and says whether a limit or a bug was the cause.
  private def formatted(
    d: MessageDefinition[?],
    candidate: Language,
    t: MessageFormat2.Message,
    values: Map[String, Any],
    language: Language,
    budget: Budget,
  ): Option[String] =
    MessageFormat2.formatted(t, values, language.tag, budget, Some(candidate.tag)) match
      case Right(text) => Some(text)
      case Left(cause) =>
        report(List(CatalogueProblem(cause, Some(candidate), Some(d.key))))
        None

  private def template(d: MessageDefinition[?], candidate: Language)
    : Option[MessageFormat2.Message] =
    if candidate == d.family.sourceLanguage then Some(d.parsedSource)
    else fromFile(d, candidate).orElse(inlineTranslation(d, candidate))

  private def inlineTranslation(d: MessageDefinition[?], language: Language)
    : Option[MessageFormat2.Message] =
    d.checkedTranslations.get(language).flatMap:
      case Right(parsed) => Some(parsed)
      case Left(errors)  =>
        report(errors.map(e => templateProblem(e, language, d.key)))
        None

  private def fromFile(d: MessageDefinition[?], language: Language)
    : Option[MessageFormat2.Message] =
    if fromResources && PlatformCatalogueResources.available &&
      !state.get.attempted((d.family.prefix, language))
    then loadResource(d.family, language)
    state.get.files.get(language).flatMap(_.get(d.key)).flatMap:
      case Entry.Valid(parsed) => Some(parsed)
      case Entry.Invalid       => None
      case Entry.Raw(t)        =>
        val (entry, found) = checked(t, d, language)
        update(s => s.copy(files = s.files.updatedWith(language)(_.map(_.updated(d.key, entry)))))
        report(found)
        entry match
          case Entry.Valid(parsed) => Some(parsed)
          case _                   => None

  end fromFile

  private def loadResource(family: MessageFamily, language: Language): Unit =
    update(s => s.copy(attempted = s.attempted + (family.prefix -> language)))
    PlatformCatalogueResources.read(family, language.tag) match
      case None                => forgetOldMisses(family.prefix -> language)
      case Some(Left(problem)) => report(List(CatalogueProblem(problem, Some(language))))
      case Some(Right(text))   =>
        Catalogue.parse(text) match
          case Left(problem) => report(List(problem.copy(language = Some(language))))
          case Right(catalogue) if catalogue.language != language =>
            report(List(CatalogueProblem(
              s"the file of ${family.prefix} for ${language.tag} says it is ${catalogue.language.tag}",
              Some(language),
            )))
          case Right(catalogue)                                   =>
            merge(catalogue.ofFamily(family.prefix), overrideExisting = false)
            notifyAdded(language)

  end loadResource

  // A file that exists is read once and kept. A miss is remembered too, but only the latest
  // MaxMisses of them: a reader's language can come from a request header, and there is no end
  // to the tags that have no file.
  private def forgetOldMisses(miss: (String, Language)): Unit =
    update: s =>
      val misses = s.misses :+ miss
      if misses.size <= MaxMisses then s.copy(misses = misses)
      else
        val (old, kept) = misses.splitAt(misses.size - MaxMisses)
        s.copy(misses = kept, attempted = s.attempted -- old)

  private def merge(catalogue: Catalogue, overrideExisting: Boolean): List[CatalogueProblem] =
    val known   = state.get.definitions
    val found   = List.newBuilder[CatalogueProblem]
    found ++= catalogue.problems
    val entries = catalogue.messages.map: (key, t) =>
      known.get(key) match
        case Some(d) =>
          val (entry, problems) = checked(t, d, catalogue.language)
          found ++= problems
          key -> entry
        case None    => key -> Entry.Raw(t)
    update: s =>
      val current = s.files.getOrElse(catalogue.language, Map.empty)
      val merged  = if overrideExisting then current ++ entries else entries ++ current
      s.copy(files = s.files.updated(catalogue.language, merged))
    val all     = found.result()
    report(all)
    all

  end merge

  private def checked(
    t: String,
    d: MessageDefinition[?],
    language: Language,
  ): (Entry, List[CatalogueProblem]) =
    val (errors, _) = MessageFormat2.check(t, d.kinds)
    if errors.isEmpty then (Entry.Valid(MessageFormat2.parse(t).message), Nil)
    else (Entry.Invalid, errors.map(e => templateProblem(e, language, d.key)))

  private def ensureRegistered(family: MessageFamily): Unit =
    if registers && !state.get.families.get(family.prefix).contains(family) then
      val clash = update: s =>
        if s.families.contains(family.prefix) then s
        else
          s.copy(
            families = s.families.updated(family.prefix, family),
            definitions = s.definitions ++ family.definitions.map(d => d.key -> d),
          )
      if clash.families.get(family.prefix).exists(_ ne family) then
        report(
          List(CatalogueProblem(s"two families have the prefix ${family.prefix}; the first is kept"))
        )

  private def update(f: State => State): State = state.updateAndGet(s => f(s))

  // The source-only registry keeps nothing, so that it holds no state between callers. A message
  // that fails to format fails each time it is shown: a problem already kept, or one past the
  // limit, changes nothing and is passed over without a write.
  private def report(found: List[CatalogueProblem]): Unit =
    val now = state.get.problems
    if registers && now.size < MaxProblems && found.exists(p => !now.contains(p)) then
      update: s =>
        val fresh = found.filterNot(s.problems.contains)
        s.copy(problems = (s.problems ++ fresh).take(MaxProblems))

  private def notifyAdded(language: Language): Unit =
    listeners.get.foreach: (_, listener) =>
      try listener(language)
      catch
        case NonFatal(e) =>
          report(List(CatalogueProblem(
            s"an onAdded listener threw ${e.getClass.getName}",
            Some(language),
          )))

end Catalogues

object Catalogues:

  /**
   * A registry that knows `families` (more are added when their messages are shown) and walks
   * `fallbacks` after the subtags of a language. On the JVM it reads catalogues from the
   * classpath when a language is first needed.
   */
  def apply(
    families: Seq[MessageFamily] = Nil,
    fallbacks: Map[Language, Seq[Language]] = Map.empty,
  ): Catalogues =
    val catalogues = new Catalogues(fallbacks, fromResources = true, registers = true)
    catalogues.register(families*)
    catalogues

  /**
   * What is wrong with the families and catalogues of an application, for its tests:
   *
   *  - every template, in the code and in each catalogue, passes the checks against the
   *    definition of its key, the types of your own arguments included;
   *  - every catalogue key belongs to a definition;
   *  - every language in `required` has every key, inline or in a catalogue; other languages may
   *    be partial and fall back;
   *  - no key of a `:plural` selector is one the template's language can never select, such as
   *    `few` in English ([[MessageFormat2.warnings]]); such a template is still shown, its
   *    variant only stays unused;
   *  - keys are unique across the families, and no language is given twice inline.
   *
   * Empty means all is well. It works with any test framework:
   * {{{
   * assertTrue(Catalogues.verify(Seq(Orders), catalogues, required = Set(Language.Dutch)).isEmpty)
   * }}}
   */
  def verify(
    families: Seq[MessageFamily],
    catalogues: Seq[Catalogue],
    required: Set[Language] = Set.empty,
  ): List[CatalogueProblem] =
    val found       = List.newBuilder[CatalogueProblem]
    val definitions = mutable.LinkedHashMap.empty[MessageKey, MessageDefinition[?]]

    families.groupBy(_.prefix).foreach: (prefix, fs) =>
      if fs.distinct.size > 1 then
        found += CatalogueProblem(s"${fs.distinct.size} families have the prefix $prefix")

    families.distinct.foreach: family =>
      family.definitions.foreach: d =>
        definitions.get(d.key) match
          case Some(other) if other ne d =>
            found += CatalogueProblem("defined twice", key = Some(d.key))
          case _                         => definitions(d.key) = d
        val (errors, _) = MessageFormat2.check(d.template, d.kinds)
        found ++= errors.map(e => templateProblem(e, family.sourceLanguage, d.key))
        found ++= unmatchable(d.template, family.sourceLanguage, d.key)
        d.translationPairs.groupBy(_._1).foreach: (language, pairs) =>
          if pairs.size > 1 then
            found += CatalogueProblem("translated inline twice", Some(language), Some(d.key))
          if language == family.sourceLanguage then
            found += CatalogueProblem(
              "an inline translation in the source language; the template is used",
              Some(language),
              Some(d.key),
            )
        d.translations.foreach: (language, t) =>
          found ++= MessageFormat2.check(t, d.kinds)._1.map(e => templateProblem(e, language, d.key))
          found ++= unmatchable(t, language, d.key)

    catalogues.foreach: catalogue =>
      found ++= catalogue.problems
      catalogue.messages.toList.sortBy(_._1.value).foreach: (key, t) =>
        definitions.get(key) match
          case None                                                     =>
            found += CatalogueProblem("no message has this key", Some(catalogue.language), Some(key))
          case Some(d) if d.family.sourceLanguage == catalogue.language => ()
          case Some(d)                                                  =>
            found ++= MessageFormat2.check(t, d.kinds)._1.map(e =>
              templateProblem(e, catalogue.language, key)
            )
            found ++= unmatchable(t, catalogue.language, key)

    required.toList.sortBy(_.tag).foreach: language =>
      val inFiles = catalogues.filter(_.language == language).flatMap(_.messages.keys).toSet
      definitions.values.foreach: d =>
        if d.family.sourceLanguage != language && !d.translations.contains(language) &&
          !inFiles(d.key)
        then
          found += CatalogueProblem("missing in a required language", Some(language), Some(d.key))

    found.result().distinct
  end verify

  // The text of a message in its source language, without any registry: what toString shows and
  // what the JSON encoder writes as "text".
  @nowarn("cat=deprecation")
  private[tsuji] def sourceText(message: Message, budget: Budget = Budget.fresh()): String =
    message.body match
      case Message.Body.Defined(d, _) => sourceOnly.render(message, d.family.sourceLanguage, budget)
      case Message.Body.Unresolved(_, text) => text
      case Message.Body.Legacy(l)           => l.toString

  // Reads no files and keeps no families: it only ever uses the code's own templates.
  private val sourceOnly = new Catalogues(Map.empty, fromResources = false, registers = false)

  // How many family and language pairs without a file a registry remembers.
  private val MaxMisses = 4096

  private val MaxChain    = 32
  private val MaxChains   = 1024
  private val MaxProblems = 1000

  // A plural key the language's rules never select: the template still formats and is kept, so
  // only verify reports it, for the tests that would rather know.
  private def unmatchable(
    template: String,
    language: Language,
    key: MessageKey,
  ): List[CatalogueProblem] =
    MessageFormat2.warnings(template, language).map(e => templateProblem(e, language, key))

  private def templateProblem(
    e: TemplateError,
    language: Language,
    key: MessageKey,
  ): CatalogueProblem =
    CatalogueProblem(s"${e.message} (at character ${e.position})", Some(language), Some(key))

  // The language, then with its last subtag removed, step by step.
  // Extensions and private use (from the first single-letter subtag on) say nothing about which
  // translation to take: after the exact tag, they are left out before subtags are removed. The
  // part before them is well formed whenever the tag is; should it not be, the walk stops at the
  // tag rather than build steps that are no tags.
  private[tsuji] def truncations(language: Language): List[Language] =
    val parts = language.tag.split('-').toList
    val plain = parts.takeWhile(_.length > 1)
    val base  = Option
      .when(plain.size == parts.size)(language)
      .orElse(Language.parse(plain.mkString("-")).toOption)
    (language :: base.fold(Nil)(truncationsOf)).distinct

  // `language` has no extension or private use, and in the grammar of such a tag every subtag
  // after the language is optional, so each prefix is a well-formed tag, already canonical. It is
  // built as it is rather than parsed again: a reader's tag can come from a request, and parsing
  // each prefix of a 255-character tag costs time quadratic in its length.
  private def truncationsOf(language: Language): List[Language] =
    val parts = language.tag.split('-').toList
    (parts.length to 1 by -1).toList.map(n => Language.fromCanonical(parts.take(n).mkString("-")))

  private enum Entry:
    case Raw(template: String)
    case Valid(parsed: MessageFormat2.Message)
    case Invalid

  final private case class State(
    families: Map[String, MessageFamily],
    definitions: Map[MessageKey, MessageDefinition[?]],
    files: Map[Language, Map[MessageKey, Entry]],
    attempted: Set[(String, Language)],
    misses: Vector[(String, Language)],
    problems: Vector[CatalogueProblem])

  private object State:
    val empty: State = State(Map.empty, Map.empty, Map.empty, Set.empty, Vector.empty, Vector.empty)

end Catalogues
