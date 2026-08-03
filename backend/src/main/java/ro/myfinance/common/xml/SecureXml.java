package ro.myfinance.common.xml;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Factory for XML parsers hardened against XXE (XML External Entity) attacks. All XML we parse comes from
 * uploaded/scraped files we don't control (CAMT.053 statements, ANAF declarations), so every parser must
 * refuse DOCTYPE declarations and external entities. Callers set {@code namespaceAware} as they need it.
 */
public final class SecureXml {

    private SecureXml() {
    }

    /**
     * A {@link DocumentBuilderFactory} that forbids DOCTYPE declarations and any external entity/DTD
     * resolution — the belt-and-braces XXE hardening recommended by the OWASP XXE cheat sheet.
     */
    public static DocumentBuilderFactory hardenedDocumentBuilderFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // The strongest single control: any DOCTYPE (where XXE payloads live) makes parsing throw.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Unable to configure a secure XML parser", e);
        }
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }
}
