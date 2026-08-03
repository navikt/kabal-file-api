package no.nav.klage.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
class AttachmentCouldNotBeConvertedException(
    override val message: String = "FILE_COULD_NOT_BE_CONVERTED"
) : RuntimeException()
