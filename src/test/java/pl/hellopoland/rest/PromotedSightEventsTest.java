package pl.hellopoland.rest;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import pl.hellopoland.config.SightEventPagedCollectionConfig;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.rest.dto.PagedCollection;
import pl.hellopoland.rest.helpdesk.HelpdeskSightEventRestService;
import pl.hellopoland.rest.market.MarketSightEventRestService;
import pl.hellopoland.service.api.helpdesk.SightEventServiceHelpdeskAPI;
import pl.hellopoland.service.api.market.SightEventServiceMarketAPI;

public class PromotedSightEventsTest {
  private static class Accepted extends RuntimeException {}

  @Test
  public void acceptsAllEightPositions() throws Exception {
    HelpdeskSightEventRestService rest = new HelpdeskSightEventRestService();
    final Integer[] saved = {null};
    inject(rest, new SightEventServiceHelpdeskAPI() {
      @Override public void setPromotion(Long id, Integer promotion) {
        assertEquals(Long.valueOf(123), id);
        saved[0] = promotion;
        throw new Accepted(); // Stop before building the container-specific HTTP response.
      }
    });
    for (int position = 1; position <= 8; position++) {
      try {
        rest.setPromotion(123L, position);
        fail("Expected service call");
      } catch (Accepted expected) {
        assertEquals(Integer.valueOf(position), saved[0]);
      }
    }
  }

  @Test
  public void rejectsPositionsOutsideTheRange() {
    HelpdeskSightEventRestService rest = new HelpdeskSightEventRestService();
    for (Integer position : new Integer[] {null, -1, 0, 9, 100}) {
      try {
        rest.setPromotion(123L, position);
        fail("Invalid position accepted: " + position);
      } catch (ConflictingException expected) {
        assertTrue(expected.getMessage().contains("1 to 8"));
      }
    }
  }

  @Test
  public void portalRequestsAllEightPositionsInOrder() throws Exception {
    MarketSightEventRestService rest = new MarketSightEventRestService();
    final SightEventPagedCollectionConfig[] received = {null};
    inject(rest, new SightEventServiceMarketAPI() {
      @Override public PagedCollection<SightEventDTO> getList(SightEventPagedCollectionConfig config) {
        received[0] = config;
        return null;
      }
    });
    rest.promoted("pl-PL");
    assertNotNull(received[0]);
    assertEquals(Integer.valueOf(8), received[0].getPageSize());
    assertEquals("e.promotion, e.id asc", received[0].getOrder());
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), received[0].getConditions().stream()
        .filter(entry -> "promotions".equals(entry.parameterName)).findFirst().get().value);
    assertNotNull(received[0].getDateFrom());
  }

  private static void inject(Object rest, Object service) throws Exception {
    Field field = rest.getClass().getDeclaredField("service");
    field.setAccessible(true);
    field.set(rest, service);
  }
}
