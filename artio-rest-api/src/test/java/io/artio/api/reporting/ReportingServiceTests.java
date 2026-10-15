package io.artio.api.reporting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.artio.api.licensing.EnterpriseFeature;
import io.artio.api.licensing.FeatureNotLicensedException;
import io.artio.api.licensing.LicensingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportingServiceTests {
    @Test
    void deniesAdvancedQueriesBeforeAccessingFinancialData() {
        var jdbc = mock(JdbcTemplate.class);
        var licensing = mock(LicensingService.class);
        doThrow(new FeatureNotLicensedException(EnterpriseFeature.ADVANCED_REPORTING)).when(licensing).require(EnterpriseFeature.ADVANCED_REPORTING);
        assertThatThrownBy(() -> new ReportingService(jdbc, licensing).advanced(30)).isInstanceOf(FeatureNotLicensedException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnboundedPeriodsBeforeDatabaseOrEntitlementAccess() {
        var jdbc = mock(JdbcTemplate.class);
        var licensing = mock(LicensingService.class);
        var service = new ReportingService(jdbc, licensing);
        for (int days : new int[] {0, -1, 366, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.standard(days)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.advanced(days)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc, licensing);
    }
}
