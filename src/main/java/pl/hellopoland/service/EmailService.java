package pl.hellopoland.service;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPTransport;

import java.io.UnsupportedEncodingException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;
import pl.hellopoland.exception.email.PasswordChangedEmailException;

@LocalBean
@Stateless
public class EmailService extends ServiceSuperclass {
  private final Logger logger = System.getLogger(this.getClass().getName());
  private static final String MAIL_PERSONAL = "Bilety Hello Poland";
  private static final String MAIL_USERNAME_PROPERTY = "mail.username";
  private static final String MAIL_PASSWORD_PROPERTY = "mail.password";
  private static final String MAIL_SMTP_HOST_PROPERTY = "mail.smtp.host";
  private static final String MAIL_SMTP_PORT_PROPERTY = "mail.smtp.port";
  private static final String MAIL_SMTP_AUTH_PROPERTY = "mail.smtp.auth";
  private static final String MAIL_SMTP_SOCKET_FACTORY_CLASS_PROPERTY =
      "mail.smtp.socketFactory.class";
  private static final String MAIL_SMTP_STARTTLS_ENABLE_PROPERTY = "mail.smtp.starttls.enable";
  private static final String MAIL_SMTP_ENABLED_PROPERTY = "mail.smtp.enabled";
  private static final String MAIL_REDIRECT_ALL_TO_PROPERTY = "mail.redirect.all.to";

  public void sendEmail(Email parameterObject)
      throws MessagingException, UnsupportedEncodingException {
    sendEmail(parameterObject, MAIL_PERSONAL, false);
  }

  public void sendEmail(Email parameterObject, String senderName)
      throws MessagingException, UnsupportedEncodingException {
    sendEmail(parameterObject, senderName, false);
  }

  public void sendHtmlEmail(Email parameterObject, String senderName)
      throws MessagingException, UnsupportedEncodingException {
    sendEmail(parameterObject, senderName, true);
  }

  public void sendPasswordChangedEmail(String login, String password) {
    String helpdesk = StringUtils.trimToNull(
        System.getProperty("mail.partner.credentials.recipient"));
    try {
      if (helpdesk == null) {
        throw new MessagingException("Missing mail.partner.credentials.recipient");
      }
      sendEmail(new Email(login, helpdesk, "Zmiana danych logowania - Hello Poland",
          "Dane logowania zostały zmienione.\nLogin: " + login + "\nHasło: " + password),
          "Hello Poland");
    } catch (MessagingException | UnsupportedEncodingException e) {
      logger.log(Level.ERROR, "Password change email failed for login: " + login, e);
      throw new PasswordChangedEmailException();
    }
  }

  void setRecipients(MimeMessage message, Email email) throws MessagingException {
    String recipient = redirectRecipientIfConfigured(email.recipientEmail);
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
    if (StringUtils.isNotBlank(email.ccEmail)) {
      String copyRecipient = redirectRecipientIfConfigured(email.ccEmail);
      if (!recipient.equalsIgnoreCase(copyRecipient)) {
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(copyRecipient));
      }
    }
  }

  private void sendEmail(Email parameterObject, String senderName, boolean html)
      throws MessagingException, UnsupportedEncodingException {
    String recipientEmail = redirectRecipientIfConfigured(parameterObject.recipientEmail);
    if (!Boolean.TRUE.toString().equals(System.getProperty(MAIL_SMTP_ENABLED_PROPERTY))) {
      logger.log(Level.WARNING, "SMTP Integration is disabled");
      logger.log(Level.INFO, "Email:\n"
          + "To: " + recipientEmail + "\n"
          + "Subject: " + parameterObject.subject + "\n"
          + "Body: [omitted]"
      );
      return;
    }

    var session = createSessionForEmail(getSessionProperties());
    var message = new MimeMessage(session);
    try {
      message
          .setFrom(new InternetAddress(System.getProperty(MAIL_USERNAME_PROPERTY), senderName));
      setRecipients(message, parameterObject);
      message.setSubject(parameterObject.subject, "UTF-8");
      var mimeBodyPart = new MimeBodyPart();
      if (html) {
        mimeBodyPart.setContent(parameterObject.msg, "text/html; charset=UTF-8");
      } else {
        mimeBodyPart.setText(parameterObject.msg, "UTF-8");
      }
      var multipart = new MimeMultipart();
      multipart.addBodyPart(mimeBodyPart);
      message.setContent(multipart);
      try (SMTPTransport transport = (SMTPTransport) session.getTransport("smtp")) {
        transport.connect();
        transport.setReportSuccess(false);
        transport.sendMessage(message, message.getAllRecipients());
        for (Address addr : message.getAllRecipients()) {
          logger.log(Level.INFO, "Email has been sent to " + addr);
        }
      }
    } catch (MessagingException | UnsupportedEncodingException e) {
      logger.log(System.Logger.Level.ERROR, "Sending an email failed: " + recipientEmail);
      logger.log(System.Logger.Level.ERROR, e.getLocalizedMessage());
      throw e;
    }
  }

  private String redirectRecipientIfConfigured(String recipientEmail) {
    String redirectEmail = System.getProperty(MAIL_REDIRECT_ALL_TO_PROPERTY);
    if (StringUtils.isBlank(redirectEmail)) {
      return recipientEmail;
    }

    redirectEmail = redirectEmail.trim();
    logger.log(Level.INFO,
        "Redirecting email recipient from " + recipientEmail + " to " + redirectEmail);
    return redirectEmail;
  }

  private Properties getSessionProperties() {
    Properties properties = new Properties();
    properties.put(MAIL_SMTP_HOST_PROPERTY, System.getProperty(MAIL_SMTP_HOST_PROPERTY));
    properties.put(MAIL_SMTP_PORT_PROPERTY, System.getProperty(MAIL_SMTP_PORT_PROPERTY));
    properties.put(MAIL_SMTP_AUTH_PROPERTY, System.getProperty(MAIL_SMTP_AUTH_PROPERTY));
    if (System.getProperty(MAIL_SMTP_SOCKET_FACTORY_CLASS_PROPERTY) != null) {
      properties.put(MAIL_SMTP_SOCKET_FACTORY_CLASS_PROPERTY,
          System.getProperty(MAIL_SMTP_SOCKET_FACTORY_CLASS_PROPERTY));
    }
    if (System.getProperty(MAIL_SMTP_STARTTLS_ENABLE_PROPERTY) != null) {
      properties.put(MAIL_SMTP_STARTTLS_ENABLE_PROPERTY,
          System.getProperty(MAIL_SMTP_STARTTLS_ENABLE_PROPERTY));
    }
    return properties;
  }

  protected Session createSessionForEmail(Properties properties) {
    return Session.getInstance(properties, new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(System.getProperty(MAIL_USERNAME_PROPERTY),
            System.getProperty(MAIL_PASSWORD_PROPERTY));
      }
    });
  }

}
