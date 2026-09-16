package pl.hellopoland.service.api.market;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import pl.hellopoland.bo.Order;
import pl.hellopoland.bo.Order.Status;
import pl.hellopoland.bo.OrderDateEntry;
import pl.hellopoland.rest.dto.OrderDateEntryORO;
import pl.hellopoland.rest.dto.OrderDateEntryOnListingORO;
import pl.hellopoland.rest.dto.OrderIRO;
import pl.hellopoland.service.OrderService;

import java.util.List;
import java.util.stream.Collectors;

@Stateless
public class OrderServiceMarketAPI {

  System.Logger logger = System.getLogger(OrderServiceMarketAPI.class.getName());

  @Inject
  OrderService service;

  @PermitAll
  public String create(OrderIRO iro) {
    Order order = service.create(iro);
    if (order.sumIsZero()) {
      service.confirm(order);
      return null;
    } else {
      return order.getTPayPaymentUrl();
    }
  }

  @PermitAll
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public boolean ack(String hash, String ack, String jws) {
    try {
      // OrderService owns the transaction: return success only after its commit.
      return service.ack(hash, ack, jws);
    } catch (Exception e) {
      logger.log(System.Logger.Level.WARNING, "Failed to ack payment", e);
      return false;
    }
  }

  @RolesAllowed("user")
  public List<OrderDateEntryOnListingORO> getActiveTicketGroupsForLoggedUser() {
    List<OrderDateEntry> bos = service.getOrderSightDateEntriesInTheFutureForLoggedUser();
    return bos.stream().map(OrderDateEntryOnListingORO::new).collect(Collectors.toList());
  }

  @RolesAllowed("user")
  public List<OrderDateEntryOnListingORO> getArchivedTicketGroupsForLoggedUser() {
    List<OrderDateEntry> bos = service.getOrderSightDateEntriesInThePastForLoggedUser();
    return bos.stream().map(OrderDateEntryOnListingORO::new).collect(Collectors.toList());
  }

  @RolesAllowed("user")
  public OrderDateEntryORO getTicketGroup(Long id) {
    OrderDateEntry bo = service.getOrderDateEntryForLoggedUser(id);
    return new OrderDateEntryORO(bo);
  }

  @RolesAllowed("user")
  public void deleteTicketGroup(Long id) {
    service.deleteOrderDateEntryForLoggedUser(id);
  }

  @PermitAll
  public Status checkStatus(String hash) {
    return service.getStatus(hash);
  }
}
