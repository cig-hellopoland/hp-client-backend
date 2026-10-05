package pl.hellopoland.service;

import org.apache.commons.lang3.RandomStringUtils;
import pl.hellopoland.bo.*;
import pl.hellopoland.bo.OrderDetails.Platform;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import pl.hellopoland.dto.SalesRowDTO;
import pl.hellopoland.rest.dto.HelpdeskSalesRowORO;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@LocalBean
@Stateless
public class AnalyticsService extends ServiceSuperclass {
  final static String PATH =
      properties.getProperty("dms.root.path") + File.separator + "analitics" + File.separator;
  final static SimpleDateFormat DATE_FORMATER = new SimpleDateFormat("yyyy-MM-dd HH:mm");
  final static BigDecimal HUNDRED = new BigDecimal("100");


  @Inject
  OrderService orderService;

  @Inject
  FileDescriptorService fileDescriptorService;

  public File getOrdersCsvFile(Date fromDate, Date toDate) {
    final File csvFile = fileDescriptorService.createEmptyFileOnDisc(
        PATH + "orders_" + RandomStringUtils.randomAlphanumeric(10) + ".csv");
    // csv file header:
    writeCsvRow(csvFile.toPath(), "NR ZAMÓWIENIA", "DATA ZAMÓWIENIA", "ID PARTNERA HP",
        "NAZWA PARTNERA", "AFILIACJA", "WARTOŚĆ", "PROWIZJA", "NAZWA UŻUTKOWNIKA", "TELEON", "ADRES EMAIL", "PLATFORMA",
        "ZALOGOWANY", "NAZWA OFERTY", "DATA OFERTY", "ILOŚĆ", "NAZWA BILETÓW", "PROMOCJA", "FAKTURA",
        "STATUS ZAMÓWIENIA");

    var orders = getOrdersInCurrentUserHelpdeskScope(fromDate, toDate, null);
    for (OrderEntry oe : orders) {
      OrderDateEntry dateEntry = oe.getDateEntry();
      OrderSightEntry sightEntry = dateEntry.getSightEntry();
      SightEvent sightEvent = sightEntry.getSightEvent();
      Partner partner = sightEvent.getPartner();
      Order order = sightEntry.getOrder();
      OrderDetails oDetails = order.getDetails();
      BigDecimal commissionPercent = partner.getCommission();
      Platform platform = oDetails.getPlatform();
      BigDecimal commission = calculateCommission(commissionPercent, oe);
      BigDecimal total = new BigDecimal(oe.getSum()).divide(HUNDRED);

      writeCsvRow(
          csvFile.toPath(),
          order.getHash(),
          DATE_FORMATER.format(order.getDate()),
          String.valueOf(partner.getId()),
          partner.getName(),
          oe.getPartnerAffiliateCode() != null ? "afiliacja" : "",
          String.valueOf(total).replace(".", ","),
          String.valueOf(commission).replace(".", ","),
          oDetails.getFirstName() + " " + oDetails.getLastName(),
          oDetails.getPhone(),
          oDetails.getEmail(),
          platform != null ? platform.name() : Platform.UNKNOWN.name(),
          String.valueOf(oDetails.isUserLogged()),
          sightEvent.getName(),
          DATE_FORMATER.format(dateEntry.getDate()),
          String.valueOf(oe.getQuantity()),
          oe.getName(),
          String.valueOf(oe.getDiscount() != null),
          String.valueOf(Boolean.TRUE.equals(oDetails.getInvoice())),
          getOrderStatusLabel(order.getStatus())
      );
    }
    return csvFile;
  }

  private String getOrderStatusLabel(Order.Status status) {
    if (status == null) {
      return "Nieznany";
    }
    return switch (status) {
      case NEW -> "Nowe";
      case CONFIRMED -> "Potwierdzone";
      case PROBLEM -> "Problem";
      case CANCELLED -> "Anulowane";
    };
  }

  BigDecimal calculateCommission(BigDecimal commissionPercent, OrderEntry oe) {
    if (commissionPercent == null) {
      return new BigDecimal(0);
    }
    var originalTotal = new BigDecimal(oe.getUnitPrice() * oe.getQuantity());
    BigDecimal commissionVal =
        originalTotal.multiply(commissionPercent).divide(HUNDRED).setScale(2,
            RoundingMode.HALF_EVEN);
    if (oe.getDiscount() != null && oe.getDiscount().getHplPart() != null) {
      commissionVal = commissionVal.subtract(new BigDecimal(oe.getDiscount().getHplPart()));
    }
    commissionVal = commissionVal.divide(HUNDRED);
    return commissionVal;
  }

  private void writeCsvRow(Path path, String... strings) {
    try (var writer =
        Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      for (String string : strings) {
        writer.append(string).append(";");
      }
      writer.newLine();
    } catch (IOException e) {
      logger.log(Level.ERROR, e.getLocalizedMessage());
    }
  }
    public Object getSales(Date fromDate, Date toDate) {

        var entries = getOrdersInCurrentUserHelpdeskScope(fromDate, toDate, null);

        return entries.stream()
                .collect(Collectors.groupingBy(oe -> {
                    OrderDateEntry dateEntry = oe.getDateEntry();
                    OrderSightEntry sightEntry = dateEntry.getSightEntry();
                    return sightEntry.getOrder();
                }))
                .values()
                .stream()
                .map(orderEntries -> {

                    OrderEntry oe = orderEntries.get(0);

                    OrderDateEntry dateEntry = oe.getDateEntry();
                    OrderSightEntry sightEntry = dateEntry.getSightEntry();
                    Order order = sightEntry.getOrder();
                    OrderDetails oDetails = order.getDetails();
                    SightEvent sightEvent = sightEntry.getSightEvent();

                    return new SalesRowDTO(
                            order.getId(),
                            order.getDate(),                          // data zakupu
                            dateEntry.getDate(),                      // data wydarzenia
                            oDetails.getFirstName() + " " + oDetails.getLastName(),
                            oDetails.getEmail(),
                            order.getStatus().name(),
                            order.getHash(),

                            sightEvent.getName(),                     // wydarzenie
                            sightEvent.getSight() != null
                                    ? sightEvent.getSight().getName()
                                    : "-"                             // obiekt
                    );
                })
                .collect(Collectors.toList());
    }

    public List<HelpdeskSalesRowORO> getSales(Date fromDate, Date toDate, Long partnerId) {
        var orders = getOrdersInCurrentUserHelpdeskScope(fromDate, toDate, partnerId);
        Map<Long, Integer> orderValues = new HashMap<>();

        return orders.stream()
                .map(oe -> {
                    OrderDateEntry dateEntry = oe.getDateEntry();
                    OrderSightEntry sightEntry = dateEntry.getSightEntry();
                    SightEvent sightEvent = sightEntry.getSightEvent();
                    Order order = sightEntry.getOrder();
                    OrderDetails details = order.getDetails();

                    String customerName = null;
                    if (details != null) {
                        String firstName = details.getFirstName() != null ? details.getFirstName() : "";
                        String lastName = details.getLastName() != null ? details.getLastName() : "";
                        customerName = (firstName + " " + lastName).trim();
                    }

                    return new HelpdeskSalesRowORO(
                            order.getId(),
                            order.getDate(),
                            dateEntry.getDate(),
                            customerName,
                            details != null ? details.getEmail() : null,
                            order.getStatus() != null ? order.getStatus().name() : null,
                            order.getHash(),
                            sightEvent != null ? sightEvent.getName() : null,
                            sightEvent != null && sightEvent.getSight() != null ? sightEvent.getSight().getName() : null,
                            oe.getRealPrice(),
                            oe.getName(),
                            oe.getQuantity(),
                            orderValues.computeIfAbsent(order.getId(), id -> order.getEntries()
                                    .stream().mapToInt(OrderSightEntry::getSum).sum())
                    );
                })
                .collect(Collectors.toList());
    }

    private List<OrderEntry> getOrdersInCurrentUserHelpdeskScope(Date fromDate, Date toDate,
        Long requestedPartnerId) {
        User user = getLoggedUser();
        Partner loggedPartner = user != null ? user.getPartner() : null;
        List<OrderEntry> orders = orderService.getOrdersInDateRange(fromDate, toDate, loggedPartner);

        if (user == null || loggedPartner != null) {
            return orders;
        }

        return orders.stream()
            .filter(oe -> isOrderEntryInRequestedPartner(oe, requestedPartnerId))
            .filter(oe -> isOrderEntryInHelpdeskScope(oe, user))
            .collect(Collectors.toList());
    }

    private boolean isOrderEntryInRequestedPartner(OrderEntry oe, Long requestedPartnerId) {
        if (requestedPartnerId == null) {
            return true;
        }
        return Optional.ofNullable(getOrderEntryPartner(oe))
            .map(Partner::getId)
            .filter(requestedPartnerId::equals)
            .isPresent();
    }

    private boolean isOrderEntryInHelpdeskScope(OrderEntry oe, User user) {
        if (user.hasRole(UserRole.Role.ADMIN)
            || user.hasRole(UserRole.Role.ROOT)
            || user.hasRole(UserRole.Role.SALESMAN)) {
            return true;
        }

        Set<Partner> allowedPartners = user.getAllowedHelpdeskPartners();
        Set<Sight> allowedSights = user.getAllowedHelpdeskSights();
        boolean hasPartnerScope = allowedPartners != null && !allowedPartners.isEmpty();
        boolean hasSightScope = allowedSights != null && !allowedSights.isEmpty();

        if (!hasPartnerScope && !hasSightScope) {
            return true;
        }

        Partner partner = getOrderEntryPartner(oe);
        Sight sight = getOrderEntrySight(oe);
        boolean partnerAllowed = !hasPartnerScope || (partner != null
            && allowedPartners.stream().anyMatch(allowed -> allowed.getId().equals(partner.getId())));
        boolean sightAllowed = !hasSightScope || (sight != null
            && allowedSights.stream().anyMatch(allowed -> allowed.getId().equals(sight.getId())));

        return partnerAllowed && sightAllowed;
    }

    private Partner getOrderEntryPartner(OrderEntry oe) {
        OrderSightEntry sightEntry = oe.getDateEntry().getSightEntry();
        SightEvent sightEvent = sightEntry.getSightEvent();
        return sightEvent != null ? sightEvent.getPartner() : null;
    }

    private Sight getOrderEntrySight(OrderEntry oe) {
        OrderSightEntry sightEntry = oe.getDateEntry().getSightEntry();
        SightEvent sightEvent = sightEntry.getSightEvent();
        return sightEvent != null ? sightEvent.getSight() : null;
    }

}
