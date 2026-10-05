package app.sprout.depository.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the depository contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Not allowed"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No such account"),
    INSTRUCTION_CONFLICT(HttpStatus.CONFLICT, "Instruction id already used"),
    INSUFFICIENT_SECURITIES(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough shares"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
