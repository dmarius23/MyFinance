package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.myfinance.intake.adapter.external.LocalFsDocumentStorage;
import ro.myfinance.intake.application.StoredObject;

class LocalFsDocumentStorageTest {

    @Test
    void storesRetrievesAndDeletes(@TempDir Path tmp) {
        LocalFsDocumentStorage storage = new LocalFsDocumentStorage(tmp);
        byte[] bytes = "hello pdf".getBytes(StandardCharsets.UTF_8);
        String key = "t1/c1/2026-06/doc-1-file.pdf";

        StoredObject stored = storage.store(key, bytes, "application/pdf");
        assertThat(stored.size()).isEqualTo(bytes.length);
        assertThat(storage.retrieve(key)).isEqualTo(bytes);

        storage.delete(key);
        assertThatThrownBy(() -> storage.retrieve(key)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsPathTraversal(@TempDir Path tmp) {
        LocalFsDocumentStorage storage = new LocalFsDocumentStorage(tmp);
        assertThatThrownBy(() -> storage.store("../escape.pdf", new byte[]{1}, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
