package ro.myfinance.intake.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;

/**
 * Read-only directory of uploaded documents, exposed to other modules so they don't reach into the
 * {@code intake} module's persistence adapter. Lookups are RLS-scoped to the current tenant.
 */
@Service
@Transactional(readOnly = true)
public class DocumentDirectory {

    private final DocumentRepository documents;

    public DocumentDirectory(DocumentRepository documents) {
        this.documents = documents;
    }

    /** A single document by id, if it exists in the current tenant. */
    public Optional<Document> findById(UUID id) {
        return documents.findById(id);
    }
}
