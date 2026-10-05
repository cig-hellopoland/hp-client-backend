package pl.hellopoland.service.api.helpdesk;

import pl.hellopoland.bo.Category;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.bo.SightEventCategory;
import pl.hellopoland.bo.SightEventTag;
import pl.hellopoland.bo.Tag;
import pl.hellopoland.bo.Sight;
import pl.hellopoland.config.SightEventPagedCollectionConfig;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.rest.dto.PagedCollection;
import pl.hellopoland.service.*;
import pl.hellopoland.service.vo.HptTpdsDownloadConfigurator;
import pl.hellopoland.util.DtoMapper;
import pl.hellopoland.util.PagedEntityCollection;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Stateless
public class SightEventServiceHelpdeskAPI {

  @Inject
  private SightEventService service;
  @Inject
  private TranslationService tService;
  @Inject
  private SightEventCategoryService secService;
  @Inject
  private SightEventTagService setService;
  @Inject
  private CategoryService catService;
  @Inject
  private TagService tagService;
  @Inject
  private SightService sightService;
  @Inject
  private HelpdeskAccessService accessService;
  @Inject
  private PromotedSightEventService promotedService;



  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager", "helpdesk_support"})
  public PagedCollection<SightEventDTO> list(SightEventPagedCollectionConfig config,
      LanguageVersion language) {
    config.onlyActive();
    return loadList(config, language);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager", "helpdesk_support"})
  public PagedCollection<SightEventDTO> listPromoted(LanguageVersion language) {
    var config = new SightEventPagedCollectionConfig();
    config.onlyPromoted();
    config.setOrderColumn("e.promotion, e.id");
    config.setOrderDirection("asc");
    return loadList(config, language);
  }

  private PagedCollection<SightEventDTO> loadList(SightEventPagedCollectionConfig config,
      LanguageVersion language) {
    config.setLanguage(language);
    accessService.applySightEventScope(config);
    PagedEntityCollection<SightEvent> bos = service.getList(config);
    Map<Long, Set<Category>> categoriesBySightEvent = catService.getFor(bos.items).stream()
        .collect(Collectors.groupingBy(
            relation -> relation.getSightEvent().getId(),
            Collectors.mapping(SightEventCategory::getCategory, Collectors.toSet())));
    Map<Long, Set<Tag>> tagsBySightEvent = tagService.getFor(bos.items).stream()
        .collect(Collectors.groupingBy(
            relation -> relation.getSightEvent().getId(),
            Collectors.mapping(SightEventTag::getTag, Collectors.toSet())));
    var dtos = bos.items.stream().map(bo -> {
      SightEventDTO dto = DtoMapper.getDTO(bo);
      dto.categories = categoriesBySightEvent.getOrDefault(bo.getId(), Set.of()).stream()
          .map(DtoMapper::getDTO).collect(Collectors.toSet());
      dto.tags = tagsBySightEvent.getOrDefault(bo.getId(), Set.of()).stream()
          .map(DtoMapper::getDTO).collect(Collectors.toSet());
      return dto;
    }).collect(Collectors.toList());
    return new PagedCollection<>(dtos, bos.config);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public void setPromotion(Long id, Integer promotion) {
    accessService.requireSightEventAccess(service.get(id));
    promotedService.move(id, promotion);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public void addPromotion(Long id) {
    accessService.requireSightEventAccess(service.get(id));
    promotedService.add(id);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public void reorderPromotions(List<Long> ids) {
    if (ids == null || ids.size() > PromotedSightEventService.MAX_OFFERS
        || ids.stream().anyMatch(id -> id == null)) {
      throw new ConflictingException("Nieprawidłowa lista promowanych ofert.");
    }
    ids.forEach(id -> accessService.requireSightEventAccess(service.get(id)));
    promotedService.reorder(ids);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public void removePromotion(Long id) {
    accessService.requireSightEventAccess(service.get(id));
    promotedService.remove(id);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public void delete(Long id) {
    accessService.requireSightEventAccess(service.get(id));
    service.delete(id);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO update(SightEventDTO dto, LanguageVersion language) {
    SightEvent bo = service.get(dto.id);
    accessService.requireSightEventAccess(bo);
    bo = service.update(bo, dto, language);
    return DtoMapper.getFullDTO(bo);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager", "helpdesk_support"})
  public SightEventDTO get(Long id, LanguageVersion language) {
    SightEvent bo = service.get(id);
    accessService.requireSightEventAccess(bo);
    bo = tService.translateEntity(bo, language);
    Set<Category> categories = bo.getCategories().stream().map(SightEventCategory::getCategory)
        .collect(Collectors.toSet());
    tService.translateEntities(categories, language);
    SightEventDTO dto = DtoMapper.getFullDTO(bo);
    service.fetchTicketPoolDefinitions(List.of(bo), List.of(dto), true, false,
        HptTpdsDownloadConfigurator.Audience.HELPDESK);
    return dto;
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO create(SightEventDTO dto, LanguageVersion language) {
    if (dto.sightId == null) {
      throw new ConflictingException("Sight is required.");
    }
    Sight sight = sightService.get(dto.sightId);
    accessService.requireSightAccess(sight);
    dto.defaultLanguage = language.getLanuage();
    dto.availableLanguageVersions = Set.of(language.getLanuage());
    dto.published = false;
    dto.blocked = false;
    return DtoMapper.getFullDTO(service.create(dto, sight.getPartner()));
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO createLanguageVesrion(SightEventDTO dto, LanguageVersion language) {
    accessService.requireSightEventAccess(service.get(dto.id));
    SightEvent bo = service.createLanguageVersion(dto, language);
    return DtoMapper.getFullDTO(bo);
  }


  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO changeDefaultLanguage(Long id, LanguageVersion language) {
    SightEvent bo = service.get(id);
    accessService.requireSightEventAccess(bo);
    if (!tService.isTranslated(bo, language)) {
      throw new ConflictingException(
          "Can not change the default language. Translation for language " + language.getLanuage()
              + "doesn't exists");
    }
    bo = service.changeDefaultLanguage(bo, language);
    bo.fetchRelations();
    return DtoMapper.getFullDTO(bo);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public void deleteLanguageVersion(Long id, LanguageVersion language) {
    SightEvent bo = service.get(id);
    accessService.requireSightEventAccess(bo);
    tService.deleteEntityTranslations(bo, language);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO addCategory(Long id, Long categoryId) {
    SightEvent se = service.get(id);
    accessService.requireSightEventAccess(se);
    Category cat = catService.get(categoryId);
    se = secService.addCategory(se, cat);
    return DtoMapper.getFullDTO(se);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO removeCategory(Long id, Long categoryId) {
    SightEvent se = service.get(id);
    accessService.requireSightEventAccess(se);
    Category cat = catService.get(categoryId);
    se = secService.removeCategory(se, cat);
    return DtoMapper.getFullDTO(se);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO addTag(Long id, Long tagId) {
    SightEvent se = service.get(id);
    accessService.requireSightEventAccess(se);
    Tag tag = tagService.get(tagId);
    se = setService.addTag(se, tag);
    return DtoMapper.getFullDTO(se);
  }

  @RolesAllowed({"root", "admin", "salesman", "helpdesk_partner_manager",
      "helpdesk_content_manager"})
  public SightEventDTO removeTag(Long id, Long tagId) {
    SightEvent se = service.get(id);
    accessService.requireSightEventAccess(se);
    Tag tag = tagService.get(tagId);
    se = setService.removeTag(se, tag);
    return DtoMapper.getFullDTO(se);
  }

}
