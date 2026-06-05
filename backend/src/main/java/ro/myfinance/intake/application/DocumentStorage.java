package ro.myfinance.intake.application;

/** Port for binary document storage. Implementations: local filesystem (dev), Supabase Storage (prod). */
public interface DocumentStorage {

    StoredObject store(String key, byte[] bytes, String contentType);

    byte[] retrieve(String key);

    void delete(String key);
}
