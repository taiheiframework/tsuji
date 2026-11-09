package dev.taihei.tsuji

import java.time.LocalDate

import zio.test.*

import dev.taihei.tsuji.CoreMessages.*
import dev.taihei.tsuji.Language.*

// The Dutch and French catalogues of CoreMessages are files in the jar, read from the classpath.
object CoreMessagesCatalogueSpec extends ZIOSpecDefault:

  def spec = suite("CoreMessages catalogues on the classpath")(
    test("the shipped Dutch and French files are valid and translate every message") {
      assertTrue(
        ClasspathCatalogues
          .verify(CoreMessages.families, Seq(Dutch, French), required = Set(Dutch, French))
          .isEmpty,
        ClasspathCatalogues.read(Validation, Dutch).exists(_.isRight),
        ClasspathCatalogues.read(Exceptions, French).exists(_.isRight),
      )
    },
    test("messages show in Dutch and French") {
      given Catalogues = Catalogues(CoreMessages.families)
      assertTrue(
        Validation.stringMinLength(min = 8).localize(Dutch) == "lengte moet minimaal 8 zijn",
        Validation.stringShouldNotBeBlank.localize(French) == "ne doit pas être vide",
        Exceptions.commandVeto(command = "close", state = "open").localize(Dutch) ==
        "Kan opdracht close niet uitvoeren in toestand open",
        Validation.dateMinimum(min =
          LocalDate.of(2024, 1, 15)
        ).localize(Language("nl-BE")).startsWith("mag niet vroeger zijn dan 15"),
        Authentication.locked.localize(Dutch) == "Te veel mislukte pogingen; probeer het later opnieuw",
        Authentication.invalidCredentials.localize(French) ==
        "Adresse e-mail ou mot de passe incorrect",
        Exceptions.notFound.localize(Dutch) == "Niet gevonden",
        Exceptions.notFound.localize(French) == "Introuvable",
        Validation.invalid.localize(French) == "échec de la validation",
        Language("de").label.localize(French) == "allemand",
      )
    },
    test("a query and a command read differently, in Dutch and in French") {
      given Catalogues = Catalogues(CoreMessages.families)
      assertTrue(
        Exceptions.invalidQuery.localize(Dutch) != Exceptions.invalidCommand.localize(Dutch),
        Exceptions.invalidQuery.localize(French) != Exceptions.invalidCommand.localize(French),
      )
    },
    test("French puts a no-break space before a colon or semicolon, Dutch none") {
      given Catalogues = Catalogues(CoreMessages.families)
      assertTrue(
        Exceptions.forbidden.localize(French) == "Interdit\u00a0: accès refusé",
        Authentication.locked.localize(French) ==
        "Trop de tentatives infructueuses\u202f; veuillez réessayer plus tard",
        Exceptions.forbidden.localize(Dutch) == "Verboden: toegang geweigerd",
      )
    },
    test("formatErrors shows each field's error in the reader's language") {
      given Catalogues = Catalogues(CoreMessages.families)
      val errors = Validation.formatErrors(Map("email" -> List(Validation.stringValidEmail)))
      assertTrue(
        errors.localize(Dutch) == "Validatie mislukt: email (moet een geldig e-mailadres zijn)",
        errors.localize(French) == "Échec de la validation\u00a0: email (doit être une adresse e-mail valide)",
      )
    },
  )

end CoreMessagesCatalogueSpec
