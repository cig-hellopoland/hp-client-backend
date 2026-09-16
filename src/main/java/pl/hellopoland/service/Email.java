package pl.hellopoland.service;

public class Email {

  public String recipientEmail;
  public String ccEmail;
  public String subject;
  public String msg;

  public Email(String recipientEmail, String subject, String msg) {
    this.recipientEmail = recipientEmail;
    this.subject = subject;
    this.msg = msg;
  }

  public Email(String recipientEmail, String ccEmail, String subject, String msg) {
    this(recipientEmail, subject, msg);
    this.ccEmail = ccEmail;
  }
}
