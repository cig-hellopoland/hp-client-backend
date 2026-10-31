package pl.hellopoland.service.api.partner;

import pl.hellopoland.service.AnalyticsService;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import java.io.File;
import java.util.Date;

@Stateless
public class AnalyticsServicePartnerAPI {
  @Inject
  AnalyticsService service;

  @RolesAllowed("partner")
  public File getOrdersCsvFile(Date fromDate, Date toDate) {
    return service.getPartnerOrdersCsvFile(fromDate, toDate);
  }

    @RolesAllowed("partner")
    public Object getSales(Date fromDate, Date toDate) {
        return service.getSales(fromDate, toDate);
    }
}
