package pl.hellopoland.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.hellopoland.bo.Order;
import pl.hellopoland.bo.OrderDateEntry;
import pl.hellopoland.bo.OrderDetails;
import pl.hellopoland.bo.OrderEntry;
import pl.hellopoland.bo.OrderSightEntry;
import pl.hellopoland.bo.Partner;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.bo.User;

public class AnalyticsOrdersCsvTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void periodExportDistinguishesCancelledAndConfirmedTickets() throws IOException {
    verifyStatuses(new Date());
  }

  @Test
  public void eventDayExportDistinguishesCancelledAndConfirmedTickets() throws IOException {
    verifyStatuses(null);
  }

  private void verifyStatuses(Date toDate) throws IOException {
    Partner partner = new Partner();
    partner.setId(12L);
    partner.setName("Partner testowy");
    User user = new User();
    user.setPartner(partner);
    OrderEntry confirmed = entry(partner, Order.Status.CONFIRMED, "confirmed", "Normalny", 2);
    OrderEntry cancelled = entry(partner, Order.Status.CANCELLED, "cancelled", "Normalny", 1);
    OrderEntry cancelledReduced = entry(partner, Order.Status.CANCELLED, "cancelled", "Ulgowy", 3);
    cancelledReduced.setDateEntry(cancelled.getDateEntry());
    List<OrderEntry> entries = List.of(confirmed, cancelled, cancelledReduced,
        entry(partner, Order.Status.NEW, "new", "Normalny", 1),
        entry(partner, Order.Status.PROBLEM, "problem", "Normalny", 1),
        entry(partner, null, "unknown", "Normalny", 1));
    Date fromDate = new Date();
    AnalyticsService service = new AnalyticsService() {
      @Override
      public User getLoggedUser() { return user; }
    };
    service.orderService = new OrderService() {
      @Override
      public List<OrderEntry> getOrdersInDateRange(Date from, Date to, Partner scopedPartner) {
        assertSame(fromDate, from);
        assertSame(toDate, to);
        assertSame(partner, scopedPartner);
        return entries;
      }
    };
    File csvFile = temporaryFolder.newFile("sales.csv");
    service.fileDescriptorService = new FileDescriptorService() {
      @Override
      public File createEmptyFileOnDisc(String path) { return csvFile; }
    };

    List<String> lines = Files.readAllLines(service.getOrdersCsvFile(fromDate, toDate).toPath());

    assertEquals(7, lines.size());
    String[] header = lines.get(0).split(";");
    assertEquals(19, header.length);
    assertEquals("NR ZAMÓWIENIA", header[0]);
    assertEquals("FAKTURA", header[17]);
    assertEquals("STATUS ZAMÓWIENIA", header[18]);
    String[] labels = {"Potwierdzone", "Anulowane", "Anulowane", "Nowe", "Problem", "Nieznany"};
    for (int i = 0; i < entries.size(); i++) {
      String[] row = lines.get(i + 1).split(";");
      assertEquals(header.length, row.length);
      assertEquals(entries.get(i).getDateEntry().getSightEntry().getOrder().getHash(), row[0]);
      assertEquals(entries.get(i).getQuantity().toString(), row[14]);
      assertEquals(entries.get(i).getName(), row[15]);
      assertEquals(labels[i], row[18]);
    }
  }

  private OrderEntry entry(Partner partner, Order.Status status, String hash, String name, int quantity) {
    Order order = new Order();
    order.setHash(hash);
    order.setStatus(status);
    OrderDetails details = new OrderDetails();
    details.setFirstName("Jan");
    details.setLastName("Testowy");
    details.setEmail("test@example.invalid");
    details.setPhone("123456789");
    order.setDetails(details);
    SightEvent event = new SightEvent();
    event.setName("Oferta testowa");
    event.setPartner(partner);
    OrderSightEntry sightEntry = new OrderSightEntry();
    sightEntry.setOrder(order);
    sightEntry.setSightEvent(event);
    OrderDateEntry dateEntry = new OrderDateEntry();
    dateEntry.setSightEntry(sightEntry);
    dateEntry.setDate(new Date());
    OrderEntry entry = new OrderEntry();
    entry.setDateEntry(dateEntry);
    entry.setName(name);
    entry.setQuantity(quantity);
    entry.setUnitPrice(1000);
    return entry;
  }
}
