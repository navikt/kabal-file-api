package no.nav.klage.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * The file is not a type we can turn into a PDF. Signals to the client that retrying is pointless:
 * the user has to upload a different file.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
class AttachmentUnsupportedTypeException(
    override val message: String = "FILE_TYPE_NOT_SUPPORTED"
) : RuntimeException(message)
