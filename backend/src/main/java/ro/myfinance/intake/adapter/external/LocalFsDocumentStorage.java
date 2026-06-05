package ro.myfinance.intake.adapter.external;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.intake.application.StoredObject;

/** Stores documents on the local filesystem under a base directory. Dev/test default. */
public class LocalFsDocumentStorage implements DocumentStorage {

    private final Path baseDir;

    public LocalFsDocumentStorage(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(String key, byte[] bytes, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return new StoredObject(key, bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store document " + key, e);
        }
    }

    @Override
    public byte[] retrieve(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read document " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete document " + key, e);
        }
    }

    /** Resolve a key under baseDir, rejecting path traversal. */
    private Path resolve(String key) {
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return resolved;
    }
}
