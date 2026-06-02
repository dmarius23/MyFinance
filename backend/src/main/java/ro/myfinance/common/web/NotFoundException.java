package ro.myfinance.common.web;

/** 404 — requested resource does not exist within the current tenant scope. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
