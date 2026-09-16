package pl.hellopoland.service;

import org.apache.commons.lang3.RandomStringUtils;
import pl.hellopoland.bo.*;
import pl.hellopoland.bo.UserRole.Role;
import pl.hellopoland.dto.UserAuthDTO;
import pl.hellopoland.dto.UserDTO;
import pl.hellopoland.dto.RoleDTO;
import pl.hellopoland.exception.UnauthorizedException;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.exception.notfound.AccessDeniedException;
import pl.hellopoland.security.password.PasswordEncoder;
import pl.hellopoland.util.HelloTicket;
import pl.hellopoland.util.NameAndAddressSplitter;
import pl.hellopoland.util.DtoMapper;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import java.io.ByteArrayInputStream;
import java.lang.System.Logger.Level;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

@LocalBean
@Stateless
public class UserService extends ServiceSuperclass {
  private static final String ACCOUNT_EMAIL_SENDER = "Hello Poland";
  private static final String USHER_EMAIL_MATCHES_PARTNER_EMAIL_MESSAGE =
      "Podaj inny adres e-mail niż ten, którym logujesz się do profilu Partnera.";
  private static final String USHER_EMAIL_ALREADY_USED_AS_LOGIN_MESSAGE =
      "Podany adres e-mail jest już używany.";

  private static final Set<Role> HELPDESK_USER_ROLES = Set.of(
      Role.ROOT,
      Role.ADMIN,
      Role.SALESMAN,
      Role.HELPDESK_PARTNER_MANAGER,
      Role.HELPDESK_CONTENT_MANAGER,
      Role.HELPDESK_SUPPORT,
      Role.HELPDESK_TECHNICAL
  );

  @Inject
  private PasswordEncoder passwordEncoder;
  @Inject
  private PartnerService partnerService;
  @Inject
  private EmailService emailService;
  @Inject
  private OrderService orderService;
  @Inject
  private PartnerUserAccessService partnerUserAccessService;
  @Inject
  private ImageService imageService;

  public User me() {
    return Optional.ofNullable(getLoggedUser()).orElseThrow(UnauthorizedException::new);
  }

  public User getOrCreateSocialMedia(User user) {
    String email = user.getEmail();
    String picture = user.getPicture();
    UserDetails details = user.getDetails();
    try {
      User bo = findOneUndeletedByEmail(email);
      bo.setPicture(picture);
      if (!bo.hasRole(Role.USER)) {
        createUserRole(bo, Role.USER);
      }
      return bo;
    } catch (NoResultException e) {
      return create(email, RandomStringUtils.randomAlphanumeric(10), picture, details);
    }
  }

  private User create(String email, String password, String picture,
      UserDetails details) {
    User bo = new User(Role.USER);
    bo.setEmail(email.toLowerCase());
    bo.changePassword(password);
    bo.setPicture(picture);
    bo.setDetails(details);

    em.persist(bo);
    return bo;
  }

  public User create(String email, String decodedPassword, boolean tosAgreement) {
    User bo = new User(Role.USER);
    if (email == null) {
      throw new ConflictingException("Cannot create user with empty email");
    }
    bo.setEmail(email.toLowerCase());
    bo.changePassword(decodedPassword);
    bo.setEmailVerified(false);

    UserDetails details = new UserDetails();
    details.setTosAgreement(tosAgreement);
    bo.setDetails(details);

    em.persist(bo);
    createEmailVerificationToken(bo);
    return bo;
  }

  /**
   * Registers a market user or restarts registration for an account that has not been activated yet.
   * Existing active, deleted, blocked or non-market accounts cannot be taken over through registration.
   */
  public User registerMarketUser(String email, String decodedPassword, boolean tosAgreement) {
    Optional<User> existingUser = findByEmail(email);

    if (existingUser.isEmpty()) {
      return create(email, decodedPassword, tosAgreement);
    }

    User user = existingUser.get();
    if (!isPendingMarketRegistration(user)) {
      throw new ConflictingException("Konto dla podanego adresu e-mail już istnieje.");
    }

    user.changePassword(decodedPassword);
    UserDetails details = Optional.ofNullable(user.getDetails()).orElseGet(UserDetails::new);
    details.setTosAgreement(tosAgreement);
    user.setDetails(details);

    em.createQuery("""
        update UserToken t
        set t.used = true
        where t.user = :user
          and t.type = :type
          and t.used = false
        """)
        .setParameter("user", user)
        .setParameter("type", UserToken.Type.EMAIL_VERIFICATION)
        .executeUpdate();

    createEmailVerificationToken(user);
    return user;
  }

  private boolean isPendingMarketRegistration(User user) {
    return !user.isDeleted()
        && !user.isBlocked()
        && !user.isEmailVerified()
        && user.getPartner() == null
        && user.hasRole(Role.USER)
        && user.getRoles().stream().allMatch(role -> role.getRole() == Role.USER);
  }

  public User create(String email, String decodedPassword, String name, String picture,
      Partner partner, UserRole.Role... roles) {
    User bo = new User(roles);
    if (email == null) {
      throw new ConflictingException("Cannot create user with empty email");
    }
    bo.setEmail(email.toLowerCase());
    bo.changePassword(decodedPassword);
    bo.setPicture(picture);
    bo.setPartner(partner);

    bo.setDetails(new UserDetails(NameAndAddressSplitter.getFirstName(name),
        NameAndAddressSplitter.getLastName(name)));
    em.persist(bo);
    return bo;
  }

  public User updateUserDetailsForLoggedUser(UserDetails newDetails) {
    User user = getLoggedUser();
    UserDetails oldDetails = user.getDetails();
    if (oldDetails == null) {
      oldDetails = new UserDetails();
    }
    oldDetails.update(newDetails);
    em.merge(user);
    return user;
  }

  public User updateAvatarForLoggedUser(byte[] bytes, String extension) {
    User user = me();
    var image = imageService.validateAndStoreImageCollector(
        "avatar-" + user.getId(),
        new ByteArrayInputStream(bytes),
        extension,
        null,
        400);
    var dto = DtoMapper.getDTO(image);
    user.setPicture(dto.qvg != null ? dto.qvg : dto.original);
    return user;
  }

  // TODO osobna metoda dla zgód, na przyszły refactor
  public User updateAgreementsForLoggedUser(UserDetails userAgreements) {
    User user = getLoggedUser();
    if (user.getDetails() == null) {
      user.setDetails(new UserDetails());
    }
    UserDetails oldDetails = user.getDetails();
    oldDetails.updateAgreements(userAgreements);
    if (!oldDetails.hasAllRequiredAgreements()) {
      deleteUser(user);
    }
    return user;
  }

  private void deleteUser(User user) {
    String hplMail = properties.getProperty("mail.hellopoland.biuro");
    String hplMailContent = "Dane usuniętego użytkownika: " + user.toString();
    String userEmail = user.getEmail();

    user.setDeleted(true);
    user.setPicture(null);
    orderService.anonymizeOrdersForUser(user);
    anonymizeUser(user);
    try {
      emailService.sendEmail(new Email(hplMail, "Usunięto konto użytkownika", hplMailContent));
      emailService.sendEmail(new Email(userEmail, "Usunięto konto w Hello Poland", "Usunięto Twoje konto w systemie Hello Poland. Usunięto Twoje dane osobowe w związku z cofnięciem zgody na warunki zawarte w naszym regulaminie oraz polityce prywatności."));
    } catch (Exception e) {
      logger.log(System.Logger.Level.ERROR, e.getLocalizedMessage());
      throw new InternalServerErrorException(
          "Something went wrong while deleting user [id:" + user.getId() + "]");
    }
  }

  private void anonymizeUser(User user) {
    user.setEmail("anon+" + user.getId().toString() + "@hello-poland.pl");
    UserDetails details = user.getDetails();
    if (details != null) {
      details.setFirstName("anon");
      details.setLastName("anon");
      details.setStreet("anon");
      details.setPhone("anon");
    }
  }

  public User get(Long id) {
    return em.createQuery("from User where id=:id and deleted=false", User.class)
        .setParameter("id", id).getResultStream().findFirst()
        .orElseThrow(() -> new NotFoundException());
  }

  public User findOneUndeletedByEmail(String email) {
    return em.createQuery("from User where lower(email) = :email and deleted=false", User.class)
        .setParameter("email", email.toLowerCase()).getSingleResult();
  }

  public Optional<User> findUndeletedByEmail(String email) {
    return em.createQuery("from User u left join fetch u.partner fp where lower(u.email) = :email and u.deleted=false", User.class)
        .setParameter("email", email.toLowerCase()).getResultStream().findFirst();
  }

  public Optional<User> findByEmail(String email) {
    return em.createQuery("from User u left join fetch u.partner fp where lower(u.email) = :email",
            User.class)
        .setParameter("email", email.toLowerCase())
        .getResultStream()
        .findFirst();
  }

  public Optional<User> findUndeletedByEmailWithNullPartner(String email) {
    return em
        .createQuery("from User where lower(email) = :email and partner = null and deleted=false",
            User.class)
        .setParameter("email", email.toLowerCase()).getResultStream().findFirst();
  }

  public void changePasswordForLoggedPartner(UserAuthDTO userAuthDTO) {
    if (passwordEncoder.matches(userAuthDTO.oldPassword, getLoggedUser().getPassword())) {
      getLoggedUser().changePassword(userAuthDTO.password);
      HelloTicket ht = credentialsHelloTicket();
      ht.changePartnerCredentials(userAuthDTO, getLoggedPartner().getHptToken());
      emailService.sendPasswordChangedEmail(getLoggedUser().getEmail(), userAuthDTO.password);
    } else {
      throw new ConflictingException("Incorrect old password.");
    }
  }

  public void updatePasswordForUser(User user, String password) {
    user.changePassword(password);
    var dto = new UserAuthDTO();
    dto.password = password;
    if (user.getPartner() != null) {
      credentialsHelloTicket().changePartnerCredentials(dto, user.getPartner().getHptToken());
    }
    emailService.sendPasswordChangedEmail(user.getEmail(), password);
  }

  public void changePasswordForUsher(long usherId, UserAuthDTO userAuthDTO) {
    HelloTicket ht = credentialsHelloTicket();
    UserDTO usher = ht.getUsherForPartner(usherId, getLoggedPartner().getHptToken());
    ht.changeUsherPassword(usherId, userAuthDTO, getLoggedPartner().getHptToken());
    emailService.sendPasswordChangedEmail(usher.email, userAuthDTO.password);
  }

  protected HelloTicket credentialsHelloTicket() {
    return new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
  }

  public void changePasswordForLoggedUser(UserAuthDTO userAuthDTO) {
    if (passwordEncoder.matches(userAuthDTO.oldPassword, getLoggedUser().getPassword())) {
      getLoggedUser().changePassword(userAuthDTO.password);
      emailService.sendPasswordChangedEmail(getLoggedUser().getEmail(), userAuthDTO.password);
    } else {
      throw new ConflictingException("Incorrect old password.");
    }
  }

  public List<UserDTO> getUshers() {
    Partner partner = partnerService.findByUserEmail(ctx.getCallerPrincipal().getName());
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket helloTicket = new HelloTicket(hpt.getUrl());
    return helloTicket.getUshersForPartner(partner.getHptToken());
  }

  public UserDTO getUsher(long usherId) {
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    return ht.getUsherForPartner(usherId, getLoggedPartner().getHptToken());
  }

  public List<UserDTO> getUshersForPartnerFromHelpdesk(Long partnerId) {
    Partner partner = partnerService.get(partnerId);
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    return ht.getUshersForPartner(partner.getHptToken());
  }

  public UserDTO updateUsherForPartnerFromHelpdesk(Long partnerId, Long usherId, UserDTO usher) {
    Partner partner = partnerService.get(partnerId);
    usher.id = usherId;
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    return ht.updateUsherForPartner(usher, partner.getHptToken());
  }

  public UserDTO setUsherBlockedForPartnerFromHelpdesk(Long partnerId, Long usherId,
      boolean blocked) {
    Partner partner = partnerService.get(partnerId);
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    UserDTO usher = ht.getUsherForPartner(usherId, partner.getHptToken());
    usher.blocked = blocked;
    return ht.updateUsherForPartner(usher, partner.getHptToken());
  }

  public UserDTO createUsherForPartnerFromHelpdesk(Long partnerId, UserDTO usherDTO) {
    validateUsherEmailForPartnerAndHelpdeskLogins(usherDTO);
    Partner partner = partnerService.get(partnerId);
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    return ht.createUsherForLoggedPartner(usherDTO, partner.getHptToken());
  }

  public void changeUsherPasswordForPartnerFromHelpdesk(Long partnerId, Long usherId,
      UserAuthDTO userAuthDTO) {
    Partner partner = partnerService.get(partnerId);
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    ht.changeUsherPassword(usherId, userAuthDTO, partner.getHptToken());
  }

  public UserDTO updateUsher(UserDTO usher) {
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    return ht.updateUsherForPartner(usher, getLoggedPartner().getHptToken());
  }

  public UserDTO setUsherBlockedForLoggedPartner(long usherId, boolean blocked) {
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    UserDTO usher = ht.getUsherForPartner(usherId, getLoggedPartner().getHptToken());
    usher.blocked = blocked;
    return ht.updateUsherForPartner(usher, getLoggedPartner().getHptToken());
  }

  public UserDTO createUsherForLoggedPartner(UserDTO usherDTO) {
    validateUsherEmailForPartnerAndHelpdeskLogins(usherDTO);
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket ht = new HelloTicket(hpt.getUrl());
    return ht.createUsherForLoggedPartner(usherDTO, getLoggedPartner().getHptToken());
  }

  private void validateUsherEmailForPartnerAndHelpdeskLogins(UserDTO usherDTO) {
    String usherEmail = StringUtils.trim(usherDTO.email);
    if (StringUtils.equalsIgnoreCase(usherEmail, getLoggedUser().getEmail())) {
      throw new ConflictingException(USHER_EMAIL_MATCHES_PARTNER_EMAIL_MESSAGE);
    }
    if (StringUtils.isBlank(usherEmail)) {
      return;
    }
    usherEmail = usherEmail.toLowerCase();

    findUndeletedByEmail(usherEmail)
        .filter(this::isPartnerOrHelpdeskLogin)
        .ifPresent(user -> {
          throw new ConflictingException(USHER_EMAIL_ALREADY_USED_AS_LOGIN_MESSAGE);
        });
    usherDTO.email = usherEmail;
  }

  private boolean isPartnerOrHelpdeskLogin(User user) {
    return hasAnyRole(user, Role.PARTNER, Role.PARTNER_ADMIN, Role.PARTNER_SALESMAN,
        Role.ADMIN, Role.ROOT, Role.SALESMAN, Role.HELPDESK_PARTNER_MANAGER,
        Role.HELPDESK_CONTENT_MANAGER, Role.HELPDESK_SUPPORT, Role.HELPDESK_TECHNICAL);
  }

  private boolean hasAnyRole(User user, Role... roles) {
    Set<Role> expectedRoles = Set.of(roles);
    return Optional.ofNullable(user.getRoles()).orElse(List.of()).stream()
        .map(UserRole::getRole)
        .anyMatch(expectedRoles::contains);
  }

  public void attachToPartner(User user, Partner partner) {
    user.changePassword(user.getPassword());
    user.setPartner(partner);
    createUserRole(user, Role.USHER);
    createUserRole(user, Role.PARTNER);
  }

  public List<UserDTO> getHelpdeskUsers() {
    requireCanManageHelpdeskUsers();
    return em.createQuery(
            "select distinct u from User u left join fetch u.roles r "
                + "left join fetch u.allowedHelpdeskPartners ahp "
                + "left join fetch u.allowedHelpdeskSights ahs "
                + "where u.partner is null and u.deleted = false",
            User.class)
        .getResultStream()
        .filter(this::isHelpdeskUser)
        .sorted(Comparator.comparing(User::getEmail, String.CASE_INSENSITIVE_ORDER))
        .map(DtoMapper::getDTO)
        .collect(Collectors.toList());
  }

  public UserDTO getHelpdeskUser(long userId) {
    requireCanManageHelpdeskUsers();
    return DtoMapper.getDTO(getHelpdeskUserEntity(userId));
  }

  public UserDTO createHelpdeskUser(UserDTO dto) {
    requireCanManageHelpdeskUsers();
    validateHelpdeskUserPayload(dto, true, null);

    User user = new User();
    user.setEmail(StringUtils.trim(dto.email).toLowerCase());
    user.changePassword(dto.password);
    setPartnerPanelUserName(user, dto.name);
    em.persist(user);

    replaceHelpdeskRoles(user, getHelpdeskRoles(dto), null);
    updateHelpdeskScope(user, dto);
    return DtoMapper.getDTO(user);
  }

  public UserDTO updateHelpdeskUser(long userId, UserDTO dto) {
    requireCanManageHelpdeskUsers();
    User user = getHelpdeskUserEntity(userId);
    requireCanModifyHelpdeskUser(user);
    validateHelpdeskUserPayload(dto, false, user);

    if (StringUtils.isNotBlank(dto.email)) {
      user.setEmail(StringUtils.trim(dto.email).toLowerCase());
    }
    if (StringUtils.isNotBlank(dto.name)) {
      setPartnerPanelUserName(user, dto.name);
    }
    replaceHelpdeskRoles(user, getHelpdeskRoles(dto), user);
    updateHelpdeskScope(user, dto);
    return DtoMapper.getDTO(user);
  }

  public UserDTO setHelpdeskUserBlocked(long userId, boolean blocked) {
    requireCanManageHelpdeskUsers();
    User user = getHelpdeskUserEntity(userId);
    requireCanModifyHelpdeskUser(user);
    if (blocked) {
      requireNotCurrentUser(user);
      requireCanRemoveAdminAccess(user);
    }
    user.setBlocked(blocked);
    return DtoMapper.getDTO(user);
  }

  public void changeHelpdeskUserPassword(long userId, String password) {
    requireCanManageHelpdeskUsers();
    User user = getHelpdeskUserEntity(userId);
    requireCanModifyHelpdeskUser(user);
    user.changePassword(password);
    emailService.sendPasswordChangedEmail(user.getEmail(), password);
  }

  public void deleteHelpdeskUser(long userId) {
    requireCanManageHelpdeskUsers();
    User user = getHelpdeskUserEntity(userId);
    requireCanModifyHelpdeskUser(user);
    requireNotCurrentUser(user);
    requireCanRemoveAdminAccess(user);

    user.setDeleted(true);
    user.setBlocked(true);
    user.setEmail("deleted+" + user.getId() + "+" + System.currentTimeMillis()
        + "@hello-poland.pl");
    user.changePassword(RandomStringUtils.randomAlphanumeric(32));
    user.setAllowedHelpdeskPartners(new HashSet<>());
    user.setAllowedHelpdeskSights(new HashSet<>());
    em.merge(user);
  }

  public List<UserDTO> getPartnerPanelUsersForLoggedPartner() {
    partnerUserAccessService.requireCanManagePartnerUsers();
    Partner partner = getLoggedPartner();
    return em.createQuery(
            "select distinct u from User u left join fetch u.roles r left join fetch u.allowedPartnerSights aps "
                + "where u.partner = :partner and u.deleted = false",
            User.class)
        .setParameter("partner", partner)
        .getResultStream()
        .filter(this::isPartnerPanelManagedUser)
        .sorted(Comparator.comparing(User::getEmail, String.CASE_INSENSITIVE_ORDER))
        .map(DtoMapper::getDTO)
        .collect(Collectors.toList());
  }

  public List<UserDTO> getPartnerUsersForHelpdesk(long partnerId) {
    Partner partner = partnerService.get(partnerId);
    return em.createQuery(
            "select distinct u from User u left join fetch u.roles r left join fetch u.allowedPartnerSights aps "
                + "where u.partner = :partner and u.deleted = false",
            User.class)
        .setParameter("partner", partner)
        .getResultStream()
        .filter(this::isHelpdeskPartnerUser)
        .sorted(Comparator.comparing(User::getEmail, String.CASE_INSENSITIVE_ORDER))
        .map(DtoMapper::getDTO)
        .collect(Collectors.toList());
  }

  public UserDTO getPartnerPanelUserForLoggedPartner(long userId) {
    partnerUserAccessService.requireCanManagePartnerUsers();
    return DtoMapper.getDTO(getPartnerPanelUserEntity(userId));
  }

  public UserDTO createPartnerPanelUserForLoggedPartner(UserDTO dto) {
    partnerUserAccessService.requireCanManagePartnerUsers();
    validatePartnerPanelUserPayload(dto, true, null);

    Partner partner = getLoggedPartner();
    Role accessRole = getPartnerPanelAccessRole(dto);
    User user = createPartnerPanelUser(dto, partner, accessRole);
    user.setAllowedPartnerSights(getAllowedSightsForPartner(partner, accessRole, dto.allowedSightIds));
    return DtoMapper.getDTO(user);
  }

  public UserDTO createPartnerPanelUserFromHelpdesk(long partnerId, UserDTO dto) {
    validatePartnerPanelUserPayload(dto, true, null);

    Partner partner = partnerService.get(partnerId);
    Role accessRole = getPartnerPanelAccessRole(dto);
    User user = createPartnerPanelUser(dto, partner, accessRole);
    user.setAllowedPartnerSights(getAllowedSightsForPartner(partner, accessRole, dto.allowedSightIds));
    return DtoMapper.getDTO(user);
  }

  public UserDTO updatePartnerPanelUserForLoggedPartner(long userId, UserDTO dto) {
    partnerUserAccessService.requireCanManagePartnerUsers();
    User user = getPartnerPanelUserEntity(userId);
    validatePartnerPanelUserPayload(dto, false, user);

    if (StringUtils.isNotBlank(dto.email)) {
      user.setEmail(StringUtils.trim(dto.email).toLowerCase());
    }
    if (StringUtils.isNotBlank(dto.name)) {
      setPartnerPanelUserName(user, dto.name);
    }
    Role accessRole = getPartnerPanelAccessRole(dto);
    replacePartnerPanelAccessRole(user, accessRole);
    user.setAllowedPartnerSights(
        getAllowedSightsForPartner(user.getPartner(), accessRole, dto.allowedSightIds));
    return DtoMapper.getDTO(user);
  }

  public UserDTO updatePartnerPanelUserFromHelpdesk(long partnerId, long userId, UserDTO dto) {
    User user = getHelpdeskPartnerUserEntity(partnerId, userId);
    requirePartnerPanelManagedUser(user);
    validatePartnerPanelUserPayload(dto, false, user);

    if (StringUtils.isNotBlank(dto.email)) {
      user.setEmail(StringUtils.trim(dto.email).toLowerCase());
    }
    if (StringUtils.isNotBlank(dto.name)) {
      setPartnerPanelUserName(user, dto.name);
    }
    Role accessRole = getPartnerPanelAccessRole(dto);
    replacePartnerPanelAccessRole(user, accessRole);
    user.setAllowedPartnerSights(
        getAllowedSightsForPartner(user.getPartner(), accessRole, dto.allowedSightIds));
    return DtoMapper.getDTO(user);
  }

  public void changePasswordForPartnerPanelUser(long userId, UserAuthDTO dto) {
    partnerUserAccessService.requireCanManagePartnerUsers();
    User user = getPartnerPanelUserEntity(userId);
    user.changePassword(dto.password);
    emailService.sendPasswordChangedEmail(user.getEmail(), dto.password);
  }

  public void changePasswordForPartnerUserFromHelpdesk(long partnerId, long userId, String password) {
    User user = getHelpdeskPartnerUserEntity(partnerId, userId);
    if (isPartnerPanelManagedUser(user)) {
      user.changePassword(password);
      emailService.sendPasswordChangedEmail(user.getEmail(), password);
      return;
    }
    updatePasswordForUser(user, password);
  }

  public UserDTO setPartnerPanelUserBlockedForLoggedPartner(long userId, boolean blocked) {
    partnerUserAccessService.requireCanManagePartnerUsers();
    User user = getPartnerPanelUserEntity(userId);
    user.setBlocked(blocked);
    return DtoMapper.getDTO(user);
  }

  public UserDTO setPartnerPanelUserBlockedFromHelpdesk(long partnerId, long userId,
      boolean blocked) {
    User user = getHelpdeskPartnerUserEntity(partnerId, userId);
    requirePartnerPanelManagedUser(user);
    user.setBlocked(blocked);
    return DtoMapper.getDTO(user);
  }

  public void deletePartnerPanelUserForLoggedPartner(long userId) {
    partnerUserAccessService.requireCanManagePartnerUsers();
    User user = getPartnerPanelUserEntity(userId);
    deletePartnerPanelUserAccount(user);
  }

  public void deletePartnerPanelUserFromHelpdesk(long partnerId, long userId) {
    User user = getHelpdeskPartnerUserEntity(partnerId, userId);
    requirePartnerPanelManagedUser(user);
    deletePartnerPanelUserAccount(user);
  }

  private void deletePartnerPanelUserAccount(User user) {
    user.setDeleted(true);
    user.setBlocked(true);
    user.setEmail("deleted+" + user.getId() + "+" + System.currentTimeMillis()
        + "@hello-poland.pl");
    user.changePassword(RandomStringUtils.randomAlphanumeric(32));
    user.setAllowedPartnerSights(new HashSet<>());
    em.merge(user);
  }

  private User getPartnerPanelUserEntity(long userId) {
    Partner partner = getLoggedPartner();
    User user = em.createQuery(
            "select distinct u from User u left join fetch u.roles r left join fetch u.allowedPartnerSights aps "
                + "where u.id = :id and u.partner = :partner and u.deleted = false",
            User.class)
        .setParameter("id", userId)
        .setParameter("partner", partner)
        .getResultStream()
        .findFirst()
        .orElseThrow(AccessDeniedException::new);
    if (!isPartnerPanelManagedUser(user)) {
      throw new AccessDeniedException();
    }
    return user;
  }

  private User getHelpdeskPartnerUserEntity(long partnerId, long userId) {
    Partner partner = partnerService.get(partnerId);
    User user = em.createQuery(
            "select distinct u from User u left join fetch u.roles r left join fetch u.allowedPartnerSights aps "
                + "where u.id = :id and u.partner = :partner and u.deleted = false",
            User.class)
        .setParameter("id", userId)
        .setParameter("partner", partner)
        .getResultStream()
        .findFirst()
        .orElseThrow(AccessDeniedException::new);
    if (!isHelpdeskPartnerUser(user)) {
      throw new AccessDeniedException();
    }
    return user;
  }

  private boolean isHelpdeskPartnerUser(User user) {
    return hasAnyRole(user, Role.PARTNER, Role.PARTNER_ADMIN, Role.PARTNER_SALESMAN)
        && !hasAnyRole(user, Role.ADMIN, Role.ROOT, Role.SALESMAN,
            Role.HELPDESK_PARTNER_MANAGER, Role.HELPDESK_CONTENT_MANAGER,
            Role.HELPDESK_SUPPORT, Role.HELPDESK_TECHNICAL);
  }

  private User createPartnerPanelUser(UserDTO dto, Partner partner, Role accessRole) {
    User user = new User(Role.PARTNER, accessRole);
    user.setEmail(StringUtils.trim(dto.email).toLowerCase());
    user.changePassword(dto.password);
    user.setPartner(partner);
    setPartnerPanelUserName(user, dto.name);
    em.persist(user);
    return user;
  }

  private void setPartnerPanelUserName(User user, String name) {
    UserDetails details = Optional.ofNullable(user.getDetails()).orElseGet(UserDetails::new);
    details.setFirstName(StringUtils.trimToNull(name));
    details.setLastName(null);
    user.setDetails(details);
  }

  private boolean isPartnerPanelManagedUser(User user) {
    return hasAnyRole(user, Role.PARTNER_ADMIN, Role.PARTNER_SALESMAN);
  }

  private void requirePartnerPanelManagedUser(User user) {
    if (!isPartnerPanelManagedUser(user)) {
      throw new ConflictingException("Konto gĹ‚Ăłwne partnera nie moĹĽe byÄ‡ edytowane jako konto panelowe.");
    }
  }

  private void validatePartnerPanelUserPayload(UserDTO dto, boolean passwordRequired, User existing) {
    if (dto == null) {
      throw new ConflictingException("User data is required.");
    }
    if (StringUtils.isBlank(dto.email)) {
      throw new ConflictingException("Email is required.");
    }
    if (passwordRequired && StringUtils.isBlank(dto.password)) {
      throw new ConflictingException("Password is required.");
    }
    Role accessRole = getPartnerPanelAccessRole(dto);
    if (accessRole == Role.PARTNER_SALESMAN
        && (dto.allowedSightIds == null || dto.allowedSightIds.isEmpty())) {
      throw new ConflictingException("Dla roli Salesman wybierz przynajmniej jeden obiekt.");
    }
    String email = StringUtils.trim(dto.email).toLowerCase();
    Optional<User> userWithEmail = findByEmail(email)
        .filter(user -> existing == null || !user.getId().equals(existing.getId()));
    if (userWithEmail.isPresent()) {
      throw new ConflictingException("Podany adres e-mail jest juz uzywany.");
    }
    findByEmail(email)
        .filter(user -> existing == null || !user.getId().equals(existing.getId()))
        .ifPresent(user -> {
          throw new ConflictingException("Podany adres e-mail jest juĹĽ uĹĽywany.");
        });
    dto.email = email;
  }

  private Role getPartnerPanelAccessRole(UserDTO dto) {
    Set<RoleDTO> roles = Optional.ofNullable(dto.roles).orElse(Set.of());
    boolean admin = roles.contains(RoleDTO.PARTNER_ADMIN);
    boolean salesman = roles.contains(RoleDTO.PARTNER_SALESMAN);
    if (admin == salesman) {
      throw new ConflictingException("Exactly one partner access role is required.");
    }
    return admin ? Role.PARTNER_ADMIN : Role.PARTNER_SALESMAN;
  }

  private void replacePartnerPanelAccessRole(User user, Role role) {
    em.createQuery("delete from UserRole ur where ur.user = :user and ur.role in (:roles)")
        .setParameter("user", user)
        .setParameter("roles", List.of(Role.PARTNER_ADMIN, Role.PARTNER_SALESMAN))
        .executeUpdate();
    if (user.getRoles() != null) {
      user.getRoles().removeIf(
          ur -> ur.getRole() == Role.PARTNER_ADMIN || ur.getRole() == Role.PARTNER_SALESMAN);
    }
    if (!user.hasRole(Role.PARTNER)) {
      createUserRole(user, Role.PARTNER);
    }
    createUserRole(user, role);
  }

  private Set<Sight> getAllowedSightsForPartner(Partner partner, Role role, List<Long> sightIds) {
    if (role == Role.PARTNER_ADMIN) {
      return new HashSet<>();
    }
    if (sightIds == null || sightIds.isEmpty()) {
      return new HashSet<>();
    }
    Set<Sight> sights = new HashSet<>(em.createQuery(
            "from Sight where partner = :partner and active = true and id in (:ids)",
            Sight.class)
        .setParameter("partner", partner)
        .setParameter("ids", sightIds)
        .getResultList());
    if (sights.size() != new HashSet<>(sightIds).size()) {
      throw new AccessDeniedException();
    }
    return sights;
  }

  private void requireCanManageHelpdeskUsers() {
    if (!hasAnyRole(getLoggedUser(), Role.ADMIN, Role.ROOT)) {
      throw new AccessDeniedException();
    }
  }

  private User getHelpdeskUserEntity(long userId) {
    User user = em.createQuery(
            "select distinct u from User u left join fetch u.roles r "
                + "left join fetch u.allowedHelpdeskPartners ahp "
                + "left join fetch u.allowedHelpdeskSights ahs "
                + "where u.id = :id and u.partner is null and u.deleted = false",
            User.class)
        .setParameter("id", userId)
        .getResultStream()
        .findFirst()
        .orElseThrow(AccessDeniedException::new);
    if (!isHelpdeskUser(user)) {
      throw new AccessDeniedException();
    }
    return user;
  }

  public boolean isHelpdeskUser(User user) {
    return user != null && hasAnyRole(user, HELPDESK_USER_ROLES.toArray(new Role[0]));
  }

  /**
   * A password reset requested through the public portal is available only to an account that can
   * actually authenticate through /market/login.
   */
  public boolean isActiveMarketUser(User user) {
    return user != null
        && !user.isDeleted()
        && !user.isBlocked()
        && user.isEmailVerified()
        && user.getPartner() == null
        && user.hasRole(Role.USER);
  }

  private void requireCanModifyHelpdeskUser(User user) {
    User loggedUser = getLoggedUser();
    if (loggedUser.hasRole(Role.ROOT)) {
      return;
    }
    if (loggedUser.hasRole(Role.ADMIN) && !hasAnyRole(user, Role.ADMIN, Role.ROOT)) {
      return;
    }
    throw new AccessDeniedException();
  }

  private void requireNotCurrentUser(User user) {
    if (user.getId().equals(getLoggedUser().getId())) {
      throw new AccessDeniedException();
    }
  }

  private void requireCanRemoveAdminAccess(User user) {
    if (hasAnyRole(user, Role.ADMIN, Role.ROOT) && countActiveAdminUsers() <= 1) {
      throw new ConflictingException("Nie mozna usunac ostatniego aktywnego administratora.");
    }
  }

  private long countActiveAdminUsers() {
    return em.createQuery(
            "select count(distinct u) from User u join u.roles roles "
                + "where u.deleted = false and u.blocked = false and roles.role in (:roles)",
            Long.class)
        .setParameter("roles", List.of(Role.ADMIN, Role.ROOT))
        .getSingleResult();
  }

  private void validateHelpdeskUserPayload(UserDTO dto, boolean passwordRequired, User existing) {
    if (dto == null) {
      throw new ConflictingException("User data is required.");
    }
    if (StringUtils.isBlank(dto.email)) {
      throw new ConflictingException("Email is required.");
    }
    if (passwordRequired && StringUtils.isBlank(dto.password)) {
      throw new ConflictingException("Password is required.");
    }
    getHelpdeskRoles(dto);
    if (existing != null && existing.hasRole(Role.HELPDESK_TECHNICAL)
        && !getLoggedUser().hasRole(Role.ROOT)) {
      throw new AccessDeniedException();
    }
    String email = StringUtils.trim(dto.email).toLowerCase();
    findByEmail(email)
        .filter(user -> existing == null || !user.getId().equals(existing.getId()))
        .ifPresent(user -> {
          throw new ConflictingException("Podany adres e-mail jest juz uzywany.");
        });
    dto.email = email;
  }

  private Set<Role> getHelpdeskRoles(UserDTO dto) {
    Set<RoleDTO> dtoRoles = Optional.ofNullable(dto.roles).orElse(Set.of());
    Set<Role> roles = dtoRoles.stream()
        .map(role -> Role.valueOf(role.name()))
        .filter(HELPDESK_USER_ROLES::contains)
        .collect(Collectors.toSet());
    if (roles.isEmpty()) {
      throw new ConflictingException("At least one helpdesk role is required.");
    }
    if (roles.stream().anyMatch(role -> role == Role.ADMIN || role == Role.ROOT
            || role == Role.HELPDESK_TECHNICAL)
        && !getLoggedUser().hasRole(Role.ROOT)) {
      throw new AccessDeniedException();
    }
    return roles;
  }

  private void replaceHelpdeskRoles(User user, Set<Role> roles, User existing) {
    if (existing != null && hasAnyRole(existing, Role.ADMIN, Role.ROOT)
        && roles.stream().noneMatch(role -> role == Role.ADMIN || role == Role.ROOT)) {
      requireCanRemoveAdminAccess(existing);
    }
    em.createQuery("delete from UserRole ur where ur.user = :user and ur.role in (:roles)")
        .setParameter("user", user)
        .setParameter("roles", HELPDESK_USER_ROLES)
        .executeUpdate();
    if (user.getRoles() != null) {
      user.getRoles().removeIf(ur -> HELPDESK_USER_ROLES.contains(ur.getRole()));
    }
    roles.forEach(role -> createUserRole(user, role));
  }

  private void updateHelpdeskScope(User user, UserDTO dto) {
    user.setAllowedHelpdeskPartners(getAllowedHelpdeskPartners(dto.allowedHelpdeskPartnerIds));
    user.setAllowedHelpdeskSights(getAllowedHelpdeskSights(dto.allowedHelpdeskSightIds));
  }

  private Set<Partner> getAllowedHelpdeskPartners(List<Long> partnerIds) {
    if (partnerIds == null || partnerIds.isEmpty()) {
      return new HashSet<>();
    }
    Set<Long> uniqueIds = new HashSet<>(partnerIds);
    Set<Partner> partners = new HashSet<>(em.createQuery(
            "from Partner where id in (:ids)",
            Partner.class)
        .setParameter("ids", uniqueIds)
        .getResultList());
    if (partners.size() != uniqueIds.size()) {
      throw new AccessDeniedException();
    }
    return partners;
  }

  private Set<Sight> getAllowedHelpdeskSights(List<Long> sightIds) {
    if (sightIds == null || sightIds.isEmpty()) {
      return new HashSet<>();
    }
    Set<Long> uniqueIds = new HashSet<>(sightIds);
    Set<Sight> sights = new HashSet<>(em.createQuery(
            "from Sight where active = true and id in (:ids)",
            Sight.class)
        .setParameter("ids", uniqueIds)
        .getResultList());
    if (sights.size() != uniqueIds.size()) {
      throw new AccessDeniedException();
    }
    return sights;
  }

  private void createUserRole(User user, Role role) {
    UserRole ur = new UserRole();
    ur.setUser(user);
    ur.setRole(role);
    em.persist(ur);
    if (user.getRoles() == null) {
      user.setRoles(new ArrayList<>());
    }
    user.getRoles().add(ur);
  }

  public Set<String> getFlatRoles(String email) {
    return findUndeletedByEmail(email).get().getRoles().stream().map(UserRole::getRole)
        .map(Role::toString)
        .collect(Collectors.toSet());
  }

  // TODO delete this
  public void globalRework() {
    List<User> all = getAll();
    for (User user : all) {
      String oldName = user.getName();

      try {
        if (user.getDetails() == null) {
          user.setDetails(new UserDetails());
        }
        user.getDetails().setFirstName(NameAndAddressSplitter.getFirstName(oldName));
        user.getDetails().setLastName(NameAndAddressSplitter.getLastName(oldName));
      } catch (ConflictingException e) {
        logger.log(Level.ERROR,
            "Did not updated first and last name for user[id:" + user.getId() + "]");
      }

      em.merge(user);
    }

  }

  public List<User> getAll() {
    return em.createQuery("from User", User.class)
        .getResultList();
  }

  public User findPartnerUserByEmailAndRole(Partner partner, String email, Role role) {
    return em.createQuery(
            "select distinct u from User u join u.roles roles "
                + "where roles.role = :role and u.partner = :partner "
                + "and lower(u.email) = :email and u.deleted = false",
            User.class)
        .setParameter("role", role)
        .setParameter("partner", partner)
        .setParameter("email", email.toLowerCase(Locale.ROOT))
        .getResultStream()
        .findFirst()
        .orElseThrow(() -> new ConflictingException(
            "Nie znaleziono głównego konta partnera dla adresu " + email));
  }

    public void createPasswordResetToken(User user, String token) {
        createPasswordResetToken(user, token, "portal.url");
    }

    public void createPasswordResetToken(User user, String token, String portalUrlProperty) {
        int ttl = Integer.parseInt(
                properties.getProperty("password.reset.token.ttl.minutes", "30")
        );

        UserToken ut = new UserToken();
        ut.setToken(token);
        ut.setUser(user);
        ut.setType(UserToken.Type.PASSWORD_RESET);
        ut.setExpiryDate(LocalDateTime.now().plusMinutes(ttl));
        ut.setUsed(false);

        em.persist(ut);

        String portalUrl = properties.getProperty(
                portalUrlProperty,
                properties.getProperty("portal.url", "https://hello-poland.pl")
        );

        String resetUrl = portalUrl + "/reset-password?token=" + token;

        String subject = properties.getProperty(
                "password.reset.mail.subject",
                "Reset hasła - Hello Poland"
        );

        String bodyTemplate = properties.getProperty("password.reset.mail.body", "");

        String body = bodyTemplate
                .replace("{resetUrl}", resetUrl)
                .replace("{ttlMinutes}", String.valueOf(ttl));
        body = body.replace("\\n", System.lineSeparator());

        try {
            emailService.sendHtmlEmail(
                    new Email(
                            user.getEmail(),
                            subject,
                            body
                    ),
                    ACCOUNT_EMAIL_SENDER
            );
        } catch (Exception e) {
            logger.log(System.Logger.Level.ERROR,
                    "Failed to send password reset email for user[id:" + user.getId() + "]",
                    e);
        }
    }

    public UserToken findValidPasswordResetToken(String token) {

        return em.createQuery("""
            from UserToken t
            where t.token = :token
              and t.type = :type
              and t.used = false
              and t.expiryDate > :now
            """, UserToken.class)
                .setParameter("token", token)
                .setParameter("type", UserToken.Type.PASSWORD_RESET)
                .setParameter("now", LocalDateTime.now())
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    public boolean confirmPasswordReset(String token, String newPassword) {

        UserToken ut = findValidPasswordResetToken(token);

        if (ut == null) {
            return false;
        }
        User user = ut.getUser();
        // zmiana hasla
        user.changePassword(newPassword);
        // oznaczenie tokena jako uzyty
        ut.setUsed(true);

        emailService.sendPasswordChangedEmail(user.getEmail(), newPassword);

        return true;
    }

    public void createEmailVerificationToken(User user) {

        int ttl = Integer.parseInt(
                properties.getProperty("email.verification.token.ttl.minutes", "60")
        );

        String token = java.util.UUID.randomUUID().toString()
                + java.util.UUID.randomUUID().toString();

        UserToken ut = new UserToken();
        ut.setToken(token);
        ut.setUser(user);
        ut.setType(UserToken.Type.EMAIL_VERIFICATION);
        ut.setExpiryDate(LocalDateTime.now().plusMinutes(ttl));
        ut.setUsed(false);

        em.persist(ut);

        String portalUrl = properties.getProperty(
                "portal.url",
                "https://hello-poland.pl"
        );

        String activationUrl = portalUrl + "/activate-account?token=" + token;

        String subject = properties.getProperty(
                "email.verification.mail.subject",
                "Aktywacja konta - Hello Poland"
        );

        String bodyTemplate =
                properties.getProperty("email.verification.mail.body", "");

        String body = bodyTemplate
                .replace("{activationUrl}", activationUrl)
                .replace("{ttlMinutes}", String.valueOf(ttl))
                .replace("\\n", System.lineSeparator());

        try {
            emailService.sendHtmlEmail(
                    new Email(user.getEmail(), subject, body),
                    ACCOUNT_EMAIL_SENDER
            );
        } catch (Exception e) {
            logger.log(System.Logger.Level.ERROR,
                    "Failed to send activation email for user[id:" + user.getId() + "]",
                    e);
        }
    }

    public boolean confirmEmailVerification(String token) {

        UserToken ut = em.createQuery("""
        from UserToken t
        where t.token = :token
          and t.type = :type
          and t.used = false
          and t.expiryDate > :now
        """, UserToken.class)
                .setParameter("token", token)
                .setParameter("type", UserToken.Type.EMAIL_VERIFICATION)
                .setParameter("now", LocalDateTime.now())
                .getResultStream()
                .findFirst()
                .orElse(null);

        if (ut == null) {
            return false;
        }

        User user = ut.getUser();
        user.setEmailVerified(true);
        ut.setUsed(true);

        return true;
    }

    public void deletePartnerUserFromHelpdesk(User user) {
        if (user == null || user.isDeleted()) {
            throw new NotFoundException();
        }

        if (user.getPartner() == null) {
            throw new ConflictingException("User is not assigned to partner.");
        }

        if (user.hasRole(Role.ADMIN) || user.hasRole(Role.ROOT)) {
            throw new ConflictingException("Cannot delete admin user.");
        }

        user.setDeleted(true);
        //user.setEmail("deleted+" + user.getId() + "+" + System.currentTimeMillis() + "@hello-poland.pl");
        //user.changePassword(RandomStringUtils.randomAlphanumeric(32));

        em.merge(user);
    }

    public void deleteUsher(long usherId) {
        Portal hpt = getPortal("Hello Ticket Cloud");
        HelloTicket ht = new HelloTicket(hpt.getUrl());
        ht.deleteUsherForPartner(usherId, getLoggedPartner().getHptToken());
    }

    public void deleteUsherForPartnerFromHelpdesk(Long partnerId, Long usherId) {
        Partner partner = partnerService.get(partnerId);
        Portal hpt = getPortal("Hello Ticket Cloud");
        HelloTicket ht = new HelloTicket(hpt.getUrl());
        ht.deleteUsherForPartner(usherId, partner.getHptToken());
    }

}
