package no.nav.klage.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
class AttachmentConversionFailedException(
    override val message: String = "FILE_CONVERSION_FAILED",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
