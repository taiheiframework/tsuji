package dev.taihei.tsuji

import java.time.LocalDate

/**
 * Ready-made messages for common situations, as families with English as their source language.
 * Their Dutch and French translations are catalogue files in the jar
 * (`META-INF/taihei-tsuji/taihei.<family>/<nl|fr>.json`), read from the classpath on the JVM; a
 * Scala.js application serves them with its other catalogues (see `taihei-tsuji-tools`).
 *
 * {{{
 * given Catalogues = Catalogues(CoreMessages.families)
 * CoreMessages.Validation.stringMinLength(min = 8).localize(Language.Dutch)   // lengte moet minimaal 8 zijn
 * }}}
 */
object CoreMessages:

  /** Every family of CoreMessages, to register with a [[Catalogues]] or to export. */
  lazy val families: List[MessageFamily] =
    List(General, Authentication, Exceptions, Validation, Authorization, Languages)

  /** A greeting, mostly for examples. */
  object General extends MessageFamily("taihei.general"):

    val helloWorld = message[(name: String)]("Hello {$name}")

  /** Signing in and out, accounts and passwords. */
  object Authentication extends MessageFamily("taihei.authentication"):

    val success = message("Authentication successful")

    val accountNotFound = message("Account not found")

    val invalidCredentials = message("Invalid email or password")

    val emailNotVerified = message("Please verify your email address before signing in")

    val emailAlreadyRegistered = message("An account with this email already exists")

    val weakPassword = message("Password is too weak; choose a stronger one")

    val passwordMismatch = message("Passwords do not match")

    val expiredToken = message("This link has expired")

    val tokenAlreadyUsed = message("This link has already been used")

    val cannotRemoveLastCredential =
      message("Cannot remove the only sign-in method for this account")

    val wrongCurrentPassword = message("Current password is incorrect")

    val verificationSent = message("Check your email to confirm your account")

    val passwordResetSent = message("If that email is registered, a reset link has been sent")

    val locked = message("Too many failed attempts; please try again later")

  end Authentication

  /** Failures of commands, queries and resources. */
  object Exceptions extends MessageFamily("taihei.exceptions"):

    val unsupportedCommand = message("Unsupported command")

    val invalidCommand = message("Invalid command")

    val commandVeto = message[(command: String, state: String)](
      "Cannot execute command {$command} on state {$state}"
    )

    val personAlreadyLinked = message("Cannot link: person is already linked to an account")

    val mediaProcessingFailed = message("Media processing failed")

    val eventFailure = message("Event failure")

    val stateFailure = message("State failure")

    val invalidQuery = message("Invalid query")

    val queryFailure = message("Query failure")

    val unknownError = message("Unknown error")

    val notFound = message("Not found")

    val imageNotFound = message("Image not found")

    val unauthorized = message("Unauthorized")

    val forbidden = message("Forbidden: Access denied")

    val revisionMismatch = message("Revision mismatch: the resource was modified by another user")

    val revisionMismatchBetween = message[(expected: Long, actual: Long)](
      "Revision mismatch: expected revision {$expected}, but current revision is {$actual}"
    )

  end Exceptions

  /** Validation of input values, field by field. */
  object Validation extends MessageFamily("taihei.validation"):

    val invalid = message("validation failed")

    val stringShouldNotBeBlank = message("should not be blank")

    val stringValidEmail = message("should be a valid email address")

    val stringValidUrl = message("should be a valid URL")

    val stringValidPhoneNumber = message("should be a valid phone number")

    val stringLengthBetween =
      message[(min: Int, max: Int)]("length should be between {$min} and {$max}")

    val stringMinLength = message[(min: Int)]("length should be at least {$min}")

    val stringMaxLength = message[(max: Int)]("length should be at most {$max}")

    val stringShouldEqual = message[(otherName: String)]("should equal {$otherName}")

    val intMinimum = message[(min: Int)]("should be at least {$min}")

    val intMaximum = message[(max: Int)]("should be at most {$max}")

    val longMinimum = message[(min: Long)]("should be at least {$min}")

    val longMaximum = message[(max: Long)]("should be at most {$max}")

    val dateMinimum = message[(min: LocalDate)]("should be on or after {$min :date}")

    val dateMaximum = message[(max: LocalDate)]("should be on or before {$max :date}")

    val dateFuture = message("should be in the future")

    val datePast = message("should be in the past")

    val nonEmpty = message("should not be empty")

    val shouldNotEqual = message("should not be equal")

    val intBetween = message[(min: Int, max: Int)]("should be between {$min} and {$max}")

    val invalidDate = message("is not a valid date")

    val invalidDateTime = message("is not a valid date and time")

    val dateRangeInvalid = message("start date must be before or equal to end date")

    val missingTranslations = message[(languages: String)]("missing translations for: {$languages}")

    val failed = message[(errors: List[Message])]("Validation failed: {$errors}")

    val fieldError = message[(field: String, error: Displayable)]("{$field} ({$error})")

    /**
     * One message for the errors of several fields, each error shown in the reader's language:
     * `Validation failed: email (should be a valid email address), name (should not be blank)`.
     * Fields in alphabetical order; no errors at all is [[invalid]].
     */
    def formatErrors(errors: Map[String, List[Displayable]]): Message =
      val details = errors.toList.sortBy(_._1).flatMap((field, es) =>
        es.map(e => fieldError(field = field, error = e))
      )
      if details.isEmpty then invalid else failed(errors = details)

  end Validation

  /** Accounts that may not do what they ask. */
  object Authorization extends MessageFamily("taihei.authorization"):

    val accountNotActive = message("account is not active")

    val accountNotSystem = message("account is not a system account")

    val insufficientRole = message("account does not have one of the required roles")

    val insufficientGrant = message("account does not have permission for this scope")

    val personAccountMismatch = message("person does not correspond to the authenticated account")

    val registerPersonForDifferentAccount =
      message("trying to register person for a different account than the one authenticated")

  /** The name of a language, in the reader's language: what [[Language.label]] gives. */
  object Languages extends MessageFamily("taihei.language"):

    val name = message[(language: Language)]("{$language}")

end CoreMessages
