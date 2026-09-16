package pl.hellopoland.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import pl.hellopoland.bo.*;
import pl.hellopoland.bo.Order.Status;
import pl.hellopoland.bo.UserRole.Role;
import pl.hellopoland.dto.EmailSendingReportDTO;
import pl.hellopoland.dto.TicketDefinitionDTO;
import pl.hellopoland.dto.TicketPoolDefinitionDTO;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.exception.email.EmailSendingException;
import pl.hellopoland.exception.notfound.ResourceNotFoundException;
import pl.hellopoland.rest.dto.OrderIRO;
import pl.hellopoland.rest.dto.OrderIRO.OrderEntryIRO;
import pl.hellopoland.security.JwtVerificator;
import pl.hellopoland.tpay.TPayClient;
import pl.hellopoland.tpay.dto.TransactionCreated;
import pl.hellopoland.util.HelloTicket;
import pl.hellopoland.util.PaymentUtils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

@LocalBean
@Stateless
public class OrderService extends ServiceSuperclass {

  @Inject
  SightEventService seService;
  @Inject
  TPayClient tPayClient;
  @Inject
  JwtVerificator jwtVerificator;
  @Inject
  PostalCodeDictionaryLookup pcd;
  @Inject
  PromotionCodeService promotionCodeService;

    private void enrichOrderDetails(OrderDetails d) {
        if (d == null) return;

        boolean missing =
                (d.getVoivodeship() == null || d.getVoivodeship().isBlank()) ||
                        (d.getCounty() == null || d.getCounty().isBlank()) ||
                        (d.getCommune() == null || d.getCommune().isBlank());

        if (!missing) return;

        if (d.getZipCode() == null || d.getZipCode().isBlank()) return;
        if (d.getCity() == null || d.getCity().isBlank()) return;

        pcd.findByZipAndCity(d.getZipCode(), d.getCity()).ifPresent(ad -> {
            if (d.getCommune() == null || d.getCommune().isBlank()) {
                d.setCommune(ad.commune());
            }
            if (d.getCounty() == null || d.getCounty().isBlank()) {
                d.setCounty(ad.county());
            }
            if (d.getVoivodeship() == null || d.getVoivodeship().isBlank()) {
                d.setVoivodeship(ad.voivodeship());
            }
        });
    }


  public Order create(OrderIRO iro) {
    //throwIfExpiredTickets(iro);
    throwIfTicketPricesDontMatch(iro);
    writeSomeLogs(iro);
    Order o = new Order();
    o.setUser(getLoggedUser());
    iro.details.setUserLogged(o.getUser() != null);

    enrichOrderDetails(iro.details);
    o.setDetails(iro.details);
    o.setDetails(iro.details);
    em.persist(o);
    Set<Long> atnaIds =
        iro.entries.stream().filter(oe -> oe.quantity != null && oe.quantity.compareTo(0) > 0)
            .collect(groupingBy(oeIRO -> oeIRO.id)).keySet();
    HelloTicket hpt = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
    List<TicketDefinitionDTO> tds = hpt.getTicketDefinitionsMarket(atnaIds);

    throwIfExpiredTickets(iro,tds);

    Map<Long, List<TicketDefinitionDTO>> ticketsGroupedBySight =
        tds.stream().collect(Collectors.groupingBy(td -> td.sightEventId));
    for (Map.Entry<Long, List<TicketDefinitionDTO>> entry : ticketsGroupedBySight.entrySet()) {
      OrderSightEntry ose = new OrderSightEntry();
      ose.setOrder(o);
      SightEvent sightEvent = seService.getByHptId(entry.getKey());
      if (!sightEvent.isAccessible()) {
        throw new ConflictingException("Sight Event is not accessible.");
      }
      ose.setSightEvent(sightEvent);
      em.persist(ose);
      ose.setAgreements(new ArrayList<>(sightEvent.getAgreements()));
      em.flush();
      Map<Long, TicketDefinitionDTO> ticketIdToObject =
          entry.getValue().stream()
              .collect(Collectors.toMap(td -> td.atnaId, td -> td));
      Set<Long> ticketIdsOfSight = ticketIdToObject.keySet();
      Map<Date, List<OrderEntryIRO>> inSightGroupedByDate =
          iro.entries.stream().filter(oeIRO -> ticketIdsOfSight.contains(oeIRO.id))
              .collect(groupingBy(oeIRO -> oeIRO.date));
      for (Map.Entry<Date, List<OrderEntryIRO>> inSightOnDate : inSightGroupedByDate.entrySet()) {
        if (!inSightOnDate.getValue().isEmpty()) {
          OrderDateEntry dateEntry = new OrderDateEntry();
          dateEntry.setDate(inSightOnDate.getKey());
          dateEntry.setSightEntry(ose);
          em.persist(dateEntry);
          for (OrderEntryIRO oeIRO : inSightOnDate.getValue()) {
            TicketDefinitionDTO ticket = ticketIdToObject.get(oeIRO.id);
            OrderEntry oe = new OrderEntry();
            oe.setName(ticket.name);
            oe.setQuantity(oeIRO.quantity);
            oe.setUnitPrice(ticket.originalPrice);
            if (ticket.discount != null) {
              oe.setDiscount(new Discount());
              oe.getDiscount().setPrice(ticket.discount.price);
              oe.getDiscount().setHplPart(ticket.discount.hplPart);
              oe.getDiscount().setPartnerPart(ticket.discount.partnerPart);
            }
            oe.setDateEntry(dateEntry);
            oe.setExternalDefinitionId(ticket.id);
            oe.setPoolId(ticket.poolId);
            if (oeIRO.partnerAffiliateCode != null
                && !oeIRO.partnerAffiliateCode.equals(sightEvent.getPartner().getAffiliateCode())) {
              logger.log(Level.ERROR,
                  "Kod afiliacyjny zamówienia [" + oeIRO.partnerAffiliateCode
                      + "] niezgodny z kodem afiliacyjnym partnera [id="
                      + sightEvent.getPartner().getId() + "]");
              throw new ConflictingException("Niezgodny kod afiliacyjny");
            }
            oe.setPartnerAffiliateCode(oeIRO.partnerAffiliateCode);
            em.persist(oe);
          }
        }
      }
    }
    em.flush();
    promotionCodeService.attachReservationToOrder(iro.promotionReservationToken, o);
    try {
      placeInExternalAPI(o);
    } catch (Exception e) {
      logger.log(Level.ERROR, e.getMessage());
      throw new ConflictingException(orderReservationErrorMessage(e));
    }
    TransactionCreated transactionCreated = createPayment(o);
    o.setTPayPaymentId(transactionCreated.transactionId);
    o.setTPayPaymentStatement(transactionCreated.title);
    o.setTPayPaymentUrl(transactionCreated.transactionPaymentUrl);
    logger.log(Level.INFO, "Returned order id=" + o.getId() + "; payment= " + transactionCreated.transactionPaymentUrl);
    logger.log(Level.INFO, "-------End creating order --------");
    return o;
  }

  String orderReservationErrorMessage(Exception exception) {
    String message = exception != null ? exception.getLocalizedMessage() : null;
    if (message == null || message.isBlank()
        || "Nieznany błąd.".equalsIgnoreCase(message.trim())
        || message.contains("Placing order in HPT returned null response")) {
      return "Nie udało się zarezerwować biletów w wybranym terminie. "
          + "Sprawdź dostępność terminu i spróbuj ponownie.";
    }

    String lowerMessage = message.toLowerCase(Locale.ROOT);
    if (lowerMessage.contains("poza zakresem definicji puli")
        || lowerMessage.contains("wydarzenie nie jest cykliczne")) {
      return "Wybrany termin nie jest dostępny dla jednego z biletów. "
          + "Wróć do koszyka, wybierz inny termin i spróbuj ponownie.";
    }
    if (lowerMessage.contains("brak dostępnych biletów")) {
      return "Bilety na wybrany termin zostały wyprzedane. "
          + "Wróć do koszyka i wybierz inny termin lub ofertę.";
    }
    return message.replaceAll("^\"|\"$", "");
  }

  private String getAckPaymentURL(Order o) {
    var url = properties.getProperty("base.url");
    if (!url.endsWith("/")) {
      url = url.concat("/");
    }
    return url.concat("market/orders/" + o.getHash() + "/ackPayment");
  }

    private TransactionCreated createPayment(Order o) {
        var entries = gatherOrderEntries(o.getEntries());

        int amount = entries.stream()
                .collect(Collectors.summingInt(oe -> (oe.getRealPrice() == null ? 0 : oe.getRealPrice()) *
                        (oe.getQuantity() == null ? 0 : oe.getQuantity())));

        logger.log(Logger.Level.INFO,"TPAY createPayment: orderId=" + o.getId()
                + " sightEntries=" + (o.getEntries() == null ? -1 : o.getEntries().size())
                + " orderEntries=" + entries.size()
                + " amount=" + amount);

        entries.stream().limit(5).forEach(oe ->
                logger.log(Logger.Level.INFO,"TPAY entry: id=" + oe.getId()
                        + " qty=" + oe.getQuantity()
                        + " realPrice=" + oe.getRealPrice()
                        + " unitPrice=" + oe.getUnitPrice())
        );

        if (amount > 0) {
            BigDecimal totalPrice = new BigDecimal(amount).divide(new BigDecimal(100));
            String description = "Zamówienie nr " + o.getId();
            String ackUrl = getAckPaymentURL(o);
            OrderDetails details = o.getDetails();
            return tPayClient.createTransaction(description, o.getHash(), ackUrl, totalPrice,
                    details.getEmail(), details.getFirstName() + " " + details.getLastName(),
                    OrderDetails.Platform.WIDGET.equals(details.getPlatform()));
        }
        return new TransactionCreated();
    }

  private void writeSomeLogs(OrderIRO iro) {
    logger.log(Level.INFO, "-------Start creating order --------");
    logger.log(Level.INFO, "Order details: " + iro.details.getEmail() + " "
        + iro.details.getFirstName() + " " + iro.details.getLastName());
    var orderEntriesLog = new StringBuilder();
    iro.entries.forEach(entry -> orderEntriesLog.append("[").append("date:").append(entry.date)
        .append("; quantity:").append(entry.quantity).append("; partnerAffiliateCode:")
        .append(entry.partnerAffiliateCode).append("; TicketDefinition id:").append(entry.id)
        .append("];\n"));
    logger.log(Level.INFO, "Order entries: " + orderEntriesLog.toString());
  }

    private void throwIfTicketPricesDontMatch(OrderIRO iro) {
        List<OrderEntryIRO> entriesWithPrice = iro.entries.stream()
                .filter(oe -> oe.price != null)
                .filter(oe -> oe.quantity != null && oe.quantity > 0)   // <-- KLUCZOWE
                .collect(Collectors.toList());

        Set<Long> ids = entriesWithPrice.stream()
                .map(oe -> oe.id)
                .collect(Collectors.toSet());

        if (!ids.isEmpty()) {
            HelloTicket hpt = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
            List<TicketDefinitionDTO> tds = hpt.getTicketDefinitionsMarket(ids);

            if (tds == null) { // <-- zabezpieczenie na null z HT
                throw new ConflictingException("Nie udało się zweryfikować cen biletów (brak danych z HelloTicket).");
            }

            for (TicketDefinitionDTO td : tds) {
                for (OrderEntryIRO oe : entriesWithPrice) {             // <-- iteruj tylko po kupowanych
                    if (oe.id.equals(td.atnaId)) {
                        if (!oe.price.equals(td.price)) {
                            throw new ConflictingException("Cena biletu " + td.atnaId + ": " + td.name + " uległa zmianie.");
                        }
                        break;
                    }
                }
            }
        }
    }

  private void throwIfExpiredTickets(OrderIRO iro) {
      var expiredIds = new HashMap<Long, OrderEntryIRO>();

    if (iro == null || iro.entries == null || iro.entries.isEmpty()) {
          throw new IllegalArgumentException("Order entries must not be null or empty");
      }

    iro.entries.forEach(entry -> {
          if (entry.date.before(new Date())) {
              expiredIds.put(entry.id, entry);
          }
      });

    List<TicketDefinitionDTO> expiredTickets = Collections.emptyList();

    if (expiredIds.size() > 0) {
      HelloTicket hpt = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
      expiredTickets = hpt.getTicketDefinitions(expiredIds.keySet());
      List<TicketPoolDefinitionDTO> wholeDayPools = hpt.getWholeDay(expiredTickets.stream()
          .map(td -> td.poolId)
          .collect(Collectors.toList()));
      for (var pool : wholeDayPools) {
        for (var iter = expiredTickets.iterator(); iter.hasNext(); ) {
          TicketDefinitionDTO ticket = iter.next();
          if (Objects.equals(pool.id, ticket.poolId)
              && !LocalDate
              .ofInstant(expiredIds.get(ticket.atnaId).date.toInstant(), ZoneId.systemDefault())
              .isBefore(LocalDate.now())) {
            expiredIds.remove(ticket.atnaId);
            iter.remove();
          }
        }
      }
    }
    if (expiredIds.size() > 0) {
      var format = new SimpleDateFormat("YYYY-MM-dd HH:mm");
      var errMsg = new StringBuilder(
          "W swoim koszyku masz bilety na oferty, które już minęły. Przeterminowane bilety:");
      expiredTickets.forEach(t -> errMsg
          .append("\n" + t.name + ", data: " + format.format(expiredIds.get(t.atnaId).date)
              + ", oferta: " + t.sightEventId + ";"));
      System.out.println(errMsg.toString());
      throw new ConflictingException(errMsg.toString());
    }
  }

  // em.refreshes are because of strange NPEs
  private void placeInExternalAPI(Order o) {
    em.refresh(o);
    logger.log(Logger.Level.INFO,
        "Checking if any of order sight entries ought to be placed in external API");
    Map<Portal, List<OrderSightEntry>> groupedByPortal = groupByPortal(o);
    for (var entry : groupedByPortal.entrySet()) {
      Portal portal = entry.getKey();
      logger.log(Logger.Level.INFO, "Placing external order in " + portal.getName());
      switch (portal.getType()) {
        case HELLOTICKET_CLOUD_1:
          placeInHpt(o.getDetails(), entry);
          break;
      }
    }
  }

  private Map<Portal, List<OrderSightEntry>> groupByPortal(Order order) {
    return order.getEntries().stream().filter(ose -> ose.getSightEvent().getPortal() != null)
        .collect(groupingBy(ose -> ose.getSightEvent().getPortal()));
  }

  public void sudoAck(String hash) {
    Order order = findByHash(hash);
    confirm(order);
  }

  private static List<OrderEntry> gatherOrderEntries(Collection<OrderSightEntry> collection) {
    List<OrderEntry> orderEntries = new ArrayList<>();
    for (OrderSightEntry se : collection) {
      for (OrderDateEntry de : se.getEntries()) {
        orderEntries.addAll(de.getEntries());
      }
    }
    return orderEntries;
  }

  public void confirmInExternalAPI(Order o) {
    logger.log(Logger.Level.INFO,
        "Checking if any of order sight entries ought to be confirmed in external API");
    Map<Portal, List<OrderSightEntry>> groupedByPortal = groupByPortal(o);
    for (var entry : groupedByPortal.entrySet()) {
      Portal portal = entry.getKey();
      logger.log(Logger.Level.INFO, "Confirming external order in " + portal.getName());

      switch (portal.getType()) {
        case HELLOTICKET_CLOUD_1:
          confirmInHpt(entry);
          break;
      }
    }
  }

  private void confirmInHpt(Map.Entry<Portal, List<OrderSightEntry>> entry) {
    Portal portal = entry.getKey();
    List<OrderEntry> orderEntries = gatherOrderEntries(entry.getValue());
    String serialNumber =
        entry.getValue().stream().map(OrderSightEntry::getSerialNumber).findFirst().get();
    HelloTicket hpt = new HelloTicket(portal.getUrl());
    hpt.confirm(serialNumber, orderEntries);
  }

  private void placeInHpt(OrderDetails details, Entry<Portal, List<OrderSightEntry>> entry) {
    Portal portal = entry.getKey();
    List<OrderEntry> orderEntries = gatherOrderEntries(entry.getValue());
    HelloTicket hpt = new HelloTicket(portal.getUrl());
    JsonObject resp = (JsonObject) hpt.book(details, orderEntries);
    if (resp == null) {
      throw new ConflictingException("Placing order in HPT returned null response.");
    }
    Integer externalOrderId = resp.getInt("id");
    entry.getValue().forEach(ose -> ose.setExternalId(externalOrderId.longValue()));
    em.flush();
  }

  private List<OrderEntry> gatherOrderEntries(List<OrderSightEntry> list) {
    List<OrderEntry> returnList = new ArrayList<>();
    for (OrderSightEntry se : list) {
      em.refresh(se);
      for (OrderDateEntry de : se.getEntries()) {
        em.refresh(de);
        returnList.addAll(de.getEntries());
      }
    }
    return returnList;
  }

  public OrderDateEntry getOrderDateEntryForLoggedUser(long id) {
    String queryString =
        "from OrderDateEntry where deleted=false and id=:id and sightEntry.order.user=:user";
    OrderDateEntry de = em.createQuery(queryString, OrderDateEntry.class).setParameter("id", id)
        .setParameter("user", getLoggedUser()).getSingleResult();

    de.getEntries().forEach(e -> e.getNumbers().size());
    return de;
  }

  public List<OrderDateEntry> getOrderSightDateEntriesInTheFutureForLoggedUser() {
    Date nowPlusOneDay = new Date(new Date().getTime() - TimeUnit.DAYS.toMillis(1));
    List<OrderDateEntry> osdes = em.createQuery(
            "from OrderDateEntry osde join fetch osde.sightEntry ose join fetch ose.sightEvent s join fetch ose.order o where osde.deleted=false and o.user=:user and o.status=:status and osde.date>=:date order by osde.date asc",
            OrderDateEntry.class)
        .setParameter("user", getLoggedUser())
        .setParameter("status", Order.Status.CONFIRMED)
        .setParameter("date", nowPlusOneDay)
        .getResultList();
    osdes.forEach(osde -> osde.getEntries().size());
    return osdes;
  }

  public List<OrderDateEntry> getOrderSightDateEntriesInThePastForLoggedUser() {
    Date nowPlusOneDay = new Date(new Date().getTime() - TimeUnit.DAYS.toMillis(1));
    List<OrderDateEntry> osdes = em.createQuery(
            "from OrderDateEntry osde join fetch osde.sightEntry ose join fetch ose.sightEvent s join fetch ose.order o where osde.deleted=false and o.user=:user and o.status=:status and osde.date<:date order by osde.date asc",
            OrderDateEntry.class)
        .setParameter("user", getLoggedUser())
        .setParameter("status", Order.Status.CONFIRMED)
        .setParameter("date", nowPlusOneDay)
        .getResultList();
    osdes.forEach(osde -> osde.getEntries().size());
    return osdes;
  }

  public void deleteOrderDateEntryForLoggedUser(long id) {
    getOrderDateEntryForLoggedUser(id).setDeleted(true);
  }

  private void cancelInExternalAPI(Order order) {
    logger.log(Logger.Level.INFO,
        "Checking if any of order sight entries ought to be cancelled in external API");
    Map<Portal, List<OrderSightEntry>> groupedByPortal = groupByPortal(order);
    for (Map.Entry<Portal, List<OrderSightEntry>> entry : groupedByPortal.entrySet()) {
      Portal portal = entry.getKey();
      logger.log(Logger.Level.INFO, "Cancelling external order in " + portal.getName());

      // they have same id. should have
      /* Long id = */
      entry.getValue().stream().map(OrderSightEntry::getExternalId).findFirst().get();
      // TODO cancel in HPT
    }
  }

  private Order findByHash(String hash) {
    return em.createQuery("from Order where hash=:hash", Order.class).setParameter("hash", hash)
        .getSingleResult();
  }

  public void cancel(Order o) {
    o.setStatus(Order.Status.CANCELLED);
    promotionCodeService.cancelOrderPromotions(o);
    cancelInExternalAPI(o);
    // TODO send mail or something
  }

  private void problem(Order order) {
    order.setStatus(Status.PROBLEM);
    promotionCodeService.releaseOrderPromotions(order);
    // TODO handle failure
  }

  public void confirm(Order order) {
    confirmInExternalAPI(order);
    promotionCodeService.markOrderPromotionsAsUsed(order);
    order.setStatus(Status.CONFIRMED);
  }

  public Status getStatus(String hash) {
    return findByHash(hash).getStatus();
  }

  public List<Order> getOrdersInDateRangeAndStatus(Date fromDate, Date toDate, Status status) {
    return em
        .createQuery("from Order where (:fromDate <= date and :toDate > date) and status = :status",
            Order.class)
        .setParameter("toDate", toDate)
        .setParameter("fromDate", fromDate).setParameter("toDate", new Date())
        .setParameter("status", status).getResultList();
  }

  public List<OrderEntry> getOrdersInDateRange(Date fromDate, Date toDate, Partner partner) {
    if (toDate == null) {
      return getOrderForSightEventInDate(fromDate, partner);
    }
    String query = "from OrderEntry oe join oe.dateEntry.sightEntry.order o "
        + "where (:fromDate <= o.date and :toDate > o.date) "
        + (partner != null ? "and oe.dateEntry.sightEntry.sightEvent.partner =:partner " : "")
        + "order by o.date asc, o.id asc";
    return getOrderEntries(query, fromDate, toDate, partner);
  }

  private List<OrderEntry> getOrderForSightEventInDate(Date fromDate, Partner partner) {
    var toDate = new Date(fromDate.getTime());
    String query = "from OrderEntry oe join oe.dateEntry.sightEntry.order o "
        + "where (:fromDate <= oe.dateEntry.date and :toDate > oe.dateEntry.date) "
        + (partner != null ? "and oe.dateEntry.sightEntry.sightEvent.partner =:partner " : "")
        + "order by o.date asc, o.id asc";
    return getOrderEntries(query, fromDate, toDate, partner);
  }

  @SuppressWarnings("deprecation")
  private List<OrderEntry> getOrderEntries(String query, Date fromDate, Date toDate,
      Partner partner) {
    toDate.setDate(toDate.getDate() + 1);
    TypedQuery<OrderEntry> tQuery = em.createQuery(query, OrderEntry.class)
        .setParameter("fromDate", fromDate).setParameter("toDate", toDate);
    if (partner != null) {
      tQuery.setParameter("partner", partner);
    }
    return tQuery.getResultList();
  }

  public EmailSendingReportDTO sendTicketCopy(String hash) {
    var order = findByHash(hash);
    EmailSendingReportDTO report = sendTicketsCopyByExternalAPI(order, null);
    String clientEmail = order.getDetails().getEmail();
    if (report.validUnsentAddresses != null && report.validUnsentAddresses.length > 0) {
      Arrays.stream(report.validUnsentAddresses).filter(address -> clientEmail.equals(address))
          .findAny().orElseThrow(() -> new EmailSendingException(
              "Wystąpił błąd podczas wysyłania kopii biletów do " + clientEmail));
    }
    if (report.invalidAddresses != null && report.invalidAddresses.length > 0) {
      Arrays.stream(report.invalidAddresses).filter(address -> clientEmail.equals(address))
          .findAny().orElseThrow(() -> new EmailSendingException(
              "Wystąpił błąd podczas wysyłania kopii biletów do " + clientEmail));
    }
    return report;
  }

  public EmailSendingReportDTO sendTicketCopyToEmail(String hash, String email) {
    var order = findByHash(hash);
    String recipientEmail = email == null ? null : email.trim();
    if (recipientEmail == null || recipientEmail.isEmpty()) {
      throw new EmailSendingException("Podaj poprawny adres e-mail.");
    }

    EmailSendingReportDTO report = sendTicketsCopyByExternalAPI(order, recipientEmail);
    if (report == null) {
      throw new EmailSendingException(
          "Wystąpił błąd podczas wysyłania kopii biletów do " + recipientEmail);
    }
    if (report.validUnsentAddresses != null
        && Arrays.stream(report.validUnsentAddresses)
            .anyMatch(recipientEmail::equalsIgnoreCase)) {
      throw new EmailSendingException(
          "Wystąpił błąd podczas wysyłania kopii biletów do " + recipientEmail);
    }
    if (report.invalidAddresses != null
        && Arrays.stream(report.invalidAddresses)
            .anyMatch(recipientEmail::equalsIgnoreCase)) {
      throw new EmailSendingException(
          "Wystąpił błąd podczas wysyłania kopii biletów do " + recipientEmail);
    }
    return report;
  }

  private EmailSendingReportDTO sendTicketsCopyByExternalAPI(Order order, String recipientEmail) {
    Map<Portal, List<OrderSightEntry>> groupedByPortal = groupByPortal(order);
    for (var entry : groupedByPortal.entrySet()) {
      Portal portal = entry.getKey();
      switch (portal.getType()) {
        case HELLOTICKET_CLOUD_1:
          return sendTicketsCopyByHpt(portal, entry.getValue(), recipientEmail);
      }
    }
    return null;
  }

  private EmailSendingReportDTO sendTicketsCopyByHpt(Portal portal, List<OrderSightEntry> ose,
      String recipientEmail) {
    String serialNumber = ose.stream().map(OrderSightEntry::getSerialNumber).findFirst()
        .orElseThrow(() -> new ResourceNotFoundException());
    HelloTicket hpt = new HelloTicket(portal.getUrl());
    User loggedUser = getLoggedUser();
    if (hasHelpdeskSystemRole(loggedUser)) {
      if (recipientEmail != null) {
        return hpt.sendTicketsCopyByAdmin(serialNumber, recipientEmail, getHelpdeskHptToken());
      }
      return hpt.sendTicketsCopyByAdmin(serialNumber, getHelpdeskHptToken());
    }
    return hpt.sendTicketsCopyByPartner(serialNumber, loggedUser.getPartner().getHptToken());
  }

  public void anonymizeOrdersForUser(User user) {
    em.createQuery("from Order where user=:user",
            Order.class)
        .setParameter("user", user).getResultStream()
        .forEach(o -> {
          OrderDetails details = o.getDetails();
          if (details != null) {
            details.setFirstName("anon");
            details.setLastName("anon");
            details.setStreet("anon");
            details.setPhone("anon");
          }
        });
  }

  public boolean ack(String hash, String ack, String jws) throws Exception {
    logger.log(Logger.Level.INFO, "Got ack from TPay");
    if (!jwtVerificator.verify(jws, ack)) {
      return false;
    }
    Map<String, String> ackMap = PaymentUtils.queryToMap(ack);
    String status = ackMap.get("tr_status");
    boolean success = "true".equalsIgnoreCase(status);
    // Temporary TST diagnostics: selected fields only, after JWS validation.
    logger.log(Logger.Level.INFO, "TPay notification diagnostic: "
        + JsonNodeFactory.instance.objectNode()
            .put("orderHash", hash)
            .put("tr_crc", ackMap.get("tr_crc"))
            .put("tr_status", status)
            .put("tr_id", ackMap.get("tr_id")));
    if (hash == null || !hash.equals(ackMap.get("tr_crc"))
        || !(success || "chargeback".equals(status))) {
      logger.log(Logger.Level.WARNING, "Rejected TPay notification: invalid order reference or status");
      return false;
    }
    Order order = findByHash(hash);
    // Serialize callbacks and read the latest committed status after acquiring the lock.
    em.refresh(order, LockModeType.PESSIMISTIC_WRITE);
    if (order.getTPayPaymentStatement() == null
        || !order.getTPayPaymentStatement().equals(ackMap.get("tr_id"))) {
      logger.log(Logger.Level.WARNING, "Rejected TPay notification: transaction mismatch; orderId=" + order.getId());
      return false;
    }
    if (success) {
      if (order.getStatus() == Status.CONFIRMED) {
        logger.log(Logger.Level.INFO, "TPay payment already confirmed; orderId=" + order.getId());
      } else {
        logger.log(Logger.Level.INFO, "confirming payment; orderId=" + order.getId());
        confirm(order);
      }
    } else if (order.getStatus() != Status.PROBLEM) {
      logger.log(Logger.Level.INFO, "payment problem; orderId=" + order.getId());
      problem(order);
    }
    return true;
  }

    public Order findBySerialNumber(String serialNumber) {
        return em.createQuery(
                        "select distinct o from Order o " +
                                "join o.sightEntries ose " +
                                "where ose.serialNumber = :serialNumber",
                        Order.class
                )
                .setParameter("serialNumber", serialNumber)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException());
    }

    public EmailSendingReportDTO sendTicketCopyBySerialNumber(String serialNumber) {
        var order = findBySerialNumber(serialNumber);
        return sendTicketCopy(order.getHash());
    }

    private void throwIfExpiredTickets(OrderIRO iro, List<TicketDefinitionDTO> tds) {
        if (iro == null || iro.entries == null || iro.entries.isEmpty()) {
            throw new IllegalArgumentException("Order entries must not be null or empty");
        }

        Map<Long, TicketDefinitionDTO> ticketByAtnaId = tds.stream()
                .collect(Collectors.toMap(td -> td.atnaId, td -> td));

        // 🔥 pobieramy poolId
        Set<Long> poolIds = tds.stream()
                .map(td -> td.poolId)
                .collect(Collectors.toSet());

        HelloTicket hpt = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
        List<TicketPoolDefinitionDTO> pools = hpt.getWholeDay(new ArrayList<>(poolIds));

        // 🔥 mapa poolId → wholeDay
        Map<Long, Boolean> poolWholeDay = pools.stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.wholeDay));

        Date now = new Date();
        LocalDate today = LocalDate.now();

        Map<Long, OrderEntryIRO> expiredIds = new HashMap<>();

        for (OrderEntryIRO entry : iro.entries) {
            if (entry.quantity == null || entry.quantity <= 0) {
                continue;
            }

            TicketDefinitionDTO td = ticketByAtnaId.get(entry.id);

            logger.log(Level.INFO, "ATNA=" + entry.id +
                    " poolId=" + (td != null ? td.poolId : null) +
                    " wholeDay=" + (td != null ? poolWholeDay.get(td.poolId) : null) +
                    " date=" + entry.date);

            boolean isWholeDay = false;
            if (td != null && td.poolId != null) {
                isWholeDay = poolWholeDay.containsKey(td.poolId);
            }

            if (isWholeDay) {
                LocalDate entryDate = entry.date.toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();

                if (entryDate.isBefore(today)) {
                    expiredIds.put(entry.id, entry);
                }
            } else {
                if (entry.date.before(now)) {
                    expiredIds.put(entry.id, entry);
                }
            }
        }

        if (!expiredIds.isEmpty()) {
            var format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            var errMsg = new StringBuilder(
                    "W swoim koszyku masz bilety na oferty, które już minęły. Przeterminowane bilety:");

            for (Map.Entry<Long, OrderEntryIRO> e : expiredIds.entrySet()) {
                TicketDefinitionDTO td = ticketByAtnaId.get(e.getKey());
                if (td != null) {
                    errMsg.append("\n")
                            .append(td.name)
                            .append(", data: ")
                            .append(format.format(e.getValue().date))
                            .append(", oferta: ")
                            .append(td.sightEventId)
                            .append(";");
                }
            }

            throw new ConflictingException(errMsg.toString());
        }
    }

}
