package ro.myfinance.settings.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Picks the <b>treasury residence</b> a company pays into, from what ANAF knows about it.
 *
 * <p>Why this is not simply "the locality": most companies sit in a commune that has no treasury of its
 * own. ANAF reports MERIC's fiscal domicile as <i>Sat Floreşti Com. Floreşti</i> and PRO ASSET's as
 * <i>Sat Sânpetru Com. Sânpetru</i> — neither is in the 202-entry treasury reference. The usable signal is
 * ANAF's {@code organFiscalCompetent} ("Administraţia Judeţeană a Finanţelor Publice Cluj"), i.e. the
 * administration that actually handles the taxpayer; a county administration pays into the county-seat
 * treasury. Confirmed against a real payment: PRO ASSET (Sânpetru, Braşov) paid "Trezoreria Brasov".
 *
 * <p>Every candidate is checked against the <em>actual</em> reference list rather than assumed, so a name
 * we guess wrong is simply skipped instead of producing an IBAN for the wrong treasury.
 */
@Service
public class TreasuryResidenceResolver {

    /** Words in an ANAF authority name that are not the place. */
    private static final List<String> AUTHORITY_WORDS = List.of(
            "administratia", "administraţia", "judeteana", "judeţeana", "judeţeană", "judeteană",
            "a", "finantelor", "finanţelor", "publice", "serviciul", "fiscal", "municipal",
            "orasenesc", "orăşenesc", "oraseneasca", "comunal", "sector", "directia", "direcţia",
            "generala", "generală", "regionala", "regională", "contribuabili", "mijlocii", "mari",
            "de", "pentru", "si", "şi");

    /**
     * Counties whose treasury seat is NOT spelled like the county. For the rest ("Braşov" → "Brasov")
     * plain normalisation already matches, so they are deliberately absent.
     */
    private static final Map<String, String> COUNTY_SEAT = Map.ofEntries(
            Map.entry("cluj", "Cluj Napoca"),
            Map.entry("bistrita nasaud", "Bistrita"),
            Map.entry("caras severin", "Resita"),
            Map.entry("covasna", "Sfantu Gheorghe"),
            Map.entry("dambovita", "Targoviste"),
            Map.entry("dolj", "Craiova"),
            Map.entry("gorj", "Targu Jiu"),
            Map.entry("harghita", "Miercurea Ciuc"),
            Map.entry("hunedoara", "Deva"),
            Map.entry("ialomita", "Slobozia"),
            Map.entry("maramures", "Baia Mare"),
            Map.entry("mehedinti", "Drobeta Tr.Severin"),
            Map.entry("mures", "Targu Mures"),
            Map.entry("neamt", "Piatra Neamt"),
            Map.entry("olt", "Slatina"),
            Map.entry("prahova", "Ploiesti"),
            Map.entry("salaj", "Zalau"),
            Map.entry("teleorman", "Alexandria"),
            Map.entry("timis", "Timisoara"),
            Map.entry("valcea", "Ramnicu Valcea"),
            Map.entry("vrancea", "Focsani"),
            Map.entry("arges", "Pitesti"),
            Map.entry("bihor", "Oradea"),
            Map.entry("alba", "Alba Iulia"));

    private final PlatformTreasuryService treasury;

    public TreasuryResidenceResolver(PlatformTreasuryService treasury) {
        this.treasury = treasury;
    }

    /**
     * The best treasury residence for a taxpayer, or empty when nothing matches the reference list.
     * Candidates are tried most-specific first: the competent fiscal authority names the office that
     * actually administers the company; its own locality only matches when it happens to host a treasury
     * (e.g. Turda); the county seat is the catch-all for a commune.
     */
    public Optional<String> resolve(String organFiscalCompetent, String locality, String county,
                                    LocalDate period) {
        List<String> known = treasury.listEffective(period).stream()
                .map(a -> a.getResidence())
                .filter(r -> r != null && !r.isBlank())
                .toList();
        if (known.isEmpty()) {
            return Optional.empty();
        }
        for (String candidate : candidates(organFiscalCompetent, locality, county)) {
            Optional<String> hit = match(candidate, known);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** Candidate place names, most specific first. */
    private List<String> candidates(String organFiscalCompetent, String locality, String county) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String office = placeOf(organFiscalCompetent);
        if (office != null) {
            out.add(office);
            // "…Finanţelor Publice Cluj" names the COUNTY administration → the county-seat treasury.
            String seat = COUNTY_SEAT.get(PlatformTreasuryService.normalizeResidence(office));
            if (seat != null) {
                out.add(seat);
            }
        }
        if (locality != null && !locality.isBlank()) {
            out.add(stripPlacePrefixes(locality));
        }
        if (county != null && !county.isBlank()) {
            String c = PlatformTreasuryService.normalizeResidence(county);
            out.add(county);
            String seat = COUNTY_SEAT.get(c);
            if (seat != null) {
                out.add(seat);
            }
        }
        return out;
    }

    private Optional<String> match(String candidate, List<String> known) {
        String target = PlatformTreasuryService.normalizeResidence(candidate);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        return known.stream()
                .filter(r -> PlatformTreasuryService.normalizeResidence(r).equals(target))
                .findFirst();
    }

    /** The place inside an ANAF authority name: drop the administrative words, keep the rest. */
    static String placeOf(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        StringBuilder place = new StringBuilder();
        for (String word : authority.trim().split("\\s+")) {
            String norm = PlatformTreasuryService.normalizeResidence(word);
            if (norm.isEmpty() || AUTHORITY_WORDS.contains(norm)) {
                continue;
            }
            place.append(place.isEmpty() ? "" : " ").append(word);
        }
        String result = place.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** "Sat Floreşti Com. Floreşti" → "Floreşti": drop the sat/comuna/oraş/municipiu markers. */
    static String stripPlacePrefixes(String locality) {
        String s = locality
                // The dot sits OUTSIDE the word boundary: "Com." must go entirely, dot included.
                .replaceAll("(?i)\\b(sat|comuna|com|ora[sş]|oraşul|municipiul|mun)\\b\\.?", " ")
                .replaceAll("[.,]", " ")
                .replaceAll("\\s+", " ").trim();
        // "Floreşti Floreşti" → "Floreşti": the commune name repeats the village name.
        String[] parts = s.split(" ");
        if (parts.length == 2 && parts[0].equalsIgnoreCase(parts[1])) {
            return parts[0];
        }
        return s.isEmpty() ? locality : s;
    }
}
