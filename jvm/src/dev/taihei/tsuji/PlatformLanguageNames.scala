package dev.taihei.tsuji

import java.util.Locale

private[tsuji] object PlatformLanguageNames:

  // The JDK keeps the Locale objects it makes. A name is therefore put together from the names of
  // its parts, each taken from a Locale that holds that part alone: one per language of two or
  // three letters, per region, per script and per variant below, a few tens of thousands at most
  // whatever tags arrive. A part outside these sets is shown as its code, as the JDK shows a part
  // it has no name for.

  // ISO 15924 scripts, the ones the JDK can name.
  private val Scripts: Set[String] = Set(
    "Adlm",
    "Afak",
    "Aghb",
    "Ahom",
    "Arab",
    "Aran",
    "Armi",
    "Armn",
    "Avst",
    "Bali",
    "Bamu",
    "Bass",
    "Batk",
    "Beng",
    "Bhks",
    "Blis",
    "Bopo",
    "Brah",
    "Brai",
    "Bugi",
    "Buhd",
    "Cakm",
    "Cans",
    "Cari",
    "Cham",
    "Cher",
    "Chis",
    "Chrs",
    "Cirt",
    "Copt",
    "Cpmn",
    "Cprt",
    "Cyrl",
    "Cyrs",
    "Deva",
    "Diak",
    "Dogr",
    "Dsrt",
    "Dupl",
    "Egyd",
    "Egyh",
    "Egyp",
    "Elba",
    "Elym",
    "Ethi",
    "Gara",
    "Geok",
    "Geor",
    "Glag",
    "Gong",
    "Gonm",
    "Goth",
    "Gran",
    "Grek",
    "Gujr",
    "Gukh",
    "Guru",
    "Hanb",
    "Hang",
    "Hani",
    "Hano",
    "Hans",
    "Hant",
    "Hatr",
    "Hebr",
    "Hira",
    "Hluw",
    "Hmng",
    "Hmnp",
    "Hrkt",
    "Hung",
    "Inds",
    "Ital",
    "Jamo",
    "Java",
    "Jpan",
    "Jurc",
    "Kali",
    "Kana",
    "Kawi",
    "Khar",
    "Khmr",
    "Khoj",
    "Kitl",
    "Kits",
    "Knda",
    "Kore",
    "Kpel",
    "Krai",
    "Kthi",
    "Lana",
    "Laoo",
    "Latf",
    "Latg",
    "Latn",
    "Leke",
    "Lepc",
    "Limb",
    "Lina",
    "Linb",
    "Lisu",
    "Loma",
    "Lyci",
    "Lydi",
    "Mahj",
    "Maka",
    "Mand",
    "Mani",
    "Marc",
    "Maya",
    "Medf",
    "Mend",
    "Merc",
    "Mero",
    "Mlym",
    "Modi",
    "Mong",
    "Moon",
    "Mroo",
    "Mtei",
    "Mult",
    "Mymr",
    "Nagm",
    "Nand",
    "Narb",
    "Nbat",
    "Newa",
    "Nkdb",
    "Nkgb",
    "Nkoo",
    "Nshu",
    "Ogam",
    "Olck",
    "Onao",
    "Orkh",
    "Orya",
    "Osge",
    "Osma",
    "Ougr",
    "Palm",
    "Pauc",
    "Pcun",
    "Pelm",
    "Perm",
    "Phag",
    "Phli",
    "Phlp",
    "Phlv",
    "Phnx",
    "Piqd",
    "Plrd",
    "Prti",
    "Psin",
    "Qaaa",
    "Rjng",
    "Rohg",
    "Roro",
    "Runr",
    "Samr",
    "Sara",
    "Sarb",
    "Saur",
    "Sgnw",
    "Shaw",
    "Shrd",
    "Shui",
    "Sidd",
    "Sidt",
    "Sind",
    "Sinh",
    "Sogd",
    "Sogo",
    "Sora",
    "Soyo",
    "Sund",
    "Sunu",
    "Sylo",
    "Syrc",
    "Syre",
    "Syrj",
    "Syrn",
    "Tagb",
    "Takr",
    "Tale",
    "Talu",
    "Taml",
    "Tang",
    "Tavt",
    "Tayo",
    "Telu",
    "Teng",
    "Tfng",
    "Tglg",
    "Thaa",
    "Thai",
    "Tibt",
    "Tirh",
    "Tnsa",
    "Todr",
    "Tols",
    "Toto",
    "Tutg",
    "Ugar",
    "Vaii",
    "Visp",
    "Vith",
    "Wara",
    "Wcho",
    "Wole",
    "Xpeo",
    "Xsux",
    "Yezi",
    "Yiii",
    "Zanb",
    "Zinh",
    "Zmth",
    "Zsye",
    "Zsym",
    "Zxxx",
    "Zyyy",
    "Zzzz",
  )

  // The variants the JDK can name, as CLDR registers them, in the case the JDK names them in.
  private val Variants: Set[String] = Set(
    "1606NICT",
    "1694ACAD",
    "1901",
    "1959ACAD",
    "1994",
    "1996",
    "ABL1943",
    "AKUAPEM",
    "ALALC97",
    "ALUKU",
    "AO1990",
    "ARANES",
    "ASANTE",
    "AUVERN",
    "BAKU1926",
    "BALANKA",
    "BARLA",
    "BASICENG",
    "BAUDDHA",
    "BISCAYAN",
    "BISKE",
    "BOHORIC",
    "BOONT",
    "BORNHOLM",
    "CISAUP",
    "COLB1945",
    "CORNU",
    "CREISS",
    "DAJNKO",
    "EKAVSK",
    "EMODENG",
    "FONIPA",
    "FONKIRSH",
    "FONNAPA",
    "FONUPA",
    "FONXSAMP",
    "GASCON",
    "GRCLASS",
    "GRITAL",
    "GRMISTR",
    "HEPBURN",
    "HOGNORSK",
    "HSISTEMO",
    "IJEKAVSK",
    "ITIHASA",
    "IVANCHOV",
    "JAUER",
    "JYUTPING",
    "KKCOR",
    "KOCIEWIE",
    "KSCOR",
    "LAUKIKA",
    "LEMOSIN",
    "LENGADOC",
    "LIPAW",
    "LUNA1918",
    "METELKO",
    "MONOTON",
    "NDYUKA",
    "NEDIS",
    "NEWFOUND",
    "NICARD",
    "NJIVA",
    "NULIK",
    "OSOJS",
    "OXENDICT",
    "PAHAWH2",
    "PAHAWH3",
    "PAHAWH4",
    "PAMAKA",
    "PEANO",
    "PETR1708",
    "PINYIN",
    "POLYTON",
    "POSIX",
    "PROVENC",
    "PUTER",
    "REVISED",
    "RIGIK",
    "ROZAJ",
    "RUMGR",
    "SAAHO",
    "SCOTLAND",
    "SCOUSE",
    "SIMPLE",
    "SOLBA",
    "SOTAV",
    "SPANGLIS",
    "SURMIRAN",
    "SURSILV",
    "SUTSILV",
    "TARASK",
    "TONGYONG",
    "TUNUMIIT",
    "UCCOR",
    "UCRCOR",
    "ULSTER",
    "UNIFON",
    "VAIDIKA",
    "VALENCIA",
    "VALLADER",
    "VECDRUKA",
    "VIVARAUP",
    "WADEGILE",
    "XSISTEMO",
  )

  private val Calendars: Set[String] =
    java.util.Calendar.getAvailableCalendarTypes.toArray.map(_.toString).toSet

  // A fixed locale with a language and two qualifiers, from which the separators a reader's
  // language puts around and between qualifiers are read.
  private val Probe = Locale.forLanguageTag("en-Latn-US")

  private def letters(s: String) = s.nonEmpty && s.forall(c => c >= 'a' && c <= 'z')

  /**
   * The name of the language `tag` in the language `in`, from the JDK's locale data, or `None`
   * when the JDK does not know it (it then returns the code itself). `in` goes through
   * [[PlatformLocales.javaLocale]]. The name is the one the JDK gives, put together from the names
   * of the language, script, region, variants, calendar and numbering system; of the extensions,
   * only a calendar and a numbering system are named.
   */
  def displayName(tag: String, in: String): Option[String] =
    val parsed     = LanguageSubtags.parse(tag)
    val reader     = PlatformLocales.javaLocale(in)
    val language   =
      if parsed.language.length <= 3 && letters(parsed.language) then
        Locale.forLanguageTag(parsed.language).getDisplayLanguage(reader)
      else parsed.language
    val qualifiers = List(
      Option.when(parsed.script.nonEmpty)(scriptName(parsed.script, reader)),
      Option.when(
        parsed.region.nonEmpty
      )(Locale.forLanguageTag(s"und-${parsed.region}").getDisplayCountry(reader)),
    ).flatten ++ parsed.variant.split('_').toList.filter(_.nonEmpty).map(variantName(_, reader)) ++
      List(
        parsed.calendar.filter(Calendars.contains).flatMap(c => keywordName(s"ca-$c", reader)),
        parsed.digits.filter(d => PlatformLocales.digitsAllowed(parsed.language, d)).flatMap(d =>
          keywordName(s"nu-$d", reader)
        ),
      ).flatten
    val name       = (language :: qualifiers).filter(_.nonEmpty) match
      case Nil                => ""
      case main :: Nil        => main
      case main :: qualifiers =>
        val (open, separator, close) = separators(reader)
        main + open + qualifiers.mkString(separator) + close
    Option(name).filter(n => n.nonEmpty && n != tag && n != parsed.language)

  end displayName

  private def scriptName(script: String, reader: Locale): String =
    if Scripts.contains(script) then Locale.forLanguageTag(s"und-$script").getDisplayScript(reader)
    else script

  private def variantName(variant: String, reader: Locale): String =
    if Variants.contains(variant) then
      Locale.forLanguageTag(s"und-$variant").getDisplayVariant(reader)
    else variant

  // The name the JDK gives a Unicode keyword, read from a fixed language with that keyword alone.
  private def keywordName(keyword: String, reader: Locale): Option[String] =
    val language         = Locale.ENGLISH.getDisplayLanguage(reader)
    val full             = Locale.forLanguageTag(s"en-u-$keyword").getDisplayName(reader)
    val (open, _, close) = separators(reader)
    Option.when(full.startsWith(language + open) && full.endsWith(close))(
      full.substring(language.length + open.length, full.length - close.length)
    ).filter(_.nonEmpty)

  // What a reader's language puts before, between and after the qualifiers of a name, read from
  // the name of the probe: `English (Latin, United States)` gives ` (`, `, ` and `)`.
  private def separators(reader: Locale): (String, String, String) =
    val full     = Probe.getDisplayName(reader)
    val language = Probe.getDisplayLanguage(reader)
    val script   = Probe.getDisplayScript(reader)
    val region   = Probe.getDisplayCountry(reader)
    val atScript = full.indexOf(script, language.length)
    val atRegion = full.indexOf(region, atScript + script.length)
    if full.startsWith(language) && atScript > 0 && atRegion > 0 then
      (
        full.substring(language.length, atScript),
        full.substring(atScript + script.length, atRegion),
        full.substring(atRegion + region.length),
      )
    else (" (", ", ", ")")

end PlatformLanguageNames
