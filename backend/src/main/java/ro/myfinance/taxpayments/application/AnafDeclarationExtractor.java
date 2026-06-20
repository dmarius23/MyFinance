package ro.myfinance.taxpayments.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.TaxCategory;
import ro.myfinance.taxpayments.domain.TaxObligation;

/**
 * Extracts payable amounts from an ANAF declaration PDF (D100 / D112 / D300). Each PDF embeds a
 * named-field XML (D100.xml / DecUnica.xml / D300.xml) — we read that, not the printed page. Fully
 * deterministic, no OCR/LLM. Per-declaration parsing; the itemized obligations are authoritative.
 */
@Component
public class AnafDeclarationExtractor {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Standard ANAF deadline when the XML carries no explicit scadenta: the 25th of the next month. */
    private static LocalDate defaultDeadline(YearMonth period) {
        return period.plusMonths(1).atDay(25);
    }

    /** Parse the declaration embedded in {@code pdf}. Throws if there is no recognizable ANAF XML. */
    public ParsedDeclaration extract(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            byte[] xml = firstXml(doc);
            if (xml == null) {
                throw new IllegalArgumentException("No embedded ANAF XML found in the PDF");
            }
            Document dom = parseXml(xml);
            String root = dom.getDocumentElement().getLocalName() != null
                    ? dom.getDocumentElement().getLocalName() : dom.getDocumentElement().getTagName();
            return switch (root) {
                case "declaratie100" -> parseD100(dom);
                case "declaratieUnica" -> parseD112(dom);
                case "declaratie300" -> parseD300(dom);
                default -> throw new IllegalArgumentException("Unsupported ANAF declaration root: " + root);
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ANAF PDF", e);
        }
    }

    // ---- per-declaration parsers ------------------------------------------------------------------

    private ParsedDeclaration parseD100(Document dom) {
        Element r = dom.getDocumentElement();
        YearMonth period = period(r.getAttribute("luna"), r.getAttribute("an"));
        List<TaxObligation> obligations = new ArrayList<>();
        for (Element o : elements(dom, "obligatie")) {
            BigDecimal amount = amount(o.getAttribute("suma_plata"));
            if (amount.signum() <= 0) {
                continue;
            }
            LocalDate scadenta = parseDate(o.getAttribute("scadenta"), period);
            obligations.add(new TaxObligation(category(o.getAttribute("cod_oblig")),
                    o.getAttribute("cod_oblig"), amount, scadenta));
        }
        return new ParsedDeclaration(DeclarationType.D100, r.getAttribute("cui"), r.getAttribute("den"),
                period, obligations, amountOrNull(r.getAttribute("totalPlata_A")));
    }

    private ParsedDeclaration parseD112(Document dom) {
        Element ang = elements(dom, "angajator").get(0);
        YearMonth period = period(attrOfRoot(dom, "luna_r"), attrOfRoot(dom, "an_r"));
        LocalDate deadline = defaultDeadline(period); // D112 carries no per-line scadenta
        List<TaxObligation> obligations = new ArrayList<>();
        for (Element a : elements(dom, "angajatorA")) {
            BigDecimal amount = amount(a.getAttribute("A_plata"));
            if (amount.signum() <= 0) {
                continue;
            }
            obligations.add(new TaxObligation(category(a.getAttribute("A_codOblig")),
                    a.getAttribute("A_codOblig"), amount, deadline));
        }
        return new ParsedDeclaration(DeclarationType.D112, ang.getAttribute("cif"), ang.getAttribute("den"),
                period, obligations, amountOrNull(ang.getAttribute("totalPlata_A")));
    }

    private ParsedDeclaration parseD300(Document dom) {
        Element r = dom.getDocumentElement();
        YearMonth period = period(r.getAttribute("luna"), r.getAttribute("an"));
        // D300 has no <obligatie>; the VAT payable is row 41 ("TVA de plată"). Row 42 ("de recuperat")
        // is a refund and yields no payment. The header totalPlata_A is NOT the VAT, so it is ignored.
        BigDecimal payable = amount(r.getAttribute("R41_2"));
        List<TaxObligation> obligations = new ArrayList<>();
        if (payable.signum() > 0) {
            obligations.add(new TaxObligation(TaxCategory.TVA, "TVA", payable, defaultDeadline(period)));
        }
        return new ParsedDeclaration(DeclarationType.D300, r.getAttribute("cui"), r.getAttribute("den"),
                period, obligations, null);
    }

    /**
     * Map an ANAF obligation code to a category. The buget-de-stat / cont-unic obligations (impozit
     * micro/profit 121, impozit pe venit 602, and any others) fall through to IMPOZIT; CAS/CASS/CAM are
     * their own funds.
     */
    private static TaxCategory category(String codOblig) {
        return switch (codOblig == null ? "" : codOblig.trim()) {
            case "412" -> TaxCategory.CAS;
            case "432" -> TaxCategory.CASS;
            case "480" -> TaxCategory.CAM;
            default -> TaxCategory.IMPOZIT;
        };
    }

    // ---- embedded-file + XML plumbing -------------------------------------------------------------

    private static byte[] firstXml(PDDocument doc) throws IOException {
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        PDEmbeddedFilesNameTreeNode tree = names.getEmbeddedFiles();
        if (tree == null) {
            return null;
        }
        Map<String, PDComplexFileSpecification> files = new LinkedHashMap<>();
        collect(tree, files);
        // Prefer a .xml entry, else the first embedded file.
        PDComplexFileSpecification chosen = null;
        for (var e : files.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().endsWith(".xml")) {
                chosen = e.getValue();
                break;
            }
        }
        if (chosen == null && !files.isEmpty()) {
            chosen = files.values().iterator().next();
        }
        PDEmbeddedFile ef = embeddedFile(chosen);
        if (ef == null) {
            return null;
        }
        try (InputStream in = ef.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static void collect(PDNameTreeNode<PDComplexFileSpecification> node,
                                Map<String, PDComplexFileSpecification> out) throws IOException {
        Map<String, PDComplexFileSpecification> n = node.getNames();
        if (n != null) {
            out.putAll(n);
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> k : kids) {
                collect(k, out);
            }
        }
    }

    private static PDEmbeddedFile embeddedFile(PDComplexFileSpecification fs) {
        if (fs == null) {
            return null;
        }
        PDEmbeddedFile ef = fs.getEmbeddedFile();
        if (ef == null) {
            ef = fs.getEmbeddedFileUnicode();
        }
        return ef;
    }

    private static Document parseXml(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false); // attributes are unprefixed; simplest to read by local name
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            d.getDocumentElement().normalize();
            return d;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ANAF XML", e);
        }
    }

    private static List<Element> elements(Document dom, String tag) {
        NodeList nl = dom.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    private static String attrOfRoot(Document dom, String name) {
        return dom.getDocumentElement().getAttribute(name);
    }

    private static YearMonth period(String luna, String an) {
        return YearMonth.of(Integer.parseInt(an.trim()), Integer.parseInt(luna.trim()));
    }

    private static LocalDate parseDate(String s, YearMonth period) {
        if (s == null || s.isBlank()) {
            return defaultDeadline(period);
        }
        try {
            return LocalDate.parse(s.trim(), DMY);
        } catch (RuntimeException e) {
            return defaultDeadline(period);
        }
    }

    private static BigDecimal amount(String s) {
        if (s == null || s.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(s.trim().replace(',', '.'));
    }

    private static BigDecimal amountOrNull(String s) {
        return (s == null || s.isBlank()) ? null : amount(s);
    }
}
