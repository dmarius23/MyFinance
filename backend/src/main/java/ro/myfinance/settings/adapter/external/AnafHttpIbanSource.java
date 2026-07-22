package ro.myfinance.settings.adapter.external;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.settings.application.AnafIbanSource;
import ro.myfinance.settings.application.TreasuryIbans;

/**
 * Live implementation of {@link AnafIbanSource}: HTTP (JDK {@link HttpClient}) + PDFBox text extraction,
 * driving {@link AnafIbanParser}. Walks the index → each county page → each treasury PDF, pulling the four
 * target IBANs. Failures are isolated per county/PDF (returned as {@code error} rows), so one bad page
 * never aborts the whole crawl. No new third-party dependency — JDK HTTP + PDFBox (already present).
 */
@Component
public class AnafHttpIbanSource implements AnafIbanSource {

    private static final Logger log = LoggerFactory.getLogger(AnafHttpIbanSource.class);
    private static final Pattern TREZ_STEM = Pattern.compile("iban_(TREZ[0-9A-Z_]+)\\.pdf", Pattern.CASE_INSENSITIVE);

    private final AnafIbanProperties props;
    private final HttpClient http;

    public AnafHttpIbanSource(AnafIbanProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<TreasuryIbans> fetchAll() {
        List<TreasuryIbans> out = new ArrayList<>();
        String indexHtml;
        try {
            indexHtml = getString(props.indexUrl());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not fetch ANAF IBAN index: " + e.getMessage(), e);
        }
        List<String> countyUrls = AnafIbanParser.countyPageUrls(indexHtml, props.baseUrl());
        log.info("ANAF IBAN sync: {} county pages discovered", countyUrls.size());

        for (String countyUrl : countyUrls) {
            String county = countyName(countyUrl);
            List<String> pdfLinks;
            try {
                pdfLinks = AnafIbanParser.pdfLinks(getString(countyUrl), props.baseUrl());
            } catch (RuntimeException e) {
                log.warn("ANAF IBAN sync: county {} failed: {}", county, e.getMessage());
                out.add(TreasuryIbans.error(county, null, countyUrl, e.getMessage()));
                continue;
            }
            for (String pdfUrl : pdfLinks) {
                out.add(fetchTreasury(county, pdfUrl));
                sleep();
            }
        }
        log.info("ANAF IBAN sync: {} treasuries scraped ({} errors)",
                out.size(), out.stream().filter(t -> !t.ok()).count());
        return out;
    }

    private TreasuryIbans fetchTreasury(String county, String pdfUrl) {
        String code = treasuryCode(pdfUrl);
        try {
            String text = extractText(getBytes(pdfUrl));
            return new TreasuryIbans(county, code, AnafIbanParser.residence(text), pdfUrl,
                    AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_5503),
                    AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_CAM),
                    AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_TVA_INTERN),
                    AnafIbanParser.ibanByCode(text, AnafIbanParser.CODE_TVA_EXTERN),
                    null);
        } catch (RuntimeException | IOException e) {
            log.warn("ANAF IBAN sync: treasury {} ({}) failed: {}", code, pdfUrl, e.getMessage());
            return TreasuryIbans.error(county, code, pdfUrl, e.getMessage());
        }
    }

    /** PDFBox text extraction — public so the parser test can exercise the real fixture PDF. */
    public static String extractText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String treasuryCode(String pdfUrl) {
        Matcher m = TREZ_STEM.matcher(pdfUrl);
        return m.find() ? m.group(1) : pdfUrl.substring(pdfUrl.lastIndexOf('/') + 1);
    }

    private static String countyName(String countyUrl) {
        String file = countyUrl.substring(countyUrl.lastIndexOf('/') + 1).replaceFirst("(?i)\\.htm$", "");
        return file.replace('_', ' ');
    }

    private String getString(String url) {
        try {
            HttpResponse<String> r = http.send(request(url), HttpResponse.BodyHandlers.ofString());
            requireOk(url, r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("GET " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + url, e);
        }
    }

    private byte[] getBytes(String url) {
        try {
            HttpResponse<byte[]> r = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
            requireOk(url, r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("GET " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + url, e);
        }
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .header("User-Agent", props.userAgent())
                .GET()
                .build();
    }

    private static void requireOk(String url, int status) {
        if (status / 100 != 2) {
            throw new IllegalStateException("GET " + url + " returned HTTP " + status);
        }
    }

    private void sleep() {
        long ms = props.delayMillis();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
