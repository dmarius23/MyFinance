package ro.myfinance.intake.application;

/** Result of persisting bytes to a storage backend. */
public record StoredObject(String key, long size) {
}
