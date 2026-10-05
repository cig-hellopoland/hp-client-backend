package pl.hellopoland.service;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.junit.Test;
import pl.hellopoland.bo.Discount;
import pl.hellopoland.bo.Order;
import pl.hellopoland.bo.OrderDateEntry;
import pl.hellopoland.bo.OrderEntry;
import pl.hellopoland.bo.OrderSightEntry;
import pl.hellopoland.bo.Partner;
import pl.hellopoland.bo.User;
import pl.hellopoland.rest.dto.HelpdeskSalesRowORO;

public class AnalyticsServiceTest {

  private final AnalyticsService service = new AnalyticsService();

  @Test
  public void preservesTicketNamesAndQuantitiesForMultipleLinesOfOneOrder() {
    Order order = new Order();
    order.setId(1272L);
    OrderSightEntry sightEntry = new OrderSightEntry();
    sightEntry.setOrder(order);
    OrderDateEntry dateEntry = new OrderDateEntry();
    dateEntry.setSightEntry(sightEntry);
    OrderEntry normal = new OrderEntry();
    normal.setDateEntry(dateEntry);
    normal.setName("Normalny");
    normal.setQuantity(2);
    normal.setUnitPrice(800);
    OrderEntry reduced = new OrderEntry();
    reduced.setDateEntry(dateEntry);
    reduced.setName("Ulgowy");
    reduced.setQuantity(1);
    reduced.setUnitPrice(500);
    dateEntry.setEntries(List.of(normal, reduced));
    sightEntry.setEntries(List.of(dateEntry));
    order.setEntries(List.of(sightEntry));

    List<HelpdeskSalesRowORO> rows = salesFor(normal, reduced);

    assertEquals(2, rows.size());
    assertEquals(Long.valueOf(1272), rows.get(0).bookingId);
    assertEquals(rows.get(0).bookingId, rows.get(1).bookingId);
    assertEquals("Normalny", rows.get(0).ticketName);
    assertEquals(Integer.valueOf(2), rows.get(0).ticketQuantity);
    assertEquals(Integer.valueOf(800), rows.get(0).ticketPrice);
    assertEquals("Ulgowy", rows.get(1).ticketName);
    assertEquals(Integer.valueOf(1), rows.get(1).ticketQuantity);
    assertEquals(Integer.valueOf(2100), rows.get(0).orderValue);
    assertEquals(rows.get(0).orderValue, rows.get(1).orderValue);

    Discount discount = new Discount();
    discount.setPrice(650);
    normal.setDiscount(discount);
    rows = salesFor(normal, reduced);
    assertEquals(Integer.valueOf(650), rows.get(0).ticketPrice);
    assertEquals(Integer.valueOf(1800), rows.get(0).orderValue);
    assertEquals(rows.get(0).orderValue, rows.get(1).orderValue);
  }

  @Test
  public void includesAllOrderLinesAfterVoucherEvenWhenOnlyOneLineIsVisible() {
    Order order = new Order();
    order.setId(1273L);
    OrderSightEntry sightEntry = new OrderSightEntry();
    sightEntry.setOrder(order);
    OrderDateEntry dateEntry = new OrderDateEntry();
    dateEntry.setSightEntry(sightEntry);
    List<OrderEntry> entries = new java.util.ArrayList<>();
    for (int price : new int[] {0, 250, 800}) {
      OrderEntry entry = new OrderEntry();
      entry.setDateEntry(dateEntry);
      entry.setUnitPrice(800);
      entry.setQuantity(1);
      Discount discount = new Discount();
      discount.setPrice(price);
      entry.setDiscount(discount);
      entries.add(entry);
    }
    dateEntry.setEntries(entries);
    sightEntry.setEntries(List.of(dateEntry));
    order.setEntries(List.of(sightEntry));

    assertEquals(Integer.valueOf(1050), salesFor(entries.get(2)).get(0).orderValue);

    entries.forEach(entry -> entry.getDiscount().setPrice(0));
    assertEquals(Integer.valueOf(0), salesFor(entries.get(2)).get(0).orderValue);
  }

  private List<HelpdeskSalesRowORO> salesFor(OrderEntry... entries) {
    AnalyticsService reportService = new AnalyticsService() {
      @Override
      public User getLoggedUser() { return null; }
    };
    reportService.orderService = new OrderService() {
      @Override
      public List<OrderEntry> getOrdersInDateRange(Date from, Date to, Partner partner) {
        return List.of(entries);
      }
    };

    return reportService.getSales(new Date(), new Date(), null);
  }

  @Test
  public void calculatesCommissionWhenDiscountHasNoHelloPolandPart() {
    OrderEntry orderEntry = new OrderEntry();
    orderEntry.setUnitPrice(10000);
    orderEntry.setQuantity(2);
    orderEntry.setDiscount(new Discount());

    BigDecimal commission = service.calculateCommission(new BigDecimal("10"), orderEntry);

    assertEquals(new BigDecimal("20.00"), commission);
  }
}
