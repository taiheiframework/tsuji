package dev.taihei.tsuji

// Distinct languages of one three-letter subtag each, which canonicalize to themselves: each is a
// whole fallback chain on its own, and none has a catalogue file in the tests.
object PlainLanguages:

  def apply(n: Int): List[Language] =
    val letters = ('a' to 'z').toList
    letters.iterator
      .flatMap(a => letters.iterator.flatMap(b => letters.iterator.map(c => s"$a$b$c")))
      .flatMap(tag => Language.parse(tag).toOption.filter(_.tag == tag))
      .take(n)
      .toList
