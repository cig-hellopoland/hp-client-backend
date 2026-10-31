package pl.hellopoland.service;

import pl.hellopoland.bo.*;
import pl.hellopoland.config.SightPagedCollectionConfig;
import pl.hellopoland.dto.SightDTO;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.exception.ExceptionFactory;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.exception.notfound.ResourceNotFoundException;
import pl.hellopoland.util.BeanUtils;
import pl.hellopoland.util.DtoMapper;
import pl.hellopoland.util.SeasonalClosure;
import pl.hellopoland.util.PagedEntityCollection;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

@LocalBean
@Stateless
public class SightService extends ServiceSuperclass {

  @Inject
  private ImageService imageService;

  @Inject
  private PartnerService partnerService;

  @Inject
  private SightEventService sightEventService;

  @Inject
  private ExceptionFactory exceptionFactory;

  @Inject
  private OpeningHoursService oHoursService;

  @Inject
  private AgreementService agreementService;

  @Inject
  private TranslationService translationService;

  @Inject
  private UserService userService;
  @Inject
  private PartnerUserAccessService partnerUserAccessService;

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


    public PagedEntityCollection<Sight> getList(SightPagedCollectionConfig config,
      LanguageVersion language) {
    if (config.isCurrentPartner()) {
      config.setPartner(partnerService.findByUserEmail(ctx.getCallerPrincipal().getName()).getId());
    }
    List<Sight> sights = partnerUserAccessService.filterAllowedSights(getQuery(config).getResultList());
    User loggedUser = userService.getLoggedUser();
    if (loggedUser != null) {
      List<Long> favourites = getAllFavouritesIdsForLoggedUser(loggedUser);
      sights.forEach(s -> s.setFavourite(favourites.contains(s.getId())));
    }
    if (language != null) {
      sights = translationService.translateEntities(sights, language);
      if (config.isFetchSightEvents()) {
        sights.forEach(s -> s.setSightEvents(translationService.translateEntities(s.getSightEvents(), language)));
      }
    }
    Collections.sort(sights, getNamesComparator(Sight::getName, new Locale("pl_PL")));

    return new PagedEntityCollection<>(sights, config);
  }

  public List<Long> getAllFavouritesIdsForLoggedUser(User user) {
    return em.createNativeQuery("select distinct sights_id from sight_users where users_id = :id", Long.class)
          .setParameter("id", user.getId())
          .getResultList();
  }

  public Sight create(SightDTO dto, Partner partner) {
    SeasonalClosure.validate(dto.seasonallyClosed, dto.seasonalClosureFrom, dto.seasonalClosureTo);
    Sight bo = new Sight();
    DtoMapper.copy(dto, bo);
    enrichLocation(bo.getLocation());
    if (bo.isBlocked()) {
      bo.setPublished(false);
    }
    if (partner == null) {
      partnerUserAccessService.requireCanManageSights();
      partner = partnerService.findByUserEmail(ctx.getCallerPrincipal().getName());
    }
    bo.setPartner(partner);
    em.persist(bo);

    imageService.handleImagesUpdate(bo, dto);

    ArrayList<OpeningHours> oHoursList = getOpeningHoursCollectionFromDTO(dto);
    if (oHoursList != null && !oHoursList.isEmpty()) {
      oHoursList.stream().forEach(oh -> {
        oh.setSight(bo);
        oHoursService.persist(oh);
      });
      bo.setOpeningHours(oHoursList);
    }
    if (Boolean.TRUE.equals(dto.generalAdmission)) {
      createGeneralAdmissionSightEvent(bo, partner);
    }
    handleAgreementsUpdate(bo, dto);
    final var bo2 = createLanguageVersion(DtoMapper.getDTO(bo), bo.getDefaultLanguage());
    recreateSearchIndex(bo2);
    return bo2;
  }

  public Sight createLanguageVersion(SightDTO dto, LanguageVersion language) {
    return translationService.createEntityLanguageVersion(get(dto.id), dto,
        language);
  }

  public Sight get(Long id) {
    Sight bo = em.createQuery("from Sight sight left join fetch sight.partner fp where sight.id=:sightId", Sight.class)
        .setParameter("sightId", id).getSingleResult();
    bo.fetchRelations();
    bo.getSightEvents().forEach(SightEvent::fetchRelations);
    bo.setFavourite(bo.getUsers().contains(userService.getLoggedUser()));
    return bo;
  }

  public List<Sight> getActiveForPartner() {
    Partner partner = partnerService.findByUserEmail(ctx.getCallerPrincipal().getName());
    return partnerUserAccessService.filterAllowedSights(em.createQuery(
        "from Sight sight left join fetch sight.mainImage fmi where sight.active=true and sight.partner=:partner order by sight.id desc",
        Sight.class).setParameter("partner", partner).getResultList());
  }

  public List<Sight> getActiveForPartner(LanguageVersion language) {
    var bos = getActiveForPartner();
    if (language == null) {
      return bos;
    }
    return translationService.translateEntities(bos, language);
  }

  public Sight updateForLoggedUser(SightDTO dto, LanguageVersion language) {
    partnerUserAccessService.requireCanManageSights();
    Sight bo = getActiveForLoggedPartner(dto.id);
    return update(bo, dto, language);
  }

  public Sight update(Sight bo, SightDTO dto, LanguageVersion language) {
    SeasonalClosure.validate(dto.seasonallyClosed, dto.seasonalClosureFrom, dto.seasonalClosureTo);
    if (!translationService.isTranslated(bo, language)) {
      // throw new ConflictingException(
      // "Translation for language " + language.getLanuage() + " doesn't exists");
      createLanguageVersion(dto, language);
    }
    if (bo.getDefaultLanguage().equals(language)) {
      DtoMapper.copy(dto, bo);
      enrichLocation(bo.getLocation());
      if (bo.isBlocked()) {
        bo.setPublished(false);
      }
      oHoursService.remove(bo.getOpeningHours());
      ArrayList<OpeningHours> oHoursList = getOpeningHoursCollectionFromDTO(dto);
      if (oHoursList != null && !oHoursList.isEmpty()) {
        oHoursList.stream().forEach(oh -> {
          oh.setSight(bo);
          oHoursService.persist(oh);
        });
      }
      bo.setOpeningHours(null);
      bo.setOpeningHours(oHoursList);
      handleAgreementsUpdate(bo, dto);
      imageService.handleImagesUpdate(bo, dto);
    }
    final var bo2 = translationService.updateEntityLanguageVersion(get(dto.id), dto,
        language);
    recreateSearchIndex(em.merge(bo));
    return bo2;
  }

  private void handleAgreementsUpdate(Sight bo, SightDTO dto) {
    var agreements = dto.agreements;
    if (agreements != null && !agreements.isEmpty()) {
      var agreementBos = Set.copyOf(agreementService.getForPartner(bo.getPartner(),
          agreements.stream().map(agrDto -> agrDto.id).collect(Collectors.toSet())));
      bo.setAgreements(agreementBos);
      var sightEventBos = bo.getSightEvents();
      if (sightEventBos != null && !sightEventBos.isEmpty()) {
        sightEventBos.forEach(se -> se.setAgreements(agreementBos));
      }
    }
  }

  private boolean hasActiveSightEvents(List<SightEvent> sightEvents) {
    for (SightEvent sightEvent : sightEvents) {
      if (sightEvent.isActive()) {
        return true;
      }
    }
    return false;
  }

  private void createGeneralAdmissionSightEvent(Sight sight, Partner partner) {
    SightEventDTO sed = DtoMapper.getGAEventDTO(sight);
    sightEventService.create(sed, partner);
  }

  public Sight getActiveForLoggedPartner(Long id) {
    Sight sight = em
        .createQuery("from Sight where id=:id and active=true and partner=:partner", Sight.class)
        .setParameter("id", id).setParameter("partner", getLoggedPartner()).getSingleResult();
    sight.fetchRelations();
    sight.getSightEvents().forEach(SightEvent::fetchRelations);
    partnerUserAccessService.requireCanAccessSight(sight);

    return sight;
  }

  public Sight getActiveForLoggedUser(Long id, LanguageVersion language) {
    var bo = getActiveForLoggedPartner(id);
    if (language == null) {
      return bo;
    }
    return translationService.translateEntity(bo, language);
  }

  private ArrayList<OpeningHours> getOpeningHoursCollectionFromDTO(SightDTO dto) {
    return Optional.ofNullable(dto.openingHours)
        .map(l -> l.stream().map(oh -> DtoMapper.copy(oh, new OpeningHours()))
            .collect(Collectors.toCollection(ArrayList::new)))
        .orElse(null);
  }

  public void deleteForLoggedUser(Long id) {
    partnerUserAccessService.requireCanManageSights();
    Sight bo = getActiveForLoggedPartner(id);
    delete(bo);
  }

  public void delete(Sight bo) {
    if (hasActiveSightEvents(bo.getSightEvents())) {
      throw exceptionFactory.sightHasAssignedSightEventsException();
    } else {
      bo.setActive(false);
    }
  }

  public void deleteForLoggedUser(Long id, LanguageVersion language) {
    partnerUserAccessService.requireCanManageSights();
    translationService.deleteEntityTranslations(getActiveForLoggedPartner(id), language);
  }

  private Sight getForLoggedPartner(Long sightId) {
    var partner = partnerService.findByUserEmail(ctx.getCallerPrincipal().getName());
    Sight sight = em.createQuery("from Sight where partner = :partner and id = :id", Sight.class)
        .setParameter("partner", partner).setParameter("id", sightId).getResultStream().findFirst()
        .orElseThrow(ResourceNotFoundException::new);
    sight.fetchRelations();
    sight.getSightEvents().forEach(SightEvent::fetchRelations);
    partnerUserAccessService.requireCanAccessSight(sight);
    return sight;
  }

  public Sight changeDefaultLanguageForLoggedUser(Long id, LanguageVersion language) {
    partnerUserAccessService.requireCanManageSights();
    Sight bo = getForLoggedPartner(id);
    return changeDefaultLanguage(bo, language);
  }

  public Sight changeDefaultLanguage(Sight bo, LanguageVersion language) {
    if (!translationService.isTranslated(bo, language)) {
      throw new ConflictingException(
          "Can not change the default language. Translation for language " + language.getLanuage()
              + "doesn't exists");
    }
    Sight translation = translationService.translateEntity(bo, language);
    bo.setDefaultLanguage(language);
    bo = BeanUtils.copyNotNullProperties(translation, bo);
    em.merge(bo);
    bo = get(bo.getId());
    recreateSearchIndex(bo);
    return bo;
  }

  public void rebuildSearchIndices() {
    SightPagedCollectionConfig config = new SightPagedCollectionConfig();
    List<Sight> sightEvents = getQuery(config).getResultList();
    sightEvents.forEach(this::recreateSearchIndex);
  }

  public void recreateSearchIndex(Sight s) {
    Set<String> words = s.getAvailableLanguageVersions().stream()
        .flatMap(
            lv -> translationService.getTranslations(s, lv).stream().map(Translation::getValue))
        .collect(Collectors.toSet());
    s.recreateSearchIndex(words);
  }

  public void dereferenceImage(Long fileId) {
    em.createQuery("update Sight set mainImage = null where mainImage.id = :id")
        .setParameter("id", fileId)
        .executeUpdate();
    em.createNativeQuery("delete from sight_images where imagecollector_id = :id")
        .setParameter("id", fileId)
        .executeUpdate();
  }
}
