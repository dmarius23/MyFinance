package ro.myfinance.extraction.adapter.external;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Parser for <b>ISO 20022 CAMT.053</b> ("BankToCustomerStatement") XML — the structured bank-statement
 * export offered by most banks. Preferred over PDF scraping: it carries explicit signed amounts
 * (CdtDbtInd), booked balances (OPBD/CLBD) and counterparty details, so it's exact and bank-agnostic —
 * one parser covers every bank that emits CAMT.053, regardless of statement layout.
 *
 * <p>Namespace-agnostic (matches by local element name) so it handles every camt.053.001.xx version.
 * XML parsing is hardened against XXE (no DTDs, no external entities), since statements are untrusted
 * input. Highest parser precedence, so the structured format always wins when present.
 */
@Component
@Order(1)
public class Camt053StatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(Camt053StatementParser.class);

    @Override
    public boolean supports(String text) {
        return text != null && (text.contains("camt.053") || text.contains("BkToCstmrStmt"));
    }

    @Override
    public ParsedStatement parse(String xml) {
        try {
            Document doc = parseXmlSecurely(xml);
            List<Element> stmts = descendants(doc.getDocumentElement(), "Stmt");
            if (stmts.isEmpty()) {
                return new ParsedStatement("CAMT", null, null, null, List.of());
            }
            String accountIban = firstText(stmts.get(0), "Acct", "Id", "IBAN");
            BigDecimal opening = balance(stmts.get(0), "OPBD", "PRCD");
            BigDecimal closing = balance(stmts.get(stmts.size() - 1), "CLBD", "CLAV");

            List<ParsedTransaction> txns = new ArrayList<>();
            for (Element stmt : stmts) {
                for (Element ntry : children(stmt, "Ntry")) {
                    ParsedTransaction t = parseEntry(ntry);
                    if (t != null) {
                        txns.add(t);
                    }
                }
            }
            return new ParsedStatement("CAMT", accountIban, opening, closing, txns);
        } catch (RuntimeException e) {
            log.warn("CAMT.053 parse failed", e);
            return new ParsedStatement("CAMT", null, null, null, List.of());
        }
    }

    /** One booked entry → a transaction (skips non-booked/pending entries). */
    private ParsedTransaction parseEntry(Element ntry) {
        String status = firstText(ntry, "Sts", "Cd");
        if (status == null) {
            status = firstText(ntry, "Sts");
        }
        if (status != null && !status.equalsIgnoreCase("BOOK")) {
            return null; // pending/expected/info — not a settled transaction
        }
        boolean credit = "CRDT".equalsIgnoreCase(firstText(ntry, "CdtDbtInd"));
        BigDecimal amount = money(firstText(ntry, "Amt"));
        if (amount == null) {
            return null;
        }
        if (!credit) {
            amount = amount.negate();
        }
        LocalDate date = date(firstText(ntry, "BookgDt", "Dt"), firstText(ntry, "BookgDt", "DtTm"),
                firstText(ntry, "ValDt", "Dt"), firstText(ntry, "ValDt", "DtTm"));

        // Counterparty is the party on the other side: creditor when we received, debtor when we paid.
        Element txDetails = firstChild(firstChild(ntry, "NtryDtls"), "TxDtls");
        String partyPath = credit ? "Dbtr" : "Cdtr";
        String acctPath = credit ? "DbtrAcct" : "CdtrAcct";
        String partnerName = txDetails == null ? null
                : firstText(firstChild(txDetails, "RltdPties"), partyPath, "Nm");
        String partnerIban = txDetails == null ? null
                : firstText(firstChild(txDetails, "RltdPties"), acctPath, "Id", "IBAN");

        String description = txDetails == null ? null : joinText(firstChild(txDetails, "RmtInf"), "Ustrd");
        if (description == null) {
            description = firstText(ntry, "AddtlNtryInf");
        }
        String ref = txDetails != null ? firstText(firstChild(txDetails, "Refs"), "EndToEndId") : null;
        if (ref == null || ref.isBlank() || "NOTPROVIDED".equalsIgnoreCase(ref)) {
            ref = firstText(ntry, "AcctSvcrRef");
        }
        return new ParsedTransaction(date, amount, blankToNull(partnerName), blankToNull(partnerIban),
                blankToNull(description), blankToNull(ref), null);
    }

    /** The value of the first balance whose type code matches one of the given codes (signed). */
    private BigDecimal balance(Element stmt, String... codes) {
        for (Element bal : children(stmt, "Bal")) {
            String code = firstText(bal, "Tp", "CdOrPrtry", "Cd");
            for (String want : codes) {
                if (want.equalsIgnoreCase(code)) {
                    BigDecimal v = money(firstText(bal, "Amt"));
                    if (v == null) {
                        return null;
                    }
                    return "DBIT".equalsIgnoreCase(firstText(bal, "CdtDbtInd")) ? v.negate() : v;
                }
            }
        }
        return null;
    }

    // ---- XML helpers (namespace-agnostic; DOM) ----

    private Document parseXmlSecurely(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CAMT.053 XML", e);
        }
    }

    /** Direct children of {@code parent} with the given local name. */
    private List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private Element firstChild(Element parent, String localName) {
        List<Element> c = children(parent, localName);
        return c.isEmpty() ? null : c.get(0);
    }

    /** Follow a chain of local names from {@code start}; first-child at each step. */
    private Element firstChild(Element start, String... path) {
        Element e = start;
        for (String name : path) {
            e = firstChild(e, name);
            if (e == null) {
                return null;
            }
        }
        return e;
    }

    /** Trimmed text at the end of a local-name path (e.g. Acct/Id/IBAN); null if absent. */
    private String firstText(Element start, String... path) {
        if (path.length == 0) {
            return start == null ? null : text(start);
        }
        Element e = firstChild(start, java.util.Arrays.copyOf(path, path.length - 1));
        Element leaf = firstChild(e, path[path.length - 1]);
        return leaf == null ? null : text(leaf);
    }

    /** Join all repeated leaf children (e.g. multiple RmtInf/Ustrd lines) with spaces. */
    private String joinText(Element parent, String localName) {
        List<Element> els = children(parent, localName);
        if (els.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Element e : els) {
            String t = text(e);
            if (t != null && !t.isBlank()) {
                sb.append(sb.length() > 0 ? " " : "").append(t.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nl = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    private String text(Element e) {
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    private LocalDate date(String... candidates) {
        for (String c : candidates) {
            if (c != null && c.length() >= 10) {
                try {
                    return LocalDate.parse(c.substring(0, 10));
                } catch (RuntimeException ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    /** Parse a decimal amount (CAMT uses a dot decimal, no grouping). */
    private BigDecimal money(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
