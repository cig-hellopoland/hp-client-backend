package pl.hellopoland.service;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import pl.hellopoland.bo.*;
import pl.hellopoland.config.SightEventPagedCollectionConfig;
import pl.hellopoland.dto.*;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.exception.badrequest.BadRequestException;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.exception.notfound.AccessDeniedException;
import pl.hellopoland.exception.notfound.ResourceNotFoundException;
import pl.hellopoland.service.vo.HptTpdsDownloadConfigurator;
import pl.hellopoland.util.*;

import jakarta.ejb.EJBAccessException;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

@LocalBean
@Stateless
public class SightEventService extends ServiceSuperclass {

  private static final int SIGHT_EVENT_DESCRIPTION_MAX_LENGTH = 2500;
  private static final int SIGHT_EVENT_DIRECTIONS_MAX_LENGTH = 1000;
  private static final Map<String, String> POLISH_VOIVODESHIPS = Map.ofEntries(
      Map.entry("dolnośląskie", "Dolnośląskie"),
      Map.entry("kujawsko-pomorskie", "Kujawsko-pomorskie"),
      Map.entry("lubelskie", "Lubelskie"),
      Map.entry("lubuskie", "Lubuskie"),
      Map.entry("łódzkie", "Łódzkie"),
      Map.entry("małopolskie", "Małopolskie"),
      Map.entry("mazowieckie", "Mazowieckie"),
      Map.entry("opolskie", "Opolskie"),
      Map.entry("podkarpackie", "Podkarpackie"),
      Map.entry("podlaskie", "Podlaskie"),
      Map.entry("pomorskie", "Pomorskie"),
      Map.entry("śląskie", "Śląskie"),
      Map.entry("świętokrzyskie", "Świętokrzyskie"),
      Map.entry("warmińsko-mazurskie", "Warmińsko-mazurskie"),
      Map.entry("wielkopolskie", "Wielkopolskie"),
      Map.entry("zachodniopomorskie", "Zachodniopomorskie"));

  @Inject
  UserService userService;
  @Inject
  ImageService iService;
  @Inject
  SightService sightService;
  @Inject
  PartnerService partnerService;
  @Inject
  PartnerUserAccessService partnerUserAccessService;
  @Inject
  OpeningHoursService oHoursService;
  @Inject
  FileDescriptorService fdService;
  @Inject
  TranslationService translationService;
  @Inject
  CategoryService catService;
  @Inject
  TagService tagService;

    @Inject
    PostalCodeDictionaryLookup pcd;

    private void enrichLocation(Location loc) {
        if (loc == null) return;

        boolean missing =
                (loc.getVoivodeship() == null || loc.getVoivodeship().isBlank()) ||
                        (loc.getCounty() == null || loc.getCounty().isBlank()) ||
                        (loc.getCommune() == null || loc.getCommune().isBlank());

        if (!missing) return;
        if (loc.getZipCode() == null || loc.getZipCode().isBlank()) return;
        if (loc.getCity() == null || loc.getCity().isBlank()) return;

        pcd.findByZipAndCity(loc.getZipCode(), loc.getCity()).ifPresent(ad -> {
            if (loc.getCommune() == null || loc.getCommune().isBlank()) loc.setCommune(ad.commune());
            if (loc.getCounty() == null || loc.getCounty().isBlank()) loc.setCounty(ad.county());
            if (loc.getVoivodeship() == null || loc.getVoivodeship().isBlank())
                loc.setVoivodeship(ad.voivodeship());
        });
    }

  public List<Long> getAllActiveAndPublishedAndNotBlocked() {
    return em.createQuery(
        "select hptId from SightEvent where active is true and published is true and blocked is false",
        Long.class).getResultList();
  }

  public PagedEntityCollection<SightEvent> getList(SightEventPagedCollectionConfig config) {
    if (config.getDateFrom() != null && config.getDateTo() != null
        && config.getDateTo().before(config.getDateFrom())) {
      throw new ConflictingException(
          "toDate[" + config.getDateTo() + "] is before fromDate[" + config.getDateFrom() + "]");
    }
    if (config.isCurrentPartner()) {
      config.setPartner(partnerService.findByUserEmail(ctx.getCallerPrincipal().getName()).getId());
      Set<Long> allowedSightIds = partnerUserAccessService.getAllowedSightIdsForLoggedUser();
      if (partnerUserAccessService.isPartnerSalesman(getLoggedUser())) {
        config.setSightIds(allowedSightIds);
      }
    }
    User loggedUser = userService.getLoggedUser();
    if (config.isLoggedUserFavourites()) {
      config.onlyFavourite(loggedUser.getId());
    }
      List<SightEvent> sightEvents = Optional.ofNullable(getQuery(config).getResultList()).orElseGet(ArrayList::new);

    sightEvents.forEach(s -> s.setFavourite( Optional.ofNullable(s.getUsers()).map(u -> u.contains(loggedUser)).orElse(false)));
    if (!sightEvents.isEmpty() && config.isFetchCategories()) {
      List<SightEventCategory> categories = catService.getFor(sightEvents);
      Map<SightEvent, Set<SightEventCategory>> grouped = categories.stream()
          .collect(groupingBy(SightEventCategory::getSightEvent, toSet()));
        sightEvents.forEach(se -> se.setCategories(grouped.getOrDefault(se, Collections.emptySet())));

    }
    if (!sightEvents.isEmpty() && config.isFetchTags()) {
      List<SightEventTag> tags = tagService.getFor(sightEvents);
      Map<SightEvent, Set<SightEventTag>> grouped = tags.stream()
          .collect(groupingBy(SightEventTag::getSightEvent, toSet()));
        sightEvents.forEach(se -> se.setTags(grouped.getOrDefault(se, Collections.emptySet())));

    }
      if (config.getLanguage() != null) {
          var translated = translationService.translateEntities(sightEvents, config.getLanguage());
          if (translated != null) {
              sightEvents = translated;
          }
      }

      if (config.getDateFrom() != null || config.getDateTo() != null) {
          HelloTicket hptClient = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
          var inRange = hptClient.getSightEventsInDateRange(new ArrayList<>(sightEvents),
                  config.getDateFrom(), config.getDateTo());
          if (inRange != null) {
              sightEvents = inRange;
          }
      }

      sightEvents.sort(sightEventPromotionComparator().thenComparing(sightEventNamesComparator()));
    return new PagedEntityCollection<>(sightEvents, config);
  }

  private Comparator<SightEvent> sightEventPromotionComparator() {
    return Comparator.nullsLast(Comparator.comparing(SightEvent::getPromotion,
        Comparator.nullsLast(Comparator.naturalOrder())));
  }

  private Comparator<SightEvent> sightEventNamesComparator() {
    return Comparator.comparing(SightEvent::getName, polishComparator());
  }

  public SightEvent get(Long id) {
    SightEvent se = em.find(SightEvent.class, id);
    if (se == null) {
      throw new ResourceNotFoundException();
    }
    se.fetchRelations();
    se.setFavourite(se.getUsers().contains(userService.getLoggedUser()));
    return se;
  }


  public void delete(Long id) {
    SightEvent bo = get(id);

    Partner partner = getOwningPartner(bo);
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket helloTicket = new HelloTicket(hpt.getUrl());
    helloTicket.deleteSightEvent(bo, partner.getHptToken());

    bo.setActive(false);
  }

  public SightEvent create(SightEventDTO dto, Partner partner) {
    SeasonalClosure.validate(dto.seasonallyClosed, dto.seasonalClosureFrom, dto.seasonalClosureTo);
    validateSightEventTextLengths(dto);

    if (dto.sightId == null) {
      throw new ConflictingException("sightId can't be null.");
    }
    boolean loggedPartnerContext = partner == null;
    if (loggedPartnerContext) {
      partner = partnerService.findByUserEmail(ctx.getCallerPrincipal().getName());
    }
    Sight sight = sightService.get(dto.sightId);
    if (!sight.getPartner().getId().equals(partner.getId())) {
      throw new AccessDeniedException();
    }
    if (loggedPartnerContext) {
      partnerUserAccessService.requireCanAccessSight(sight);
    }
    var defLang = dto.defaultLanguage;
    var availableLanguageVersions = dto.availableLanguageVersions;
    dto.generalAdmission = Boolean.TRUE.equals(dto.generalAdmission);
    fetchImagesOriginalById(dto);
    validatePublishedSightEventHasNormalTicket(dto, partner, null);
    List<ImageDTO> gallery = dto.images;
    Portal hpt = getPortal("Hello Ticket Cloud");
    HelloTicket helloTicket = createHelloTicket(hpt);
    // HelloTicket does not store voivodeship, county or commune. Keep the submitted location.
    LocationDTO location = dto.location;
    FileDescriptorDTO pdf = dto.pdfAttachment;
    dto.pdfAttachment = null;
    // These fields belong to HP; Hello Ticket does not return them.
    SightEventDTO submitted = dto;
    dto = helloTicket.addSightEvent(dto, partner.getHptToken());
    dto.seasonallyClosed = submitted.seasonallyClosed;
    dto.seasonalClosureFrom = submitted.seasonalClosureFrom;
    dto.seasonalClosureTo = submitted.seasonalClosureTo;
    dto.location = location;
    dto.pdfAttachment = pdf;
    dto.images = gallery;
    dto.defaultLanguage = defLang;
    dto.availableLanguageVersions = availableLanguageVersions;
    SightEvent bo = new SightEvent();
    DtoMapper.copy(dto, bo);
    enrichLocation(bo.getLocation());
    if (sight != null) {
      bo.setSight(sight);
      bo.setPartner(sight.getPartner());
      if (sight.getAgreements() != null && !sight.getAgreements().isEmpty()) {
        bo.setAgreements(Set.copyOf(sight.getAgreements()));
      }
    }
    iService.handleImagesUpdate(bo, dto);
    handleAttachmentUpdate(bo, dto);
    bo.setPortal(hpt);

    em.persist(bo);

    ArrayList<OpeningHours> oHoursList = getOpeningHoursCollectionFromDTO(dto);
    if (oHoursList != null && !oHoursList.isEmpty()) {
      oHoursList.stream().forEach(oh -> {
        oh.setSightEvent(bo);
        oHoursService.persist(oh);
      });
      bo.setOpeningHours(oHoursList);
    }
    em.refresh(sight);
    sightService.recreateSearchIndex(sight);
    recreateSearchIndex(bo);
    logger.log(Logger.Level.INFO, "Saved new sight event: " + bo.getName());
    return createLanguageVersion(DtoMapper.getDTO(bo), partner, bo.getDefaultLanguage());
  }

  private void fetchImagesOriginalById(SightEventDTO dto) {
    if (dto.mainImage != null && dto.mainImage.id != null) {
      dto.mainImage.original = DtoMapper.getDTO(iService.get(dto.mainImage.id)).original;
    }
    if (dto.images != null) {
      for (ImageDTO im : dto.images) {
        if (im.id != null) {
          im.original = DtoMapper.getDTO(iService.get(im.id)).original;
        }
      }
    }
  }

  private ArrayList<OpeningHours> getOpeningHoursCollectionFromDTO(SightEventDTO dto) {
    return Optional.ofNullable(dto.openingHours)
        .map(l -> l.stream().map(oh -> DtoMapper.copy(oh, new OpeningHours()))
            .collect(toCollection(ArrayList::new)))
        .orElse(null);
  }

  private SightEvent createLanguageVersion(SightEventDTO dto, Partner partner,
      LanguageVersion language) {
    validateSightEventTextLengths(dto);
    return translationService.createEntityLanguageVersion(getForPartner(dto.id, partner), dto,
        language);
  }

  public SightEvent createLanguageVersion(SightEventDTO dto, LanguageVersion language) {
    validateSightEventTextLengths(dto);
    SightEvent bo = get(dto.id);
    return translationService.createEntityLanguageVersion(bo, dto, language);
  }

  public SightEvent createLanguageVersionForLoggedUser(SightEventDTO dto,
      LanguageVersion language) {
    validateSightEventTextLengths(dto);
    return translationService.createEntityLanguageVersion(getForLoggedUser(dto.id), dto, language);
  }

  public SightEvent updateForLoggedUser(SightEventDTO dto, LanguageVersion language) {
    SightEvent bo = getForLoggedUser(dto.id);
    return update(bo, dto, language);
  }

  public SightEvent update(SightEvent bo, SightEventDTO dto, LanguageVersion language) {
    SeasonalClosure.validate(dto.seasonallyClosed, dto.seasonalClosureFrom, dto.seasonalClosureTo);
    validateSightEventTextLengths(dto);

    alignPartnerWithSight(bo);
    if (!translationService.isTranslated(bo, language)) {
      // throw new ConflictingException(
      // "Translation for language " + language.getLanuage() + " doesn't exists");
      createLanguageVersion(dto, language);
    }
    if (bo.getDefaultLanguage().equals(language)) {
      Partner partner = getOwningPartner(bo);
      validatePublishedSightEventHasNormalTicket(dto, partner, bo.getHptId(), bo.isPublished());
      fetchImagesOriginalById(dto);
      if (bo.getPortal().getType() == Portal.Type.HELLOTICKET_CLOUD_1) {
        Portal hpt = getPortal("Hello Ticket Cloud");
        HelloTicket helloTicket = createHelloTicket(hpt);
        dto.id = bo.getHptId();
        LocationDTO location = dto.location;
        FileDescriptorDTO pdf = dto.pdfAttachment;
        dto.pdfAttachment = null;
        List<ImageDTO> gallery = dto.images;
        // These fields belong to HP; Hello Ticket does not return them.
        SightEventDTO submitted = dto;
        dto = helloTicket.updateSightEvent(dto, partner.getHptToken());
        dto.seasonallyClosed = submitted.seasonallyClosed;
        dto.seasonalClosureFrom = submitted.seasonalClosureFrom;
        dto.seasonalClosureTo = submitted.seasonalClosureTo;
        dto.location = location;
        dto.images = gallery;
        dto.pdfAttachment = pdf;
      }
      DtoMapper.copy(dto, bo);
        enrichLocation(bo.getLocation());
      alignPartnerWithSight(bo);
      oHoursService.remove(bo.getOpeningHours());
      ArrayList<OpeningHours> oHoursList = getOpeningHoursCollectionFromDTO(dto);
      if (oHoursList != null && !oHoursList.isEmpty()) {
        oHoursList.stream().forEach(oh -> {
          oh.setSightEvent(bo);
          oHoursService.persist(oh);
        });
      }
      bo.setOpeningHours(null);
      bo.setOpeningHours(oHoursList);
      iService.handleImagesUpdate(bo, dto);
      handleAttachmentUpdate(bo, dto);
      Sight sight = em.merge(bo.getSight());
      em.refresh(sight);
      sightService.recreateSearchIndex(sight);
      recreateSearchIndex(bo);
      em.flush();
    }
    return translationService.updateEntityLanguageVersion(bo, dto, language);
  }

  HelloTicket createHelloTicket(Portal portal) {
    return new HelloTicket(portal.getUrl());
  }

  private void validatePublishedSightEventHasNormalTicket(SightEventDTO dto, Partner partner,
      Long sightEventHptId) {
    validatePublishedSightEventHasNormalTicket(dto, partner, sightEventHptId, false);
  }

  private void validateSightEventTextLengths(SightEventDTO dto) {
    if (dto == null) {
      return;
    }

    if (isLongerThan(dto.description, SIGHT_EVENT_DESCRIPTION_MAX_LENGTH)) {
      throw new BadRequestException("Opis oferty może mieć maksymalnie "
          + SIGHT_EVENT_DESCRIPTION_MAX_LENGTH + " znaków.");
    }

    if (dto.location != null
        && isLongerThan(dto.location.directions, SIGHT_EVENT_DIRECTIONS_MAX_LENGTH)) {
      throw new BadRequestException("Wskazówki dojazdu mogą mieć maksymalnie "
          + SIGHT_EVENT_DIRECTIONS_MAX_LENGTH + " znaków.");
    }
  }

  private boolean isLongerThan(String value, int maxLength) {
    return value != null && value.length() > maxLength;
  }

  private void validatePublishedSightEventHasNormalTicket(SightEventDTO dto, Partner partner,
      Long sightEventHptId, boolean currentlyPublished) {
    boolean resultingPublished =
        dto.published != null ? dto.published : currentlyPublished;
    if (!resultingPublished) {
      return;
    }

    if (sightEventHptId == null) {
      throw new BadRequestException(
          "Opublikowana oferta musi zawierac bilet typu Normalny.");
    }

    HelloTicket helloTicket = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
    List<TicketPoolDefinitionDTO> ticketPoolDefinitions =
        helloTicket.getTicketPoolDefinitions(partner.getHptToken(), List.of(sightEventHptId));
    boolean hasNormalTicket = ticketPoolDefinitions != null && ticketPoolDefinitions.stream()
        .filter(tpd -> tpd != null && !tpd.deleted)
        .filter(tpd -> tpd.ticketDefinitions != null)
        .flatMap(tpd -> tpd.ticketDefinitions.stream())
        .filter(td -> td != null && td.ticketType != null)
        .anyMatch(td -> "NORMALNY".equals(td.ticketType.code));
    if (!hasNormalTicket) {
      throw new BadRequestException(
          "Opublikowana oferta musi zawierac bilet typu Normalny.");
    }
  }

  private void handleAttachmentUpdate(SightEvent bo, SightEventDTO dto) {
    FileDescriptor newAttachment = null;
    Long newAttachmentId = null;
    if (dto.pdfAttachment != null && dto.pdfAttachment.id != null) {
      newAttachmentId = dto.pdfAttachment.id;
      newAttachment = fdService.get(newAttachmentId);
      if (newAttachment.getPartner() == null || !newAttachment.getPartner().getId().equals(getOwningPartner(bo).getId())) {
        throw new EJBAccessException();
      }
    }
    if (bo.getPdfAttachment() != null && !bo.getPdfAttachment().getId().equals(newAttachmentId)) {
      deleteAttachment(bo);
    }
    setAttachment(bo, newAttachment);
  }

  public SightEvent getForLoggedUser(Long id) {
    Partner partner = partnerService.getLoggedPartner();
    SightEvent sightEvent = getForPartner(id, partner);
    partnerUserAccessService.requireCanAccessSightEvent(sightEvent);
    return sightEvent;
  }

  public SightEvent getForPartner(Long sightEventId, Partner partner) {
    SightEvent sightEvent =
        em.createQuery("from SightEvent where id=:id and sight.partner.id=:partnerId", SightEvent.class)
            .setParameter("id", sightEventId)
            .setParameter("partnerId", partner.getId())
            .getSingleResult();
    sightEvent.fetchRelations();
    alignPartnerWithSight(sightEvent);
    return sightEvent;
  }

  public void deleteForLoggedUser(Long id) {
    getForLoggedUser(id).setActive(false);
    delete(id);
  }

  public void deleteForLoggedUser(Long id, LanguageVersion language) {
    translationService.deleteEntityTranslations(getForLoggedUser(id), language);
  }

  public void fetchTicketPoolDefinitions(Collection<SightEvent> bos,
      List<SightEventDTO> dtos, boolean showDeletedTPD, boolean replaceTdIdsWithAtnaIds) {
    fetchTicketPoolDefinitions(bos, dtos, showDeletedTPD, replaceTdIdsWithAtnaIds,
        HptTpdsDownloadConfigurator.Audience.MARKET);
  }

  public void fetchTicketPoolDefinitions(Collection<SightEvent> bos,
      List<SightEventDTO> dtos, boolean showDeletedTPD, boolean replaceTdIdsWithAtnaIds,
      HptTpdsDownloadConfigurator.Audience audience) {
    if (hasAnyHptCloudEvent(bos)) {

      var partnersToSightEventsWithHptId = groupDtosWithHptIdByPartner(bos, dtos);

      for (var partnerToSightEventsWithHptId : partnersToSightEventsWithHptId.entrySet()) {
        HptTpdsDownloadConfigurator configurator = new HptTpdsDownloadConfigurator();
        configurator.replaceTdIdsWithAtnaIds = replaceTdIdsWithAtnaIds;
        configurator.showDeletedAndOverdued = showDeletedTPD;
        configurator.audience = audience;
        configurator.subject = partnerToSightEventsWithHptId.getKey();
        configurator.sightEventIds = partnerToSightEventsWithHptId.getValue()
            .stream()
            .map(Pair::getKey)
            .collect(Collectors.toList());
        List<TicketPoolDefinitionDTO> tpds = downloadHptTpds(configurator);
        var tpdsGroupedBySightEventId = tpds.stream()
            .collect(groupingBy(pool -> pool.sightEventId));

        for (var hptIdToSightEvent : partnerToSightEventsWithHptId.getValue()) {
          hptIdToSightEvent.getRight().ticketPoolDefinitions =
              tpdsGroupedBySightEventId.get(hptIdToSightEvent.getLeft());
        }
      }

      for (var sightEventDto : dtos) {
        sightEventDto.minPrice = null;
        sightEventDto.minDiscountPrice = null;
        Optional<TicketDefinitionDTO> cheapestOpt = findCheapest(sightEventDto);
        cheapestOpt.ifPresent(cheapest -> {
          sightEventDto.minPrice = cheapest.originalPrice;
          sightEventDto.minDiscountPrice = getValidDiscountPrice(cheapest);
        });
      }
    }
  }

  Integer getValidDiscountPrice(TicketDefinitionDTO ticketDefinitionDto) {
    if (ticketDefinitionDto == null
        || ticketDefinitionDto.originalPrice == null
        || ticketDefinitionDto.discount == null) {
      return null;
    }

    Integer discountAmount = ticketDefinitionDto.discount.amount;
    if (discountAmount == null && ticketDefinitionDto.discount.type != null
        && ticketDefinitionDto.discount.value != null) {
      switch (ticketDefinitionDto.discount.type) {
        case FLAT:
          discountAmount = ticketDefinitionDto.discount.value;
          break;
        case PERCENT:
          discountAmount =
              (int) (1.0 * ticketDefinitionDto.originalPrice * ticketDefinitionDto.discount.value
                  / 100);
          break;
      }
    }

    if (discountAmount == null || discountAmount <= 0
        || discountAmount >= ticketDefinitionDto.originalPrice) {
      return null;
    }
    return ticketDefinitionDto.originalPrice - discountAmount;
  }

  private Optional<TicketDefinitionDTO> findCheapest(SightEventDTO sightEventDto) {
    if (sightEventDto.ticketPoolDefinitions != null) {
      return sightEventDto.ticketPoolDefinitions.stream()
          .filter(this::isVisibleOnPortal)
          .map(this::findPreferredPriceFromTicket)
          .flatMap(Optional::stream)
          .min(Comparator.comparing(td -> td.originalPrice));
    } else {
      return Optional.empty();
    }
  }

  private Optional<TicketDefinitionDTO> findPreferredPriceFromTicket(
      TicketPoolDefinitionDTO ticketPoolDefinitionDto) {
    if (ticketPoolDefinitionDto.ticketDefinitions == null) {
      return Optional.empty();
    }

    List<TicketDefinitionDTO> typedCandidates = ticketPoolDefinitionDto.ticketDefinitions.stream()
        .filter(td -> td.ticketType != null)
        .collect(Collectors.toList());

    Optional<TicketDefinitionDTO> normal = findCheapestByType(typedCandidates, "NORMALNY");
    if (normal.isPresent()) {
      return normal;
    }

    List<TicketDefinitionDTO> eligible = typedCandidates.stream()
        .filter(td -> Boolean.TRUE.equals(td.ticketType.eligibleForPriceFrom))
        .collect(Collectors.toList());
    if (!eligible.isEmpty()) {
      return eligible.stream()
          .min(Comparator.comparing(td -> td.originalPrice));
    }

    return typedCandidates.stream()
        .min(Comparator.comparing(td -> td.originalPrice));
  }

  private Optional<TicketDefinitionDTO> findCheapestByType(List<TicketDefinitionDTO> candidates,
      String code) {
    return candidates.stream()
        .filter(td -> code.equals(td.ticketType.code))
        .min(Comparator.comparing(td -> td.originalPrice));
  }

  private List<TicketPoolDefinitionDTO> downloadHptTpds(HptTpdsDownloadConfigurator configurator) {
    HelloTicket hpt = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
    List<TicketPoolDefinitionDTO> poolDefinitions =
        hpt.getTicketPoolDefinitions(configurator.subject.getHptToken(),
            configurator.sightEventIds);

    if (!configurator.showDeletedAndOverdued) {
      poolDefinitions = poolDefinitions
          .stream()
          .filter(tpd -> !tpd.deleted)
          .filter(tpd -> {
            return !tpd.isCyclic
                || tpd.frequencyData.endDate == null
                || tpd.frequencyData.endDate.after(new Date());
          }).collect(Collectors.toList());
    }

    poolDefinitions = filterTicketPoolDefinitionsByAudience(poolDefinitions,
        configurator.audience);

    if (configurator.replaceTdIdsWithAtnaIds) {
      poolDefinitions.forEach(pd -> {
        pd.ticketDefinitions.forEach(td -> td.id = td.atnaId);
      });
    }

    return poolDefinitions;
  }

  List<TicketPoolDefinitionDTO> filterTicketPoolDefinitionsByAudience(
      List<TicketPoolDefinitionDTO> poolDefinitions, HptTpdsDownloadConfigurator.Audience audience) {
    if (audience == HptTpdsDownloadConfigurator.Audience.PARTNER) {
      return poolDefinitions.stream()
          .filter(this::isVisibleForPartner)
          .collect(Collectors.toList());
    }
    if (audience == HptTpdsDownloadConfigurator.Audience.HELPDESK) {
      return poolDefinitions.stream()
          .filter(this::isVisibleForHelpdesk)
          .collect(Collectors.toList());
    }
    return poolDefinitions;
  }

  private boolean isVisibleForHelpdesk(TicketPoolDefinitionDTO poolDefinition) {
    return poolDefinition != null
        && !poolDefinition.deleted
        && (TicketPoolTypeDTO.PROMOTIONAL.equals(defaultPoolType(poolDefinition.poolType))
            || isVisibleForPartner(poolDefinition));
  }

  private boolean isVisibleForPartner(TicketPoolDefinitionDTO poolDefinition) {
    return poolDefinition != null
        && !TicketPoolTypeDTO.PROMOTIONAL.equals(defaultPoolType(poolDefinition.poolType))
        && defaultVisible(poolDefinition.visibleForPartner);
  }

  private TicketPoolTypeDTO defaultPoolType(TicketPoolTypeDTO poolType) {
    return poolType == null ? TicketPoolTypeDTO.STANDARD : poolType;
  }

  private boolean defaultVisible(Boolean visible) {
    return visible == null || visible;
  }

  private Map<Partner, List<Pair<Long, SightEventDTO>>> groupDtosWithHptIdByPartner(
      Collection<SightEvent> bos, List<SightEventDTO> dtos) {
    var groupedById = pairBosWithDtosByHptId(bos, dtos);
    var groupedByPartner = new HashMap<Partner, List<Pair<Long, SightEventDTO>>>();
    groupedById.forEach(triplet -> {
      Partner partner = getOwningPartner(triplet.second);
      if (!groupedByPartner.containsKey(partner)) {
        groupedByPartner.put(partner, new ArrayList<>());
      }
      groupedByPartner.get(partner).add(new ImmutablePair<>(triplet.first, triplet.third));
    });
    return groupedByPartner;
  }

  private boolean hasAnyHptCloudEvent(Collection<SightEvent> bos) {
    return bos.stream()
        .anyMatch(se -> se.getPortal().getType().equals(Portal.Type.HELLOTICKET_CLOUD_1));
  }

  private List<Triplet<Long, SightEvent, SightEventDTO>> pairBosWithDtosByHptId(
      Collection<SightEvent> bos,
      List<SightEventDTO> dtos) {
    var grouped = new ArrayList<Triplet<Long, SightEvent, SightEventDTO>>();
    bos.forEach(bo -> {
      for (var dto : dtos) {
        if (bo.getId().equals(dto.id)) {
          grouped.add(new Triplet<>(bo.getHptId(), bo, dto));
          break;
        }
      }
    });
    return grouped;
  }

  @SuppressWarnings("deprecation")
  public AvailableTicketNumberAssociationDTO checkAvailability(Long sightEventId, Date fromDate,
      Date toDate) {
    AvailableTicketNumberAssociationDTO associationDTO = new AvailableTicketNumberAssociationDTO();
    SightEvent sightEvent = get(sightEventId);
    if (sightEvent.isAccessible()) {
      HelloTicket hpt = new HelloTicket(getPortal("Hello Ticket Cloud").getUrl());
      associationDTO =
          hpt.checkAvailabilityOfTicketsForSightEvent(sightEvent, fromDate, toDate);
      associationDTO.ticketPoolDefinitions.forEach(tpd -> {
        tpd.ticketDefinitions.forEach(td -> td.id = td.atnaId);
        tpd.startDate.setYear(fromDate.getYear());
        tpd.startDate.setMonth(fromDate.getMonth());
        tpd.startDate.setDate(fromDate.getDate());
      });
      if (associationDTO.ticketPools != null) {
        associationDTO.ticketPools.forEach(tp -> {
          tp.ticketDefinitions.forEach(td -> td.id = td.atnaId);
        });
      }
    }

    return associationDTO;
  }

  public SightEvent setAttachment(SightEvent bo, FileDescriptor file) {
    bo.setPdfAttachment(file);
    if (file != null) {
      Portal hpt = getPortal("Hello Ticket Cloud");
      HelloTicket helloTicket = new HelloTicket(hpt.getUrl());
      helloTicket.addPdfToSightEvent(bo.getHptId(), DtoMapper.getFullDTO(bo.getPdfAttachment()),
          getOwningPartner(bo).getHptToken());
    }
    return bo;
  }

  public void deleteAttachment(SightEvent bo) {
    var pdf = bo.getPdfAttachment();
    if (pdf != null) {
      fdService.deleteFile(Paths.get(pdf.getPath()));
      Portal hpt = getPortal("Hello Ticket Cloud");
      HelloTicket helloTicket = new HelloTicket(hpt.getUrl());
      helloTicket.deletePdfFromSightEvent(bo, getOwningPartner(bo).getHptToken());
      bo.setPdfAttachment(null);
      return;
    }
    logger.log(Level.INFO, "SightEvent [id=" + bo.getId() + "] doesn't have a pdf file ");
  }

  public boolean isAvailable(SightEventDTO dto, Date fromDate, Date toDate) {
    List<TicketPoolDefinitionDTO> tpds = dto.ticketPoolDefinitions;
    if (tpds != null && !tpds.isEmpty()) {
      return !tpds.stream()
          .filter(tpd -> !tpd.deleted
              && isVisibleOnPortal(tpd)
              && isInDateRange(tpd, fromDate, toDate)
              && ticketAreAvailable(dto, tpd, fromDate, toDate))
          .collect(toList()).isEmpty();
    }
    return false;
  }

  private boolean ticketAreAvailable(SightEventDTO dto, TicketPoolDefinitionDTO tpd, Date fromDate,
      Date toDate) {
    AvailableTicketNumberAssociationDTO availableTickets = null;
    if (toDate == null) {
      if (tpd.isCyclic) {
        // if (tpd.frequencyData.endDate == null) {
        return true;
        // }
        // availableTickets = checkAvailability(dto.id, tpd.startDate, tpd.frequencyData.endDate);
      } else {
        availableTickets = checkAvailability(dto.id, tpd.startDate, null);
      }
    } else {
      availableTickets = checkAvailability(dto.id, fromDate, toDate);
    }

    Stream<TicketPoolDefinitionDTO> s1 =
        availableTickets.ticketPoolDefinitions.stream()
            .filter(this::isVisibleOnPortal)
            .filter(tp -> tp.ticketDefinitions.stream()
            .filter(td -> !td.availableTicketsNumber.equals(Integer.valueOf(0))).count() != 0);
    Stream<TicketPoolDTO> s2 =
        availableTickets.ticketPools.stream()
            .filter(this::isVisibleOnPortal)
            .filter(tp -> tp.ticketDefinitions.stream()
            .filter(td -> !td.availableTicketsNumber.equals(Integer.valueOf(0))).count() != 0);

    return s1.count() != 0l || s2.count() != 0l;

    // return availableTickets.ticketPoolDefinitions.stream()
    // .filter(f -> f.availableTicketsNumber != 0).count() != 0l
    // || availableTickets.ticketPools.stream().filter(f -> f.availableTicketsNumber != 0)
    // .count() != 0l;
  }

  private boolean isInDateRange(TicketPoolDefinitionDTO tpd, Date fromDate, Date toDate) {
    if (fromDate == null) {
      fromDate = new Date();
    }
    var tpdStartDate = tpd.startDate;
    if (tpd.isCyclic) {
      return (fromDate.before(tpdStartDate)
          || ((tpd.frequencyData.endDate != null ? fromDate.before(tpd.frequencyData.endDate)
              : true)))
          && (toDate != null ? toDate.after(tpdStartDate) : true);
    }

    return ((tpd.wholeDay && fromDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        .isEqual(tpdStartDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()))
        || fromDate.before(tpdStartDate)) && (toDate != null ? toDate.after(tpdStartDate) : true);
  }

  public boolean isVisibleOnPortal(TicketPoolDefinitionDTO poolDefinition) {
    return poolDefinition != null
        && !TicketPoolTypeDTO.PROMOTIONAL.equals(defaultPoolType(poolDefinition.poolType))
        && defaultVisible(poolDefinition.visibleOnPortal);
  }

  public boolean isVisibleOnPortal(TicketPoolDTO pool) {
    return pool != null
        && !TicketPoolTypeDTO.PROMOTIONAL.equals(defaultPoolType(pool.poolType))
        && defaultVisible(pool.visibleOnPortal);
  }

  public void stopSale(Long sightId, Long ticketPoolDefId, Date date) {
    var bo = getForLoggedUser(sightId);
    Partner partner = getLoggedPartner();
    HelloTicket ht = new HelloTicket(bo.getPortal().getUrl());
    TicketPoolDefinitionDTO poolDefinition =
        ht.getTicketPoolDefinition(partner.getHptToken(), ticketPoolDefId);
    if (!isVisibleForPartner(poolDefinition)) {
      throw new ResourceNotFoundException();
    }
    ht.stopSale(partner.getHptToken(), bo.getHptId(), ticketPoolDefId, date);
  }

  public SightEvent changeDefaultLanguageForLoggedUser(Long id, LanguageVersion language) {
    SightEvent bo = getForLoggedUser(id);
    return changeDefaultLanguage(bo, language);
  }

  public SightEvent changeDefaultLanguage(SightEvent bo, LanguageVersion language) {
    if (!translationService.isTranslated(bo, language)) {
      throw new ConflictingException(
          "Can not change the default language. Translation for language " + language.getLanuage()
              + "doesn't exists");
    }
    SightEvent translation = translationService.translateEntity(bo, language);
    bo.setDefaultLanguage(language);
    bo = BeanUtils.copyNotNullProperties(translation, bo);
    alignPartnerWithSight(bo);
    em.merge(bo);
    bo = get(bo.getId());
    if (bo.getPortal().getType() == Portal.Type.HELLOTICKET_CLOUD_1) {
      Partner partner = getOwningPartner(bo);
      Portal hpt = getPortal("Hello Ticket Cloud");
      HelloTicket helloTicket = new HelloTicket(hpt.getUrl());
      helloTicket.updateSightEvent(DtoMapper.getDTO(bo), partner.getHptToken());
    }
    sightService.recreateSearchIndex(bo.getSight());
    recreateSearchIndex(bo);
    return bo;
  }

  private SightEvent getOrThrow(Long id) throws ConflictingException {
    var bo = Optional.ofNullable(get(id))
        .orElseThrow(() -> new ConflictingException("Resource not found"));
    return bo;
  }

  public List<String> getCitiesForPublicEvents() {
    return getCitiesForPublicEvents(null);
  }

  public List<String> getCitiesForPublicEvents(String voivodeship) {
    String query = "select distinct location.city from SightEvent where active = true "
        + "and published = true and blocked = false and available = true and partner.blocked = false "
        + "and location.city is not null";
    boolean filterByVoivodeship = voivodeship != null && !voivodeship.isBlank();
    if (filterByVoivodeship) {
      query += " and lower(trim(location.voivodeship)) = lower(:voivodeship)";
    }
    query += " order by location.city asc";

    TypedQuery<String> citiesQuery = em.createQuery(query, String.class);
    if (filterByVoivodeship) {
      citiesQuery.setParameter("voivodeship", voivodeship.strip());
    }

    return citiesQuery.getResultStream()
        .map(String::strip)
        .filter(city -> !city.isBlank())
        .distinct()
        .sorted(Comparator.comparing(String::toLowerCase, polishComparator()))
        .collect(toList());
  }

  public List<String> getVoivodeshipsForPublicEvents() {
    return em.createQuery(
        "select distinct location.voivodeship from SightEvent where active = true "
            + "and published = true and blocked = false and available = true and partner.blocked = false "
            + "and location.voivodeship is not null",
        String.class).getResultStream()
        .map(this::canonicalVoivodeship)
        .filter(Objects::nonNull)
        .distinct()
        .sorted(Comparator.comparing(String::toLowerCase, polishComparator()))
        .collect(toList());
  }

  String canonicalVoivodeship(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return POLISH_VOIVODESHIPS.get(value.strip().toLowerCase(Locale.forLanguageTag("pl-PL")));
  }

  private Comparator<Object> polishComparator() {
    var collator = Collator.getInstance(new Locale("pl", "PL"));
    collator.setStrength(Collator.CANONICAL_DECOMPOSITION);
    return collator;
  }

  public void rebuildSearchIndices() {
    SightEventPagedCollectionConfig config = new SightEventPagedCollectionConfig();
    List<SightEvent> sightEvents = getQuery(config).getResultList();
    sightEvents.forEach(this::recreateSearchIndex);
  }

  private void recreateSearchIndex(SightEvent se) {
    {
      Set<String> words = se.getAvailableLanguageVersions().stream()
          .flatMap(
              lv -> translationService.getTranslations(se, lv).stream().map(Translation::getValue))
          .collect(toSet());
      se.recreateSearchIndex(words);
    }
  }

  public SightEvent getByHptId(Long hptId) {
    SightEvent se = em.createQuery("from SightEvent where hptId=:hptId", SightEvent.class)
        .setParameter("hptId", hptId).getSingleResult();
    se.fetchRelations();
    se.setFavourite(se.getUsers().contains(userService.getLoggedUser()));
    return se;
  }

  private Partner getOwningPartner(SightEvent sightEvent) {
    if (sightEvent.getSight() != null && sightEvent.getSight().getPartner() != null) {
      return sightEvent.getSight().getPartner();
    }
    return sightEvent.getPartner();
  }

  private void alignPartnerWithSight(SightEvent sightEvent) {
    Partner owningPartner = getOwningPartner(sightEvent);
    if (owningPartner != null && (sightEvent.getPartner() == null
        || !owningPartner.getId().equals(sightEvent.getPartner().getId()))) {
      sightEvent.setPartner(owningPartner);
    }
  }

  public void dereferenceImage(Long fileId) {
    em.createQuery("update SightEvent set mainImage = null where mainImage.id = :id")
        .setParameter("id", fileId)
        .executeUpdate();
    em.createNativeQuery("delete from sightevent_images where imagecollector_id = :id")
        .setParameter("id", fileId)
        .executeUpdate();
  }

  public void dereferenceAttachment(Long fileId) {
    em.createQuery("from SightEvent where pdfAttachment.id = :id", SightEvent.class)
        .setParameter("id", fileId)
        .getResultList()
        .forEach(this::deleteAttachment);
  }

  public void setAvailableByHptId(List<Long> ids, boolean available) {
    em.createQuery("update SightEvent set available = :available where hptId in (:ids)")
        .setParameter("ids", ids)
        .setParameter("available", available)
        .executeUpdate();
  }

}
