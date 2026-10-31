package pl.hellopoland.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.lang.reflect.Proxy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;
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
import pl.hellopoland.bo.Discount;

public class AnalyticsOrdersCsvTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void periodExportDistinguishesCancelledAndConfirmedTickets() throws IOException {
    verifyStatuses(new Date(), false);
  }

  @Test
  public void eventDayExportDistinguishesCancelledAndConfirmedTickets() throws IOException {
    verifyStatuses(null, false);
  }

  @Test
  public void partnerPeriodExportRemovesOnlyRequestedColumns() throws IOException {
    verifyStatuses(new Date(), true);
  }

  @Test
  public void partnerEventDayExportRemovesOnlyRequestedColumns() throws IOException {
    verifyStatuses(null, true);
  }

  private void verifyStatuses(Date toDate, boolean partnerReport) throws IOException {
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

    List<String> lines = readCsv((partnerReport
        ? service.getPartnerOrdersCsvFile(fromDate, toDate)
        : service.getOrdersCsvFile(fromDate, toDate)).toPath());

    assertEquals(7, lines.size());
    String[] header = lines.get(0).split(";", -1);
    assertEquals(partnerReport ? 12 : 20, header.length);
    assertArrayEquals(new String[] {"NR ZAMÓWIENIA", "DATA ZAKUPU", "DATA WYDARZENIA",
        "NAZWA OFERTY", "NAZWA BILETU", "NAZWA UŻYTKOWNIKA", "ADRES EMAIL",
        "CENA BRUTTO BILETU", "PROMOCJA", "LICZBA SPRZEDANYCH BILETÓW",
        "WARTOŚĆ SPRZEDANYCH BILETÓW", "STATUS ZAMÓWIENIA"},
        java.util.Arrays.copyOf(header, 12));
    if (!partnerReport) {
      assertArrayEquals(new String[] {"ID PARTNERA HP", "NAZWA PARTNERA", "AFILIACJA",
          "PROWIZJA", "TELEFON", "PLATFORMA", "ZALOGOWANY", "FAKTURA"},
          java.util.Arrays.copyOfRange(header, 12, header.length));
    }
    String[] labels = {"Potwierdzone", "Anulowane", "Anulowane", "Nowe", "Problem", "Nieznany"};
    for (int i = 0; i < entries.size(); i++) {
      String[] row = lines.get(i + 1).split(";", -1);
      assertEquals(header.length, row.length);
      assertEquals(entries.get(i).getDateEntry().getSightEntry().getOrder().getHash(), row[0]);
      assertEquals(AnalyticsService.DATE_FORMATER.format(
          entries.get(i).getDateEntry().getSightEntry().getOrder().getDate()), row[1]);
      assertEquals(AnalyticsService.DATE_FORMATER.format(entries.get(i).getDateEntry().getDate()), row[2]);
      assertEquals("Zażółć gęślą jaźń", row[3]);
      assertEquals(entries.get(i).getName(), row[4]);
      assertEquals("Żaneta Łącka", row[5]);
      assertEquals("test@example.invalid", row[6]);
      assertEquals("10,00", row[7]);
      assertEquals("Nie", row[8]);
      assertEquals(entries.get(i).getQuantity().toString(), row[9]);
      assertEquals((10 * entries.get(i).getQuantity()) + ",00", row[10]);
      assertEquals(labels[i], row[11]);
      if (!partnerReport) {
        assertEquals("12", row[12]);
        assertEquals("Partner testowy", row[13]);
        assertEquals("123456789", row[16]);
      }
    }
  }

  @Test
  public void reportsPromotionForTheSpecificTicketInPartnerAndHelpdeskCsv() throws IOException {
    Partner partner = new Partner();
    partner.setId(12L);
    partner.setName("Partner testowy");
    OrderEntry promotional = entry(partner, Order.Status.CANCELLED, "mixed", "Bilet za 1 zł", 1);
    promotional.setUnitPrice(100);
    OrderEntry normal = entry(partner, Order.Status.CANCELLED, "mixed", "Normalny", 1);
    normal.setUnitPrice(100);
    normal.setDateEntry(promotional.getDateEntry());
    OrderEntry discounted = entry(partner, Order.Status.CONFIRMED, "discount", "Ulgowy", 2);
    Discount discount = new Discount();
    discount.setPrice(500);
    discounted.setDiscount(discount);
    List<OrderEntry> entries = List.of(promotional, normal, discounted);
    for (int i = 0; i < entries.size(); i++) entries.get(i).setId((long) i + 1);
    AnalyticsService service = new AnalyticsService() {
      @Override public User getLoggedUser() { return null; }
    };
    service.orderService = new OrderService() {
      @Override public List<OrderEntry> getOrdersInDateRange(Date from, Date to, Partner scopedPartner) {
        return entries;
      }
    };
    service.fileDescriptorService = new FileDescriptorService() {
      @Override public File createEmptyFileOnDisc(String path) {
        try { return temporaryFolder.newFile(); }
        catch (IOException e) { throw new AssertionError(e); }
      }
    };
    TypedQuery<?> query = (TypedQuery<?>) Proxy.newProxyInstance(
        TypedQuery.class.getClassLoader(), new Class<?>[] {TypedQuery.class}, (proxy, method, args) -> {
          if (method.getName().equals("setParameter")) {
            assertEquals("entryIds", args[0]);
            assertEquals(List.of(1L, 2L, 3L), args[1]);
            return proxy;
          }
          if (method.getName().equals("getResultList")) return List.of(1L);
          throw new AssertionError(method.getName());
        });
    service.em = (EntityManager) Proxy.newProxyInstance(
        EntityManager.class.getClassLoader(), new Class<?>[] {EntityManager.class}, (proxy, method, args) -> {
          if (method.getName().equals("createQuery")) {
            String hql = (String) args[0];
            HqlParser parser = new HqlParser(new CommonTokenStream(new HqlLexer(CharStreams.fromString(hql))));
            parser.statement();
            assertEquals(0, parser.getNumberOfSyntaxErrors());
            assertTrue(hql.contains("redemption.orderEntry.id in :entryIds"));
            assertFalse(hql.contains("status"));
            return query;
          }
          throw new AssertionError(method.getName());
        });
    for (boolean partnerReport : new boolean[] {false, true}) {
      List<String> lines = readCsv((partnerReport
          ? service.getPartnerOrdersCsvFile(new Date(), new Date())
          : service.getOrdersCsvFile(new Date(), new Date())).toPath());
      int promotionColumn = List.of(lines.get(0).split(";")).indexOf("PROMOCJA");
      assertEquals("Tak", lines.get(1).split(";")[promotionColumn]);
      assertEquals("Nie", lines.get(2).split(";")[promotionColumn]);
      assertEquals("Tak", lines.get(3).split(";")[promotionColumn]);
      assertEquals("1,00", lines.get(1).split(";")[7]);
      assertEquals("1,00", lines.get(1).split(";")[10]);
      assertEquals("5,00", lines.get(3).split(";")[7]);
      assertEquals("2", lines.get(3).split(";")[9]);
      assertEquals("10,00", lines.get(3).split(";")[10]);
    }
  }

  private List<String> readCsv(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
        java.util.Arrays.copyOf(bytes, 3));
    List<String> lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(lines.get(0).startsWith("\uFEFFNR ZAMÓWIENIA"));
    lines.set(0, lines.get(0).substring(1));
    assertTrue(lines.stream().noneMatch(line -> line.contains("\uFEFF")));
    return lines;
  }

  private OrderEntry entry(Partner partner, Order.Status status, String hash, String name, int quantity) {
    Order order = new Order();
    order.setHash(hash);
    order.setStatus(status);
    OrderDetails details = new OrderDetails();
    details.setFirstName("Żaneta");
    details.setLastName("Łącka");
    details.setEmail("test@example.invalid");
    details.setPhone("123456789");
    order.setDetails(details);
    SightEvent event = new SightEvent();
    event.setName("Zażółć gęślą jaźń");
    event.setPartner(partner);
    OrderSightEntry sightEntry = new OrderSightEntry();
    sightEntry.setOrder(order);
    sightEntry.setSightEvent(event);
    OrderDateEntry dateEntry = new OrderDateEntry();
    dateEntry.setSightEntry(sightEntry);
    dateEntry.setDate(new Date(1791504000000L));
    OrderEntry entry = new OrderEntry();
    entry.setDateEntry(dateEntry);
    entry.setName(name);
    entry.setQuantity(quantity);
    entry.setUnitPrice(1000);
    return entry;
  }
}
