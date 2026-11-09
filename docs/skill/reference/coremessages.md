# coremessages - the pre-built message families

Package `dev.taihei.tsuji`. `CoreMessages` holds families of common messages, English as the source
language, Dutch and French as catalogue files in the jar
(`META-INF/taihei-tsuji/taihei.<group>/<nl|fr>.json`), which translate every message of every
family. On Scala.js those files reach the browser through the export. `CoreMessages.families`
lists all of them. The French texts put a no-break space (U+00A0) before `:` and a narrow
no-break space (U+202F) before `;`, `?` and `!`, so a test that compares French output needs them.

```scala
given Catalogues = Catalogues(CoreMessages.families)
CoreMessages.Validation.stringMinLength(min = 8).localize(Language.Dutch)  // lengte moet minimaal 8 zijn
CoreMessages.Authentication.invalidCredentials.localize(Language.French)   // Adresse e-mail ou mot de passe incorrect
```

| Family (prefix) | Members |
| --- | --- |
| `General` (`taihei.general`) | `helloWorld(name)` |
| `Authentication` (`taihei.authentication`) | `success`, `accountNotFound`, `invalidCredentials`, `emailNotVerified`, `emailAlreadyRegistered`, `weakPassword`, `passwordMismatch`, `expiredToken`, `tokenAlreadyUsed`, `cannotRemoveLastCredential`, `wrongCurrentPassword`, `verificationSent`, `passwordResetSent`, `locked` |
| `Authorization` (`taihei.authorization`) | `accountNotActive`, `accountNotSystem`, `insufficientRole`, `insufficientGrant`, `personAccountMismatch`, `registerPersonForDifferentAccount` |
| `Validation` (`taihei.validation`) | `invalid`, `stringShouldNotBeBlank`, `stringValidEmail`, `stringValidUrl`, `stringValidPhoneNumber`, `stringLengthBetween(min, max)`, `stringMinLength(min)`, `stringMaxLength(max)`, `stringShouldEqual(otherName)`, `intMinimum(min)`, `intMaximum(max)`, `intBetween(min, max)`, `longMinimum(min)`, `longMaximum(max)`, `dateMinimum(min: LocalDate)`, `dateMaximum(max: LocalDate)`, `dateFuture`, `datePast`, `nonEmpty`, `shouldNotEqual`, `invalidDate`, `invalidDateTime`, `dateRangeInvalid`, `missingTranslations(languages: List[Language])`, `failed(errors)`, `fieldError(field, error)`, `formatErrors(errors)` |
| `Exceptions` (`taihei.exceptions`) | `unsupportedCommand`, `invalidCommand`, `commandVeto(command, state)`, `personAlreadyLinked`, `mediaProcessingFailed`, `eventFailure`, `stateFailure`, `invalidQuery`, `queryFailure`, `unknownError`, `notFound`, `imageNotFound`, `unauthorized`, `forbidden`, `revisionMismatch`, `revisionMismatchBetween(expected, actual)` |
| `Languages` (`taihei.language`) | `name(language)`: what `Language.label` returns |

## Missing translations

```scala
CoreMessages.Validation.missingTranslations(languages = List(Language("de"), Language.French))
  .localize(Language.Dutch)  // ontbrekende vertalingen voor: Duits, Frans
```

Since 6.1.0 each language is named in the reader's language and the names are joined with `, `.
Key `taihei.validation.missingTranslationsFor`. The 6.0 form `missingTranslations(languages:
String)` (key `taihei.validation.missingTranslations`) shows the string as it is; it is
deprecated since 6.1.0 and removed in 7.0.0: pass the `Language` values instead.

## Validation errors of several fields

```scala
CoreMessages.Validation.formatErrors(Map(
  "email" -> List(CoreMessages.Validation.stringValidEmail),
  "name"  -> List(CoreMessages.Validation.stringShouldNotBeBlank),
)).localize(Language.Dutch)
// Validatie mislukt: email (moet een geldig e-mailadres zijn), name (mag niet leeg zijn)
```

`formatErrors(errors: Map[String, List[Displayable]]): Message`: fields in alphabetical order,
each error a nested message shown in the reader's language; no errors gives `invalid`.
