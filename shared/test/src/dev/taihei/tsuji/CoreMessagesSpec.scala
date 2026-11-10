package dev.taihei.tsuji

import java.time.LocalDate

import scala.annotation.nowarn

import zio.test.*

import dev.taihei.tsuji.CoreMessages.*
import dev.taihei.tsuji.Language.*
import dev.taihei.tsuji.TestJson.*

object CoreMessagesSpec extends ZIOSpecDefault:

  def spec = suite("CoreMessages")(
    test("the families have the prefix taihei and English as their source") {
      assertTrue(
        CoreMessages.families.map(_.prefix) ==
        List(
          "taihei.general",
          "taihei.authentication",
          "taihei.exceptions",
          "taihei.validation",
          "taihei.authorization",
          "taihei.language",
        ),
        CoreMessages.families.forall(_.sourceLanguage == English),
        Validation.stringMinLength.key.value == "taihei.validation.stringMinLength",
      )
    },
    test("messages show in English with their arguments") {
      given Catalogues = Catalogues(CoreMessages.families)
      assertTrue(
        General.helloWorld(name = "Alice").localize(English) == "Hello Alice",
        Validation.stringLengthBetween(min = 5, max = 20).localize(English) == "length should be between 5 and 20",
        Exceptions.revisionMismatchBetween(expected = 3L, actual = 4L).localize(English) ==
        "Revision mismatch: expected revision 3, but current revision is 4",
        Authentication.locked.toString == "Too many failed attempts; please try again later",
      )
    },
    test("the length and date bounds read as inclusive in English, as the checks that use them are") {
      given Catalogues = Catalogues(CoreMessages.families)
      assertTrue(
        Validation.stringMinLength(min = 8).localize(English) == "length should be at least 8",
        Validation.stringMaxLength(max = 100).localize(English) == "length should be at most 100",
        Validation.dateMinimum(min = LocalDate.of(2024, 1, 15)).localize(English) ==
        "should be on or after Jan 15, 2024",
        Validation.dateMaximum(max = LocalDate.of(2024, 12, 31)).localize(English) ==
        "should be on or before Dec 31, 2024",
      )
    },
    test("the number bounds read as inclusive in English, as the checks that use them are") {
      given Catalogues = Catalogues(CoreMessages.families)
      assertTrue(
        Validation.intMinimum(min = 1).localize(English) == "should be at least 1",
        Validation.intMaximum(max = 10).localize(English) == "should be at most 10",
        Validation.longMinimum(min = 5000000000L).localize(English) == "should be at least 5,000,000,000",
        Validation.longMaximum(max = -3L).localize(English) == "should be at most -3",
      )
    },
    test("formatErrors makes one message of the fields' errors, in field order") {
      given Catalogues = Catalogues(CoreMessages.families)
      val errors = Validation.formatErrors(
        Map(
          "name" -> List(Validation.stringShouldNotBeBlank),
          "email" -> List(Validation.stringValidEmail, Text("taken", English)),
        )
      )
      assertTrue(
        errors.localize(English) ==
        "Validation failed: email (should be a valid email address), email (taken), name (should not be blank)",
        Validation.formatErrors(Map.empty) == Validation.invalid,
        errors.toJson.fromJson[Message] == Right(errors),
      )
    },
    test("missingTranslations names each language in the reader's language") {
      given Catalogues = Catalogues(CoreMessages.families)
      val missing = Validation.missingTranslations(languages = List(Language("de"), French))
      // The template comes from the catalogue on the JVM and falls back to English in Scala.js,
      // which loads no catalogue here; the names are the reader's on both.
      assertTrue(
        missing.localize(English) == "missing translations for: German, French",
        missing.localize(Dutch).endsWith(": Duits, Frans"),
        Validation.missingTranslations(List(Language("de"), Language("es"))).localize(French)
          .endsWith(": allemand, espagnol"),
        missing.key.value == "taihei.validation.missingTranslationsFor",
        missing.toJson.fromJson[Message] == Right(missing),
        Validation.missingTranslations(List(Dutch)).localize(English) ==
        "missing translations for: Dutch",
      )
    },
    test("a message written with the joined string still reads, under its own key") {
      given Catalogues = Catalogues(CoreMessages.families)
      @nowarn("cat=deprecation")
      val joined = Validation.missingTranslations(languages = "nl, fr")
      val read   = joined.toJson.fromJson[Message]
      assertTrue(
        joined.key.value == "taihei.validation.missingTranslations",
        read == Right(joined),
        read.map(_.localize(English)) == Right("missing translations for: nl, fr"),
      )
    },
    test("both forms read from the JSON a writer sends, without its text") {
      given Catalogues = Catalogues(CoreMessages.families)
      val listed =
        """{"key":"taihei.validation.missingTranslationsFor","args":{"languages":["de","fr"]}}"""
      val joined = """{"key":"taihei.validation.missingTranslations","args":{"languages":"nl, fr"}}"""
      assertTrue(
        listed.fromJson[Message].map(_.localize(English)) ==
        Right("missing translations for: German, French"),
        listed.fromJson[Message] == Right(Validation.missingTranslations(List(Language("de"), French))),
        joined.fromJson[Message].map(_.localize(English)) == Right("missing translations for: nl, fr"),
      )
    },
    test("every template and translation passes verify") {
      assertTrue(Catalogues.verify(CoreMessages.families, Nil).isEmpty)
    },
  )

end CoreMessagesSpec
