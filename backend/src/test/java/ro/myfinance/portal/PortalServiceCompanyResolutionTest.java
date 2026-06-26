package ro.myfinance.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.portal.application.PortalService;

/**
 * The portal must resolve a representative's active company server-side and only ever honour a company
 * the user is actually linked to — a client-supplied id outside their assignments is rejected.
 */
class PortalServiceCompanyResolutionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COMPANY_A = UUID.randomUUID();
    private static final UUID COMPANY_B = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID(); // a company the rep is NOT linked to

    private CompanyRepository companies;
    private RepresentativeLinkRepository repLinks;
    private AppUserRepository users;
    private HttpServletRequest request;

    @BeforeEach
    void setup() {
        companies = mock(CompanyRepository.class);
        repLinks = mock(RepresentativeLinkRepository.class);
        users = mock(AppUserRepository.class);
        request = mock(HttpServletRequest.class);

        AppUser active = mock(AppUser.class);
        lenient().when(active.getStatus()).thenReturn(ro.myfinance.access.domain.UserStatus.ACTIVE);
        Company alpha = company(COMPANY_A, "Alpha SRL");
        Company beta = company(COMPANY_B, "Beta SRL");
        lenient().when(users.findById(USER)).thenReturn(Optional.of(active));
        lenient().when(repLinks.findByUserId(USER)).thenReturn(List.of(
                new RepresentativeLink(TENANT, USER, COMPANY_A),
                new RepresentativeLink(TENANT, USER, COMPANY_B)));
        lenient().when(companies.findById(COMPANY_A)).thenReturn(Optional.of(alpha));
        lenient().when(companies.findById(COMPANY_B)).thenReturn(Optional.of(beta));

        TenantContext.set(new TenantContext.Identity(TENANT, USER, Role.REPRESENTATIVE, COMPANY_A));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Company company(UUID id, String name) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        lenient().when(c.getLegalName()).thenReturn(name);
        lenient().when(c.getCui()).thenReturn("1234567");
        return c;
    }

    private PortalService service() {
        return new PortalService(companies, null, null, null, null, null, null, null, repLinks, users, request);
    }

    @Test
    void honoursTheRequestedCompanyWhenLinked() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(COMPANY_B.toString());
        PortalService.CompanyInfo me = service().me();
        assertThat(me.companyId()).isEqualTo(COMPANY_B);
        assertThat(me.companies()).extracting(PortalService.CompanyOption::companyId)
                .containsExactlyInAnyOrder(COMPANY_A, COMPANY_B);
    }

    @Test
    void rejectsACompanyTheRepIsNotLinkedTo() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(OUTSIDER.toString());
        assertThatThrownBy(() -> service().me()).isInstanceOf(NotFoundException.class);
    }

    @Test
    void fallsBackToTheJwtCompanyWhenNoHeader() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(null);
        assertThat(service().me().companyId()).isEqualTo(COMPANY_A); // JWT company_id, still linked
    }

    @Test
    void deactivatedRepresentativeIsLockedOut() {
        AppUser inactive = mock(AppUser.class);
        when(inactive.getStatus()).thenReturn(ro.myfinance.access.domain.UserStatus.INACTIVE);
        when(users.findById(USER)).thenReturn(Optional.of(inactive));
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(null);
        assertThatThrownBy(() -> service().me()).isInstanceOf(NotFoundException.class);
    }
}
