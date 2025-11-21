package ru.driics.aitrade.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * Global exception handler for all controllers.
 * Provides consistent error responses and validation error formatting.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val clock: Clock
) {

    /**
     * Handle validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationErrors(
        ex: MethodArgumentNotValidException
    ): ResponseEntity<ValidationErrorResponse> {
        val errors = ex.bindingResult.allErrors.mapNotNull { error ->
            when (error) {
                is FieldError -> ValidationErrorDetail(
                    field = error.field,
                    message = error.defaultMessage ?: "Invalid value",
                    rejectedValue = error.rejectedValue?.toString()
                )
                else -> ValidationErrorDetail(
                    field = error.objectName,
                    message = error.defaultMessage ?: "Invalid value",
                    rejectedValue = null
                )
            }
        }

        log.warn { "Validation failed: ${errors.size} errors" }

        return ResponseEntity
            .badRequest()
            .body(
                ValidationErrorResponse(
                    status = "error",
                    message = "Validation failed",
                    timestamp = clock.instant().toEpochMilli(),
                    errors = errors
                )
            )
    }

    /**
     * Handle illegal argument exceptions (e.g., from typed ID validation).
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        log.warn(ex) { "Illegal argument: ${ex.message}" }

        return ResponseEntity
            .badRequest()
            .body(
                mapOf(
                    "status" to "error",
                    "message" to (ex.message ?: "Invalid argument"),
                    "timestamp" to clock.instant().toEpochMilli()
                )
            )
    }

    /**
     * Handle generic exceptions.
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<Map<String, Any>> {
        log.error(ex) { "Unhandled exception: ${ex.message}" }

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                mapOf(
                    "status" to "error",
                    "message" to "Internal server error",
                    "timestamp" to clock.instant().toEpochMilli()
                )
            )
    }
}

/**
 * Validation error response with detailed field errors.
 */
data class ValidationErrorResponse(
    val status: String,
    val message: String,
    val timestamp: Long,
    val errors: List<ValidationErrorDetail>
)

data class ValidationErrorDetail(
    val field: String,
    val message: String,
    val rejectedValue: String?
)