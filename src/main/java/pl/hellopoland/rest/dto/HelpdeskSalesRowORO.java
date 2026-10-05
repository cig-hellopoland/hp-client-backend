package pl.hellopoland.rest.dto;

import java.util.Date;

public class HelpdeskSalesRowORO {

  public Long bookingId;
  public Date purchaseDate;
  public Date eventDate;
  public String customerName;
  public String customerEmail;
  public String status;
  public String hash;
  public String sightEventName;
  public String objectName;
  public Integer ticketPrice;
  public String ticketName;
  public Integer ticketQuantity;
  public Integer orderValue;

  public HelpdeskSalesRowORO(Long bookingId, Date purchaseDate, Date eventDate,
      String customerName, String customerEmail, String status, String hash,
      String sightEventName, String objectName, Integer ticketPrice,
      String ticketName, Integer ticketQuantity, Integer orderValue) {
    this.bookingId = bookingId;
    this.purchaseDate = purchaseDate;
    this.eventDate = eventDate;
    this.customerName = customerName;
    this.customerEmail = customerEmail;
    this.status = status;
    this.hash = hash;
    this.sightEventName = sightEventName;
    this.objectName = objectName;
    this.ticketPrice = ticketPrice;
    this.ticketName = ticketName;
    this.ticketQuantity = ticketQuantity;
    this.orderValue = orderValue;
  }
}
