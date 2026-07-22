package ro.myfinance.settings.application;

import java.util.List;

/**
 * Port for the ANAF treasury-IBAN catalogue: walks every county → treasury → PDF and returns the four
 * IBANs per treasury. Deterministic (no OCR/LLM). The HTTP + PDF details live behind the port in
 * {@code adapter/external}, so the sync service and its tests never touch the network.
 */
public interface AnafIbanSource {

    /**
     * Fetch every treasury's IBANs. Never throws for a single county/PDF failure — those become
     * {@link TreasuryIbans} rows with a non-null {@code error} so the caller can report them per-item.
     */
    List<TreasuryIbans> fetchAll();
}
