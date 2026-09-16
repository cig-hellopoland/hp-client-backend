package pl.hellopoland.service;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.exception.ConstraintViolationException;
import pl.hellopoland.bo.*;
import pl.hellopoland.bo.UserRole.Role;
import pl.hellopoland.dto.PartnerDTO;
import pl.hellopoland.dto.RoleDTO;
import pl.hellopoland.dto.UserAuthDTO;
import pl.hellopoland.dto.UserDTO;
import pl.hellopoland.enums.BusinessType;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.exception.ExceptionMessagesService;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.exception.conflict.ExternalSystemException;
import pl.hellopoland.exception.email.EmailSendingRollbackException;
import pl.hellopoland.soap.p24.object.MerchantRegisterRequest;
import pl.hellopoland.util.HelloTicket;
import pl.hellopoland.util.soap.p24.MerchantRegisterValidator;

@LocalBean
@Stateless
public class HellopolandService extends ServiceSuperclass {
  private static final String PARTNER_ALREADY_EXISTS_CODE = "9022";
  private static final String PARTNER_EMAIL_REQUIRED_KEY = "partner.create.email.required";
  private static final String PARTNER_NAME_REQUIRED_KEY = "partner.create.name.required";
  private static final String PARTNER_COMMISSION_OUT_OF_RANGE_KEY =
      "partner.create.commission.outOfRange";
  private static final String PARTNER_CREATION_FAILED_KEY = "partner.create.failed";
  private static final String PARTNER_USER_EMAIL_REQUIRED_KEY =
      "partner.create.user.email.required";
  private static final String PARTNER_USER_ROLES_INVALID_KEY =
      "partner.create.user.roles.invalid";
  private static final String HPT_PARTNER_CREATION_FAILED_KEY =
      "partner.create.helloTicket.failed";
  private static final String HPT_AUTHORIZATION_FAILED_KEY =
      "partner.create.helloTicket.authorizationFailed";
  private static final String PARTNER_CREDENTIALS_SEND_FAILED_KEY =
      "partner.create.credentials.sendFailed";
  private static final String PARTNER_CREDENTIALS_RECIPIENT_MISSING_KEY =
      "partner.create.credentials.recipientMissing";
  private static final String PARTNER_CREDENTIALS_RECIPIENT_PROPERTY =
      "mail.partner.credentials.recipient";

  @Inject
  private UserService userService;
  @Inject
  private EmailService emailService;
  @Inject
  private TranslationService translationService;
  @Inject
  private ExceptionMessagesService exceptionMessagesService;

  final Set<UserRole.Role> excludedRoles = Set.of(UserRole.Role.ROOT, UserRole.Role.ADMIN,
      UserRole.Role.PARTNER, UserRole.Role.PARTNER_ADMIN, UserRole.Role.PARTNER_SALESMAN,
      UserRole.Role.SALESMAN);

  public void resetPartnerCredentials(Long id, String email) {
    Partner partner = em.find(Partner.class, id);
    String previousEmail = partner.getEmail();
    email = StringUtils.trim(email).toLowerCase(Locale.ROOT);
    partner.setEmail(email);
    HelloTicket ht = credentialsHelloTicket();

    String newPassword = RandomStringUtils.randomAlphanumeric(10);
    UserAuthDTO form = new UserAuthDTO();
    form.login = email;
    form.password = newPassword;
    form.passwordConfirmation = newPassword;
    ht.changePartnerCredentials(form, partner.getHptToken());

    User user = userService.findPartnerUserByEmailAndRole(
        partner, previousEmail, Role.PARTNER);
    user.changePassword(newPassword);
    user.setEmail(email);

    emailService.sendPasswordChangedEmail(email, newPassword);
  }

  protected HelloTicket credentialsHelloTicket() {
    return new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
  }

  public void synchronizePartnerWithHpt(Partner partner, String previousEmail) {
    String email = StringUtils.trimToNull(partner.getEmail());
    if (email == null) {
      throw new ConflictingException("The email cannot be blank.");
    }
    email = email.toLowerCase(Locale.ROOT);
    partner.setEmail(email);
    userService.findPartnerUserByEmailAndRole(
        partner, previousEmail, Role.PARTNER).setEmail(email);

    if (partner.getHptId() == null) {
      return;
    }

    PartnerDTO partnerDTO = new PartnerDTO();
    partnerDTO.id = partner.getHptId();
    partnerDTO.name = partner.getName();
    partnerDTO.email = email;

    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    ht.updatePartner(partner.getHptId(), partnerDTO, getHelpdeskHptToken());
  }

  public Partner addPartner(PartnerDTO partner) {
    if (StringUtils.isBlank(partner.email)) {
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(PARTNER_EMAIL_REQUIRED_KEY));
    }
    if (StringUtils.isBlank(partner.name)) {
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(PARTNER_NAME_REQUIRED_KEY));
    }
    if (partner.commission != null && (partner.commission.compareTo(BigDecimal.ZERO) < 0
        || partner.commission.compareTo(new BigDecimal("100")) > 0)) {
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(PARTNER_COMMISSION_OUT_OF_RANGE_KEY));
    }

    var merchant = new MerchantRegisterRequest(partner);
    MerchantRegisterValidator.validate(merchant, exceptionMessagesService);
    ensurePartnerDoesNotExist(partner);
    Partner partnerBO = getPartnerFromMerchantRegisterRequest(merchant);

    // 2. creating a partner and the user in hpl:
    partnerBO.setCreated(LocalDateTime.now());
    partnerBO.setCommission(partner.commission);
    partnerBO.setHptToken("temporaryToken");
    if (BooleanUtils.isTrue(partner.affiliation)) {
      partnerBO.setAffiliateCode(RandomStringUtils.randomAlphanumeric(8));
    }
    em.persist(partnerBO.getContactPerson());
    em.persist(partnerBO.getTechnicalContact());
    em.persist(partnerBO);
    String password = RandomStringUtils.randomAlphanumeric(10);
    try {
      Optional<User> user = userService.findUndeletedByEmailWithNullPartner(partner.email);
      user.ifPresentOrElse(us -> {
        us.changePassword(password);
        userService.attachToPartner(us, partnerBO);
      }, () -> userService.create(partner.email, password, null, null, partnerBO,
          UserRole.Role.PARTNER, UserRole.Role.USHER));
      em.flush();
    } catch (Exception e) {
      if (isPartnerAlreadyExistsException(e)) {
        throw new ConflictingException(
            exceptionMessagesService.getMessageByCode(PARTNER_ALREADY_EXISTS_CODE),
            PARTNER_ALREADY_EXISTS_CODE, e);
      }
      var exc = e.getCause();
      if (exc instanceof jakarta.validation.ConstraintViolationException) {
        var errMsg = new StringBuilder();
        ((jakarta.validation.ConstraintViolationException) exc).getConstraintViolations().forEach(
            cv -> errMsg.append(cv.getPropertyPath()).append(" ").append(cv.getMessage()).append(", "));
        String message = exceptionMessagesService.getMessageByKey(PARTNER_CREATION_FAILED_KEY);
        logger.log(Level.ERROR, message + " " + errMsg);
        throw new ConflictingException(message);
      }

      var exc2 = e.getCause().getCause();
      if (exc2 instanceof ConstraintViolationException) {
        String errMsg = ((ConstraintViolationException) exc2).getSQLException().getMessage();
        String message = exceptionMessagesService.getMessageByKey(PARTNER_CREATION_FAILED_KEY);
        logger.log(Level.ERROR, message + " " + errMsg);
        throw new ConflictingException(message);
      }
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(PARTNER_CREATION_FAILED_KEY));
    }

    partner.password = password;
    var emailPassword = new HashMap<String, String>();
    emailPassword.put(partner.email, password);

    // 3. creating users (excluded ushers) of the partner in hpl:
    var usersDTOs = partner.users;
    if (usersDTOs != null && !usersDTOs.isEmpty()) {
      for (UserDTO userDTO : usersDTOs) {
        if (StringUtils.isBlank(userDTO.email)) {
          throw new ConflictingException(
              exceptionMessagesService.getMessageByKey(PARTNER_USER_EMAIL_REQUIRED_KEY));
        }
        if (userDTO.roles == null || !areRolesSupported(userDTO.roles)) {
          throw new ConflictingException(
              exceptionMessagesService.getMessageByKey(PARTNER_USER_ROLES_INVALID_KEY));
        }
        Role[] userRoles = getFilteredRolesFromDTO(userDTO.roles);
        if (userRoles.length > 0) {
          String pass = RandomStringUtils.randomAlphanumeric(10);
          User userBO = userService.create(userDTO.email, pass, userDTO.name, null, partnerBO,
              userRoles);
          emailPassword.put(userDTO.email, pass);
          partnerBO.addUser(userBO);
        }
      }
    }

    // 4. creating a partner in hpt:
    Portal hpt = getPortal("Hello Ticket Cloud");
    var ht = new HelloTicket(hpt.getUrl());
    var hptToken = getHelpdeskHptToken();
    try {
      var hptPartner = ht.addPartner(partner, hptToken);
      if (hptPartner == null) {
        throw new ExternalSystemException(
            exceptionMessagesService.getMessageByKey(HPT_PARTNER_CREATION_FAILED_KEY));
      }
      partnerBO.setHptToken(hptPartner.token);
      partnerBO.setHptId(hptPartner.id);
    } catch (ConflictingException e) {
      throw e;
    } catch (ExternalSystemException e) {
      if (e.getStatusCode() != null && (e.getStatusCode() == 401 || e.getStatusCode() == 403)) {
        throw new ConflictingException(
            exceptionMessagesService.getMessageByKey(HPT_AUTHORIZATION_FAILED_KEY), e);
      }
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(HPT_PARTNER_CREATION_FAILED_KEY), e);
    } catch (Exception e) {
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(HPT_PARTNER_CREATION_FAILED_KEY), e);
    }

    // 5. sending generated credentials to the internal technical mailbox:
    String credentialsRecipient = getPartnerCredentialsRecipient();
    emailPassword.forEach((key, value) -> {
      try {
        emailService.sendEmail(new Email(credentialsRecipient,
            "Nowe konto partnera w Hello Poland.",
            credentialsEmailBody(partner.name, key, value)));
      } catch (Exception e) {
        logger.log(System.Logger.Level.ERROR, e.getLocalizedMessage());
        ht.removePartner(partner.email, hptToken);
        throw new EmailSendingRollbackException(
            exceptionMessagesService.getFormattedMessageByKey(
                PARTNER_CREDENTIALS_SEND_FAILED_KEY, credentialsRecipient));
      }
    });

    // 6. create first translation
    translationService.createEntityLanguageVersion(partnerBO, partner, LanguageVersion.PL_PL);
    translationService.createEntityLanguageVersion(partnerBO.getAddress(), partner.location,
        LanguageVersion.PL_PL);

    return partnerBO;
  }

  private String getPartnerCredentialsRecipient() {
    String recipient = StringUtils.trimToNull(
        properties.getProperty(PARTNER_CREDENTIALS_RECIPIENT_PROPERTY));
    if (recipient == null) {
      throw new ConflictingException(
          exceptionMessagesService.getMessageByKey(PARTNER_CREDENTIALS_RECIPIENT_MISSING_KEY));
    }
    return recipient;
  }

  private String credentialsEmailBody(String partnerName, String login, String password) {
    return "Partner: " + partnerName + "\nLogin: " + login + "\nHas\u0142o: " + password;
  }

  private Partner getPartnerFromMerchantRegisterRequest(MerchantRegisterRequest merchant) {
    var partnerBO = new Partner();
    partnerBO.setBankAccount(merchant.bank_account);
    partnerBO.setName(merchant.name);
    partnerBO.setEmail(merchant.email);
    partnerBO.setInvoiceEmail(merchant.invoice_email);
    partnerBO.setKrs(merchant.krs);
    partnerBO.setTaxNumber(merchant.nip);
    partnerBO.setSocialNumber(
        StringUtils.isNotBlank(merchant.pesel) ? Long.valueOf(merchant.pesel) : null);
    partnerBO.setPhone(merchant.phone_number);
    partnerBO.setRegon(merchant.regon);
    partnerBO.setServicesDescription(merchant.services_description);
    partnerBO.setShopUrl(merchant.shop_url);
    partnerBO.setBusinessType(BusinessType.getBusinessType(merchant.business_type));
    var address = new Address();
    address.setCountry(merchant.address.country);
    address.setCity(merchant.address.city);
    address.setPostCode(merchant.address.post_code);
    address.setStreet(merchant.address.street);
    partnerBO.setAddress(address);
    var correspondenceAddress = new Address();
    correspondenceAddress.setCountry(merchant.correspondence_address.country);
    correspondenceAddress.setCity(merchant.correspondence_address.city);
    correspondenceAddress.setPostCode(merchant.correspondence_address.post_code);
    correspondenceAddress.setStreet(merchant.correspondence_address.street);
    partnerBO.setCorrespondenceAddress(correspondenceAddress);
    var contactPerson = new ContactPerson();
    contactPerson.setEmail(merchant.contact_person.email);
    contactPerson.setName(merchant.contact_person.name);
    contactPerson.setPhone(merchant.contact_person.phone_number);
    partnerBO.setContactPerson(contactPerson);
    var technicalContact = new ContactPerson();
    technicalContact.setEmail(merchant.technical_contact.email);
    technicalContact.setName(merchant.technical_contact.name);
    technicalContact.setPhone(merchant.technical_contact.phone_number);
    partnerBO.setTechnicalContact(technicalContact);
    if (merchant.representatives != null) {
      List<PartnerRepresentative> representatives =
          Arrays.asList(merchant.representatives).stream().map(r -> {
            var rep = new PartnerRepresentative();
            rep.setName(r.name);
            rep.setSocialNumber(StringUtils.isNotBlank(r.pesel) ? Long.valueOf(r.pesel) : null);
            return rep;
          }).collect(Collectors.toList());
      partnerBO.setRepresentatives(representatives);
    }
    return partnerBO;
  }

  private void ensurePartnerDoesNotExist(PartnerDTO partner) {
    if (partner == null) {
      return;
    }

    boolean partnerNameExists = em.createQuery(
            "select count(p) from Partner p where lower(p.name) = :name", Long.class)
        .setParameter("name", partner.name.toLowerCase())
        .getSingleResult() > 0;

    boolean partnerEmailExists = em.createQuery(
            "select count(p) from Partner p where lower(p.email) = :email", Long.class)
        .setParameter("email", partner.email.toLowerCase())
        .getSingleResult() > 0;

    boolean userEmailAlreadyAssigned = userService.findUndeletedByEmail(partner.email)
        .map(User::getPartner)
        .filter(Objects::nonNull)
        .isPresent();

    if (partnerNameExists || partnerEmailExists || userEmailAlreadyAssigned) {
      throw new ConflictingException(
          exceptionMessagesService.getMessageByCode(PARTNER_ALREADY_EXISTS_CODE),
          PARTNER_ALREADY_EXISTS_CODE);
    }
  }

  private boolean isPartnerAlreadyExistsException(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof ConstraintViolationException cve) {
        String sqlMessage = cve.getSQLException() != null ? cve.getSQLException().getMessage() : "";
        if (sqlMessage != null && sqlMessage.toLowerCase().contains("duplicate key value")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  private boolean areRolesSupported(Set<RoleDTO> roles) {
    var supported = new HashSet<Role>(Arrays.asList(UserRole.Role.values()));
    supported.removeAll(excludedRoles);
    try {
      return supported.containsAll(
          roles.stream().map(r -> UserRole.Role.valueOf(r.name())).collect(Collectors.toSet()));
    } catch (Exception e) {
      return false;
    }
  }

  private Role[] getFilteredRolesFromDTO(Set<RoleDTO> roles) {
    Stream<UserRole.Role> stream = roles.stream().map(r -> UserRole.Role.valueOf(r.name()))
        .filter(r -> !excludedRoles.contains(r) && !r.equals(UserRole.Role.USHER));
    return stream.toArray(UserRole.Role[]::new);
  }
}
