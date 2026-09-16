package pl.hellopoland.service;

import jakarta.ejb.ApplicationException;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import pl.hellopoland.exception.email.PasswordChangedEmailException;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

/** Exercises MIME construction and the SMTP envelope without making any network requests. */
public class PasswordChangedEmailTest {
  private final EmailService service = new EmailService() {
    @Override
    protected Session createSessionForEmail(Properties properties) {
      Session session = Session.getInstance(properties);
      try {
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp",
            RecordingTransport.class.getName(), "test", "1"));
      } catch (NoSuchProviderException e) {
        throw new AssertionError(e);
      }
      return session;
    }
  };
  private final Map<String, String> saved = new HashMap<>();

  @Before
  public void setUp() {
    property("mail.smtp.enabled", "true");
    property("mail.username", "sender@example.com");
    property("mail.smtp.host", "unused.example.com");
    property("mail.smtp.port", "25");
    property("mail.smtp.auth", "false");
    property("mail.partner.credentials.recipient", "helpdesk@example.com");
    property("mail.redirect.all.to", null);
    RecordingTransport.message = null;
    RecordingTransport.envelope = null;
    RecordingTransport.failure = false;
    RecordingTransport.closed = false;
  }

  @After
  public void restoreProperties() {
    saved.forEach((key, value) -> {
      if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    });
  }

  @Test
  public void sendsNewPasswordToLoginAndHelpdeskAsCc() throws Exception {
    service.sendPasswordChangedEmail("new-login@example.com", "test-password");
    assertEquals("new-login@example.com", address(Message.RecipientType.TO));
    assertEquals("helpdesk@example.com", address(Message.RecipientType.CC));
    assertEquals(2, RecordingTransport.envelope.length);
    assertNull(RecordingTransport.message.getRecipients(Message.RecipientType.BCC));
    Multipart content = (Multipart) RecordingTransport.message.getContent();
    assertTrue(content.getBodyPart(0).getContent().toString().contains("Login: new-login@example.com"));
    assertTrue(content.getBodyPart(0).getContent().toString().contains("Hasło: test-password"));
    assertTrue(RecordingTransport.closed);
  }

  @Test
  public void testRedirectContainsBothRecipientsAndSendsOnlyOnce() throws Exception {
    System.setProperty("mail.redirect.all.to", "test-inbox@example.com");
    service.sendPasswordChangedEmail("customer@example.com", "test-password");
    assertEquals("test-inbox@example.com", address(Message.RecipientType.TO));
    assertNull(RecordingTransport.message.getRecipients(Message.RecipientType.CC));
    assertEquals(1, RecordingTransport.envelope.length);
  }

  @Test
  public void doesNotDuplicateHelpdeskWhenItIsAlsoTheLogin() throws Exception {
    service.sendPasswordChangedEmail("HELPDESK@example.com", "test-password");
    assertEquals(1, RecordingTransport.envelope.length);
    assertNull(RecordingTransport.message.getRecipients(Message.RecipientType.CC));
  }

  @Test
  public void ordinaryMailStillHasOnlyItsOriginalRecipient() throws Exception {
    service.sendEmail(new Email("customer@example.com", "Test", "Body"));
    assertEquals("customer@example.com", address(Message.RecipientType.TO));
    assertNull(RecordingTransport.message.getRecipients(Message.RecipientType.CC));
    assertEquals(1, RecordingTransport.envelope.length);
  }

  @Test
  public void smtpRejectionIsReportedWithoutRollingBackAlreadyChangedCredentials() {
    RecordingTransport.failure = true;
    try {
      service.sendPasswordChangedEmail("customer@example.com", "test-password");
      fail("Expected a notification failure");
    } catch (PasswordChangedEmailException e) {
      assertTrue(e.getMessage().contains("Dane logowania zostały zmienione"));
      assertFalse(e.getClass().getAnnotation(ApplicationException.class).rollback());
      assertFalse(e.getMessage().contains("test-password"));
    }
    assertTrue(RecordingTransport.closed);
  }

  @Test(expected = PasswordChangedEmailException.class)
  public void missingHelpdeskConfigurationIsReported() {
    System.clearProperty("mail.partner.credentials.recipient");
    service.sendPasswordChangedEmail("customer@example.com", "test-password");
  }

  private String address(Message.RecipientType type) throws Exception {
    return ((InternetAddress) RecordingTransport.message.getRecipients(type)[0]).getAddress();
  }

  private void property(String key, String value) {
    saved.put(key, System.getProperty(key));
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }

  public static class RecordingTransport extends SMTPTransport {
    static Message message;
    static Address[] envelope;
    static boolean failure;
    static boolean closed;

    public RecordingTransport(Session session, URLName url) { super(session, url); }
    @Override
    public void connect() { }
    @Override
    public synchronized void sendMessage(Message value, Address[] addresses) throws MessagingException {
      if (failure) throw new SendFailedException("Simulated SMTP rejection");
      message = value;
      envelope = addresses;
    }
    @Override
    public synchronized void close() { closed = true; }
  }
}
