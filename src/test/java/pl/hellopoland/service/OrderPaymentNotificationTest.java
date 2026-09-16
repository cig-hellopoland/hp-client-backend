package pl.hellopoland.service;

import jakarta.ejb.EJBException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import pl.hellopoland.bo.Order;
import pl.hellopoland.rest.market.MarketOrderRestService;
import pl.hellopoland.security.JwtVerificator;
import pl.hellopoland.service.api.market.OrderServiceMarketAPI;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

/** Exercises the REST/API/service path without an application server or external requests. */
public class OrderPaymentNotificationTest {
  private static final String HASH = "bac22639-9ead-4ce8-aca1-44b88eb26678";
  private static final String TRANSACTION = "TR-5YGJ-10H00Y4X";
  private final Order order = new Order();
  private final RecordingOrderService service = new RecordingOrderService();
  private final OrderServiceMarketAPI api = new OrderServiceMarketAPI();
  private final MarketOrderRestService rest = new MarketOrderRestService();
  private int lookups;
  private int promotionUses;
  private int promotionReleases;
  private boolean locked;
  private boolean confirmedWhileWaiting;
  private boolean lockFails;
  private String verifiedBody;

  @Before
  public void setUp() throws Exception {
    order.setHash(HASH);
    order.setTPayPaymentStatement(TRANSACTION);
    service.jwtVerificator = new JwtVerificator() {
      @Override
      public boolean verify(String signature, String body) {
        verifiedBody = body;
        return "valid-signature".equals(signature);
      }
    };
    service.promotionCodeService = new PromotionCodeService() {
      @Override
      public void markOrderPromotionsAsUsed(Order value) { promotionUses++; }
      @Override
      public void releaseOrderPromotions(Order value) { promotionReleases++; }
    };
    TypedQuery<?> query = (TypedQuery<?>) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[]{TypedQuery.class}, (proxy, method, args) -> {
          if (method.getName().equals("setParameter")) return proxy;
          if (method.getName().equals("getSingleResult")) return order;
          throw new UnsupportedOperationException(method.getName());
        });
    service.em = (EntityManager) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
          if (method.getName().equals("createQuery")) { lookups++; return query; }
          if (method.getName().equals("refresh")) {
            assertSame(order, args[0]);
            assertEquals(LockModeType.PESSIMISTIC_WRITE, args[1]);
            if (lockFails) throw new EJBException("simulated lock failure");
            locked = true;
            if (confirmedWhileWaiting) order.setStatus(Order.Status.CONFIRMED);
            return null;
          }
          throw new UnsupportedOperationException(method.getName());
        });
    inject(api, "service", service);
    inject(rest, "service", api);
  }

  @Test
  public void confirmsOnceAndReturnsExactPlainTextTrueOnEveryRetry() {
    String body = body("true");
    for (int i = 0; i < 3; i++) assertAccepted(rest.ackPayment(HASH, "valid-signature", body));
    assertEquals(body, verifiedBody);
    assertEquals(Order.Status.CONFIRMED, order.getStatus());
    assertEquals(1, service.confirmations);
    assertEquals(1, promotionUses);
  }

  @Test
  public void acceptsUppercaseSandboxStatusAndRetriesWithDifferentLetterCase() {
    for (String status : new String[]{"TRUE", "True", "true"}) {
      String notification = body(status);
      assertAccepted(rest.ackPayment(HASH, "valid-signature", notification));
      assertEquals(notification, verifiedBody);
      assertEquals(Order.Status.CONFIRMED, order.getStatus());
    }
    assertEquals(1, service.confirmations);
    assertEquals(1, promotionUses);
    assertEquals(0, promotionReleases);
  }

  @Test
  public void acceptsPreviouslyConfirmedOrderWithoutCallingTicketSystem() {
    order.setStatus(Order.Status.CONFIRMED);
    assertAccepted(notifyPayment());
    assertEquals(0, service.confirmations);
    assertEquals(0, promotionUses);
  }

  @Test
  public void usesCommittedStatusAfterWaitingForAnotherCallback() {
    confirmedWhileWaiting = true;
    assertAccepted(notifyPayment());
    assertTrue(locked);
    assertEquals(0, service.confirmations);
  }

  @Test
  public void rejectsInvalidSignatureEvenForConfirmedOrder() {
    order.setStatus(Order.Status.CONFIRMED);
    assertRejected(rest.ackPayment(HASH, "invalid-signature", body("true")));
    assertEquals(0, lookups);
  }

  @Test
  public void rejectsMissingSignature() {
    assertRejected(rest.ackPayment(HASH, null, body("true")));
    assertEquals(0, lookups);
  }

  @Test
  public void rejectsNotificationSignedForAnotherOrder() {
    assertRejected(rest.ackPayment(HASH, "valid-signature", body("true").replace(HASH, "other-order")));
    assertEquals(0, lookups);
  }

  @Test
  public void rejectsAnotherTransactionEvenForConfirmedOrder() {
    order.setStatus(Order.Status.CONFIRMED);
    assertRejected(rest.ackPayment(HASH, "valid-signature", body("true").replace(TRANSACTION, "other-transaction")));
    assertEquals(0, service.confirmations);
  }

  @Test
  public void rejectsMissingTransactionReference() {
    order.setTPayPaymentStatement(null);
    assertRejected(notifyPayment());
    assertEquals(0, service.confirmations);
  }

  @Test
  public void rejectsUnknownOrMissingStatusWithoutChangingOrder() {
    assertRejected(rest.ackPayment(HASH, "valid-signature", body("unknown")));
    assertRejected(rest.ackPayment(HASH, "valid-signature", "tr_crc=" + HASH + "&tr_id=" + TRANSACTION));
    assertEquals(Order.Status.NEW, order.getStatus());
    assertEquals(0, lookups);
    assertEquals(0, promotionReleases);
  }

  @Test
  public void rejectsMalformedBodyWithoutChangingOrder() {
    assertRejected(rest.ackPayment(HASH, "valid-signature", "malformed"));
    assertEquals(0, lookups);
    assertEquals(Order.Status.NEW, order.getStatus());
  }

  @Test
  public void returnsFailureOnExternalErrorAndAllowsRetry() {
    service.failConfirmation = true;
    assertRejected(notifyPayment());
    assertEquals(Order.Status.NEW, order.getStatus());
    assertEquals(0, promotionUses);
    service.failConfirmation = false;
    assertAccepted(notifyPayment());
    assertEquals(Order.Status.CONFIRMED, order.getStatus());
  }

  @Test
  public void returnsFailureWhenDatabaseLockFails() {
    lockFails = true;
    assertRejected(notifyPayment());
    assertEquals(0, service.confirmations);
  }

  @Test
  public void doesNotAcknowledgeTransactionCompletionFailure() throws Exception {
    inject(api, "service", new OrderService() {
      @Override
      public boolean ack(String hash, String body, String signature) {
        throw new EJBException("simulated transaction completion failure");
      }
    });
    assertRejected(notifyPayment());
  }

  @Test
  public void preservesChargebackHandlingAndSkipsRepeatedRelease() {
    order.setStatus(Order.Status.CONFIRMED);
    assertAccepted(rest.ackPayment(HASH, "valid-signature", body("chargeback")));
    assertAccepted(rest.ackPayment(HASH, "valid-signature", body("chargeback")));
    assertEquals(Order.Status.PROBLEM, order.getStatus());
    assertEquals(1, promotionReleases);
    assertEquals(0, service.confirmations);
  }

  private Response notifyPayment() { return rest.ackPayment(HASH, "valid-signature", body("true")); }

  private String body(String status) {
    return "tr_status=" + status + "&tr_crc=" + HASH + "&tr_id=" + TRANSACTION;
  }

  private void assertAccepted(Response response) {
    assertEquals(200, response.getStatus());
    assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getMediaType());
    assertEquals("TRUE", response.getEntity());
  }

  private void assertRejected(Response response) {
    assertEquals(500, response.getStatus());
    assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getMediaType());
    assertEquals("FALSE", response.getEntity());
  }

  private static void inject(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private class RecordingOrderService extends OrderService {
    private int confirmations;
    private boolean failConfirmation;

    @Override
    public void confirmInExternalAPI(Order value) {
      assertTrue("Lock must precede external side effects", locked);
      confirmations++;
      if (failConfirmation) throw new IllegalStateException("simulated ticket service failure");
    }
  }
}
