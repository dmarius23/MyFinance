package ro.myfinance.common.web;

/** 409 — request conflicts with current state (e.g. duplicate CUI within a tenant). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
