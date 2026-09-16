package pl.hellopoland.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import pl.hellopoland.bo.Partner;
import pl.hellopoland.bo.User;
import pl.hellopoland.bo.UserRole.Role;
import pl.hellopoland.bo.UserToken;
import pl.hellopoland.dto.UserAuthDTO;
import pl.hellopoland.dto.UserDTO;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.security.password.PasswordEncoder;
import pl.hellopoland.util.HelloTicket;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Regression coverage for the real credential-changing methods, with DB/HTTP/mail fakes. */
public class PasswordChangeNotificationsTest {
  private final Partner partner = new Partner();
  private final User owner = new User(Role.PARTNER);
  private User target;
  private UserToken resetToken;
  private final List<Email> sent = new ArrayList<>();
  private final PasswordEncoder encoder = new PasswordEncoder();
  private String previousRecipient;
  private int synchronizations;
  private String synchronizedLogin;
  private String synchronizedPassword;
  private boolean synchronizationFails;
  private final EmailService mail = new EmailService() {
    @Override
    public void sendEmail(Email email, String sender) { sent.add(email); }
  };
  private final HelloTicket ticket = new HelloTicket("http://unused.invalid") {
    @Override
    public void changePartnerCredentials(UserAuthDTO dto, String token) {
      if (synchronizationFails) throw new ConflictingException("Simulated synchronization failure");
      synchronizations++;
      synchronizedLogin = dto.login;
      synchronizedPassword = dto.password;
    }
    @Override
    public UserDTO getUsherForPartner(long id, String token) {
      UserDTO dto = new UserDTO();
      dto.email = "usher@example.com";
      return dto;
    }
    @Override
    public void changeUsherPassword(long id, UserAuthDTO dto, String token) {
      synchronizedPassword = dto.password;
    }
  };
  private final UserService users = new UserService() {
    @Override
    public User getLoggedUser() { return owner; }
    @Override
    protected HelloTicket credentialsHelloTicket() { return ticket; }
    @Override
    public UserToken findValidPasswordResetToken(String token) { return resetToken; }
    @Override
    public User findPartnerUserByEmailAndRole(Partner value, String email, Role role) {
      assertEquals("old-login@example.com", email);
      return owner;
    }
  };

  @Before
  public void setUp() throws Exception {
    previousRecipient = System.getProperty("mail.partner.credentials.recipient");
    System.setProperty("mail.partner.credentials.recipient", "helpdesk@example.com");
    partner.setId(10L);
    partner.setEmail("old-login@example.com");
    partner.setHptToken("test-token");
    owner.setId(20L);
    owner.setEmail("old-login@example.com");
    owner.setPartner(partner);
    owner.changePassword("old-password");
    target = owner;
    inject(users, "emailService", mail);
    inject(users, "passwordEncoder", encoder);
    inject(users, "partnerService", new PartnerService() {
      @Override
      public Partner get(Long id) { return partner; }
    });
    inject(users, "partnerUserAccessService", new PartnerUserAccessService() {
      @Override
      public void requireCanManagePartnerUsers() { }
    });
    TypedQuery<?> query = (TypedQuery<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{TypedQuery.class}, (proxy, method, args) -> {
          if (method.getName().equals("setParameter")) return proxy;
          if (method.getName().equals("getResultStream")) return Stream.of(target);
          throw new UnsupportedOperationException(method.getName());
        });
    users.em = (EntityManager) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
          if (method.getName().equals("createQuery")) return query;
          if (method.getName().equals("find")) return partner;
          throw new UnsupportedOperationException(method.getName());
        });
  }

  @After
  public void restoreProperties() {
    if (previousRecipient == null) System.clearProperty("mail.partner.credentials.recipient");
    else System.setProperty("mail.partner.credentials.recipient", previousRecipient);
  }

  @Test
  public void helpdeskLoginChangeSendsGeneratedPasswordToNewLogin() throws Exception {
    HellopolandService service = new HellopolandService() {
      @Override
      protected HelloTicket credentialsHelloTicket() { return ticket; }
    };
    service.em = users.em;
    inject(service, "emailService", mail);
    inject(service, "userService", users);
    service.resetPartnerCredentials(10L, " NEW-LOGIN@example.com ");
    assertEquals("new-login@example.com", owner.getEmail());
    assertEquals(owner.getEmail(), synchronizedLogin);
    assertEquals(owner.getEmail(), partner.getEmail());
    assertTrue(encoder.matches(synchronizedPassword, owner.getPassword()));
    assertNotification("new-login@example.com", synchronizedPassword);
  }

  @Test
  public void partnerChangingOwnPasswordGetsMailAfterSynchronization() {
    users.changePasswordForLoggedPartner(passwordForm());
    assertEquals(1, synchronizations);
    assertNotification(owner.getEmail(), "new-password");
    assertTrue(encoder.matches("new-password", owner.getPassword()));
  }

  @Test
  public void portalUserChangingOwnPasswordGetsMail() {
    users.changePasswordForLoggedUser(passwordForm());
    assertNotification(owner.getEmail(), "new-password");
    assertEquals(0, synchronizations);
  }

  @Test
  public void wrongOldPasswordDoesNotSendMailOrChangePassword() {
    UserAuthDTO form = passwordForm();
    form.oldPassword = "incorrect";
    try {
      users.changePasswordForLoggedUser(form);
      fail("Expected rejected old password");
    } catch (ConflictingException expected) {
      assertTrue(sent.isEmpty());
      assertTrue(encoder.matches("old-password", owner.getPassword()));
    }
  }

  @Test
  public void synchronizationFailureDoesNotSendSuccessfulChangeMail() {
    synchronizationFails = true;
    try {
      users.changePasswordForLoggedPartner(passwordForm());
      fail("Expected synchronization failure");
    } catch (ConflictingException expected) {
      assertTrue(sent.isEmpty());
    }
  }

  @Test
  public void helpdeskChangingMainPartnerPasswordSendsOneNotification() {
    users.changePasswordForPartnerUserFromHelpdesk(10L, 20L, "new-password");
    assertEquals(1, synchronizations);
    assertNotification(owner.getEmail(), "new-password");
  }

  @Test
  public void helpdeskChangingStaffPasswordNotifiesStaffRatherThanPartnerOwner() {
    target = staff();
    users.changePasswordForPartnerUserFromHelpdesk(10L, 30L, "new-password");
    assertNotification(target.getEmail(), "new-password");
    assertEquals(0, synchronizations);
    assertTrue(encoder.matches("old-password", owner.getPassword()));
  }

  @Test
  public void partnerChangingStaffPasswordNotifiesStaff() {
    target = staff();
    users.changePasswordForPartnerPanelUser(30L, passwordForm());
    assertNotification(target.getEmail(), "new-password");
  }

  @Test
  public void usherPasswordChangeNotifiesUsher() {
    users.changePasswordForUsher(40L, passwordForm());
    assertNotification("usher@example.com", "new-password");
  }

  @Test
  public void confirmedResetLinkSendsPasswordToTokenOwner() {
    resetToken = new UserToken();
    resetToken.setUser(staff());
    assertTrue(users.confirmPasswordReset("valid-token", "new-password"));
    assertTrue(resetToken.isUsed());
    assertNotification("staff@example.com", "new-password");
  }

  @Test
  public void invalidResetLinkDoesNotSendMail() {
    assertFalse(users.confirmPasswordReset("invalid-token", "new-password"));
    assertTrue(sent.isEmpty());
  }

  private void assertNotification(String login, String password) {
    assertEquals(1, sent.size());
    Email email = sent.get(0);
    assertEquals(login, email.recipientEmail);
    assertEquals("helpdesk@example.com", email.ccEmail);
    assertTrue(email.msg.contains("Login: " + login));
    assertTrue(email.msg.contains("Hasło: " + password));
  }

  private User staff() {
    User user = new User(Role.PARTNER, Role.PARTNER_SALESMAN);
    user.setId(30L);
    user.setPartner(partner);
    user.setEmail("staff@example.com");
    return user;
  }

  private UserAuthDTO passwordForm() {
    UserAuthDTO form = new UserAuthDTO();
    form.oldPassword = "old-password";
    form.password = "new-password";
    return form;
  }

  private static void inject(Object object, String name, Object value) throws Exception {
    Class<?> type = object.getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
        return;
      } catch (NoSuchFieldException e) { type = type.getSuperclass(); }
    }
    throw new NoSuchFieldException(name);
  }
}
