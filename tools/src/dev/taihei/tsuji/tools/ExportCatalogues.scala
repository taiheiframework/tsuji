package dev.taihei.tsuji.tools

import java.io.{File, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import dev.taihei.tsuji.*

/**
 * Writes the catalogues of the message families on the classpath, one merged file per language,
 * for a browser application to serve and for translators to translate:
 *
 * {{{
 * ExportCatalogues --out <directory> [--scan <package>]... [--family <object>]...
 * }}}
 *
 * `--scan com.acme` finds every family object in that package and below on the classpath, in
 * directories and jars; `--family com.acme.Orders` names one. Families of libraries, such as
 * `dev.taihei.tsuji.CoreMessages`, are found the same way. For each language that has a template,
 * `<directory>/<tag>.json` holds every family's messages in that language: the source templates
 * for the families written in it (the file to hand to translators), else the catalogue files on
 * the classpath over the inline translations. Templates that fail their checks are left out.
 *
 * Problems are printed and make the exit status 1; a usage error makes it 2. Run it as an
 * ordinary `runMain` step of the application's build, forked in sbt so that the classpath is the
 * application's.
 */
object ExportCatalogues:

  /** Runs the export with the command line's arguments and exits with its status. */
  def main(args: Array[String]): Unit =
    val status = run(args.toList, System.out, System.err)
    if status != 0 then sys.exit(status)

  private val Usage = usage(
    Option(getClass.getPackage).flatMap(p => Option(p.getImplementationVersion))
  )

  // The version comes from the jar's manifest, so a run from compiled classes names none.
  private[tools] def usage(version: Option[String]): String =
    val line =
      "usage: ExportCatalogues --out <directory> [--scan <package>]... [--family <object>]..."
    version.fold(line)(v => s"taihei-tsuji-tools $v\n$line")

  final private case class Options(
    out: Option[Path],
    scan: List[String],
    families: List[String])

  private def parse(args: List[String], acc: Options): Either[String, Options] = args match
    case Nil                        => Right(acc)
    case "--out" :: dir :: rest     => parse(rest, acc.copy(out = Some(Paths.get(dir))))
    case "--scan" :: pkg :: rest    =>
      // A package name, never the whole class path, nor a path out of it.
      if MessageKey.isPrefix(pkg) then parse(rest, acc.copy(scan = acc.scan :+ pkg))
      else Left(s"not a package name: '${Printable(pkg)}'")
    case "--family" :: name :: rest => parse(rest, acc.copy(families = acc.families :+ name))
    case other :: _                 => Left(s"unknown argument: $other")

  /** Exports as `main` does, printing to `out` and `err`; returns the exit status. */
  def run(
    args: List[String],
    out: PrintStream,
    err: PrintStream,
  ): Int =
    parse(args, Options(None, Nil, Nil)) match
      case Left(e)                                =>
        err.println(e); err.println(Usage); 2
      case Right(Options(None, _, _))             =>
        err.println(Usage); 2
      case Right(Options(_, Nil, Nil))            =>
        err.println("name at least one --scan or --family"); err.println(Usage); 2
      case Right(Options(Some(dir), scan, named)) =>
        val loader   =
          Option(Thread.currentThread.getContextClassLoader).getOrElse(getClass.getClassLoader)
        val entries  = Classpath.entries(loader)
        val found    = named.map(n => n -> Classpath.family(n, loader)) ++
          scan.flatMap(p => Classpath.familiesIn(p, entries, loader).map(p -> _))
        val missing  = found.collect { case (n, Left(e)) => s"$n: $e" }
        val families = found.collect { case (_, Right(f)) => f }.distinct.sortBy(_.prefix)
        missing.foreach(err.println)
        if families.isEmpty then
          err.println("no message family found"); 2
        else
          val status =
            try write(dir, families, entries, out, err)
            catch
              case NonFatal(e) =>
                err.println(s"cannot write to $dir: ${describe(e)}")
                1
          if missing.nonEmpty then 1 else status

  // An exception's message, or its class's name when it has none, never "null".
  private[tools] def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getName)

  // "0 messages", "1 message", "2 messages".
  private[tools] def countLine(
    count: Int,
    language: Language,
    file: Path,
  ): String =
    val noun = if count == 1 then "message" else "messages"
    s"$count $noun in ${language.tag}: $file"

  private def write(
    dir: Path,
    families: List[MessageFamily],
    entries: List[File],
    out: PrintStream,
    err: PrintStream,
  ): Int =
    val fileLanguages =
      families.map(f => f -> Classpath.catalogueLanguages(f.prefix, entries)).toMap
    val read          =
      for
        f <- families
        l <- fileLanguages(f)
        r <- ClasspathCatalogues.read(f, l).toList
      yield f -> r
    val unreadable    = read.collect { case (_, Left(p)) => p }
    val catalogues    = read.collect { case (f, Right(c)) => f -> c }
    val problems      = (unreadable ++ Catalogues.verify(families, catalogues.map(_._2))).distinct

    val languages =
      (families.map(_.sourceLanguage) ++
      families.flatMap(_.definitions.flatMap(_.translations.keys)) ++
      catalogues.map(_._2.language)).distinct.sortBy(_.tag)

    val bad = problems.flatMap(p => p.language.zip(p.key)).toSet

    Files.createDirectories(dir)
    languages.foreach: language =>
      val messages = families.flatMap: family =>
        if family.sourceLanguage == language then family.definitions.map(d => d.key -> d.template)
        else
          val inline = family.definitions.flatMap(d => d.translations.get(language).map(d.key -> _))
          val files  = catalogues.collect {
            case (f, c) if (f eq family) && c.language == language => c.messages.toList
          }.flatten
          (inline.toMap ++ files.toMap).toList.filterNot((k, _) => bad((language, k)))
      val file     = dir.resolve(s"${language.tag}.json")
      Files.writeString(
        file,
        Catalogue(language, messages.toMap).toJsonPretty + "\n",
        StandardCharsets.UTF_8,
      )
      out.println(countLine(messages.size, language, file))

    problems.foreach(p => err.println(s"problem: $p"))
    if problems.isEmpty then 0 else 1
  end write

end ExportCatalogues

// Finding families and catalogue files on the classpath without a dependency: the class path's
// directories and jars, walked once.
private object Classpath:

  def entries(loader: ClassLoader): List[File] =
    val fromProperty =
      Option(System.getProperty("java.class.path")).toList.flatMap(_.split(File.pathSeparator))
    val fromLoader   = loader match
      case u: java.net.URLClassLoader =>
        u.getURLs.toList.flatMap(url => scala.util.Try(Paths.get(url.toURI).toString).toOption)
      case _                          => Nil
    (fromProperty ++ fromLoader).filter(_.nonEmpty).distinct.map(new File(_)).filter(_.exists)

  // The names of the files under `prefix/` (a path with /) in every entry.
  private def names(entries: List[File], prefix: String): List[String] =
    entries.flatMap: entry =>
      try
        if entry.isDirectory then
          val root = entry.toPath.resolve(prefix)
          if !Files.isDirectory(root) then Nil
          else
            val stream = Files.walk(root)
            try stream.iterator.asScala.filter(Files.isRegularFile(_)).map(p =>
                entry.toPath.relativize(p).toString.replace(File.separatorChar, '/')
              ).toList
            finally stream.close()
        else if entry.getName.endsWith(".jar") then
          val jar = new JarFile(entry)
          try jar.entries.asScala.map(_.getName).filter(n =>
              n.startsWith(prefix) && !n.endsWith("/")
            ).toList
          finally jar.close()
        else Nil
      catch case NonFatal(_) => Nil
    .distinct

  def catalogueLanguages(prefix: String, entries: List[File]): List[Language] =
    names(entries, s"META-INF/taihei-tsuji/$prefix/")
      .map(_.stripPrefix(s"META-INF/taihei-tsuji/$prefix/"))
      .filter(n => n.endsWith(".json") && !n.contains('/'))
      .flatMap(n => Language.parse(n.stripSuffix(".json")).toOption)
      .distinct

  // Every family object in the package and below; one that fails to load is a Left, so that the
  // export reports it instead of leaving its messages out without a word.
  def familiesIn(
    pkg: String,
    entries: List[File],
    loader: ClassLoader,
  ): List[Either[String, MessageFamily]] =
    names(entries, pkg.replace('.', '/') + "/")
      .filter(_.endsWith("$.class"))
      .map(_.stripSuffix(".class").replace('/', '.'))
      .flatMap: binaryName =>
        instance(binaryName, loader) match
          case Left(e)        => List(Left(s"${objectName(binaryName)}: $e"))
          case Right(Some(f)) => List(Right(f))
          case Right(None)    => Nil

  // The class a.b.C$D$ is the object `a.b.C.D`, the name --family takes.
  private def objectName(binaryName: String): String =
    binaryName.stripSuffix("$").replace('$', '.')

  // An object `a.b.C.D` is the class a.b.C$D$; try the dots from the right as nesting.
  def family(name: String, loader: ClassLoader): Either[String, MessageFamily] =
    val parts      = name.split('.').toList
    val candidates = (parts.size to 1 by -1).map: i =>
      parts.take(i).mkString(".") + parts.drop(i).map("$" + _).mkString + "$"
    val results    = candidates.map(instance(_, loader))
    results.collectFirst { case Right(Some(f)) => f }
      .toRight(
        results.collectFirst { case Left(e) => e }
          .getOrElse("not a MessageFamily object on the classpath")
      )

  // Right(None): no class of that name, or not a MessageFamily object. Left: a MessageFamily
  // class whose object cannot be had, such as one whose initialiser throws.
  private def instance(
    binaryName: String,
    loader: ClassLoader,
  ): Either[String, Option[MessageFamily]] =
    familyClass(binaryName, loader).fold(Right(None)): c =>
      try
        c.getField("MODULE$").get(null) match
          case f: MessageFamily => Right(Some(f))
          case _                => Right(None)
      catch
        case _: NoSuchFieldException        => Right(None)
        // The first use of a failing initialiser throws ExceptionInInitializerError, every later
        // one NoClassDefFoundError: both are the same fault.
        case e: ExceptionInInitializerError =>
          Left(s"cannot initialise: ${describe(Option(e.getCause).getOrElse(e))}")
        case e: LinkageError                => Left(s"cannot initialise: ${describe(e)}")
        case NonFatal(e)                    => Left(describe(e))

  // A class that cannot be loaded or linked is not known to be a family, so it is not one.
  private def familyClass(binaryName: String, loader: ClassLoader): Option[Class[?]] =
    try
      Some(Class.forName(binaryName, false, loader)).filter(classOf[MessageFamily].isAssignableFrom)
    catch
      case _: LinkageError => None
      case NonFatal(_)     => None

  private def describe(e: Throwable): String = ExportCatalogues.describe(e)

end Classpath
