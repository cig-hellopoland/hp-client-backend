package pl.hellopoland.util;

import pl.hellopoland.bo.*;
import pl.hellopoland.dto.*;
import pl.hellopoland.enums.LanguageVersion;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class DtoMapper {

  private final static Logger logger = System.getLogger(DtoMapper.class.getName());

  public static void copy(SightDTO source, Sight target) {
    target.setName(source.name);
    target.setLead(source.lead);
    target.setDescription(source.description);
    target.setEmail(source.email);
    target.setPhone(source.phone);
    target.setGooglePlaceId(source.googlePlaceId);
    target.setScore(source.score);
    if (source.defaultLanguage != null) {
      target
          .setDefaultLanguage(LanguageVersion.getForCreateAndUpdateEntity(source.defaultLanguage));
    }
    if (source.availableLanguageVersions != null && !source.availableLanguageVersions.isEmpty()) {
      target.setAvailableLanguageVersions(source.availableLanguageVersions.stream()
          .map(ver -> LanguageVersion.getForCreateAndUpdateEntity(ver))
          .collect(Collectors.toSet()));
    }
    if (source.blocked != null) {
      target.setBlocked(source.blocked);
    }
    // Older clients omit this flag: do not erase the stored closure on their updates.
    if (source.seasonallyClosed != null) {
      SeasonalClosure.validate(source.seasonallyClosed, source.seasonalClosureFrom, source.seasonalClosureTo);
      target.setSeasonallyClosed(source.seasonallyClosed);
      target.setSeasonalClosureFrom(SeasonalClosure.parse(source.seasonalClosureFrom));
      target.setSeasonalClosureTo(SeasonalClosure.parse(source.seasonalClosureTo));
    }
    if (source.published != null) {
      target.setPublished(source.published);
    }
      target.setAnimalsAllowed(Boolean.TRUE.equals(source.animalsAllowed));
      target.setCarParkAvailable(Boolean.TRUE.equals(source.carParkAvailable));
      target.setFoodAndDrinkAvailable(Boolean.TRUE.equals(source.foodAndDrinkAvailable));

      target.setDisabledAccessHearing(Boolean.TRUE.equals(source.disabledAccessHearing));
      target.setDisabledAccessMovement(Boolean.TRUE.equals(source.disabledAccessMovement));
      target.setDisabledAccessVision(Boolean.TRUE.equals(source.disabledAccessVision));


    copyLocation(source.location, target);

  }

  public static OpeningHours copy(OpeningHoursDTO source, OpeningHours target) {
    target.setCloseTime(source.closeTime);
    target.setDay(source.day);
    target.setOpenTime(source.openTime);
    return target;
  }

// --Commented out by Inspection START (2021-02-15 10:18):
//  public static Agreement copy(AgreementDTO source, Agreement target) {
//    if (source.linkUrl != null && !source.linkUrl.isEmpty()) {
//      target.setLinkUrl(source.linkUrl);
//    }
//    if (source.text != null && !source.text.isEmpty()) {
//      target.setText(source.text);
//    }
//    if (source.obligatory != null) {
//      target.setObligatory(source.obligatory);
//    }
//    return target;
//  }
// --Commented out by Inspection STOP (2021-02-15 10:18)

  public static SightDTO getDTO(Sight bo) {
    SightDTO dto = new SightDTO();
    dto.id = bo.getId();
    dto.name = bo.getName();
    dto.lead = bo.getLead();
    dto.description = bo.getDescription();
    dto.mainImage = bo.getMainImage() != null ? getDTO(bo.getMainImage()) : null;
    dto.location = ofNullable(bo.getLocation()).map(DtoMapper::getDTO).orElse(null);
    dto.email = bo.getEmail();
    dto.phone = bo.getPhone();
    dto.googlePlaceId = bo.getGooglePlaceId();
    dto.score = bo.getScore();
    dto.blocked = bo.isBlocked();
    dto.published = bo.isPublished();
    dto.seasonallyClosed = bo.isSeasonallyClosed();
    dto.seasonalClosureFrom = SeasonalClosure.format(bo.getSeasonalClosureFrom());
    dto.seasonalClosureTo = SeasonalClosure.format(bo.getSeasonalClosureTo());
    dto.seasonalClosureActive = SeasonalClosure.isActive(bo.isSeasonallyClosed(),
        bo.getSeasonalClosureFrom(), bo.getSeasonalClosureTo());
    dto.animalsAllowed = bo.isAnimalsAllowed();
    dto.carParkAvailable = bo.isCarParkAvailable();
    dto.foodAndDrinkAvailable = bo.isFoodAndDrinkAvailable();

    dto.disabledAccessHearing = bo.isDisabledAccessHearing();
    dto.disabledAccessMovement = bo.isDisabledAccessMovement();
    dto.disabledAccessVision = bo.isDisabledAccessVision();

    dto.defaultLanguage = bo.getDefaultLanguage().getLanuage();
    dto.language = bo.getCurrentLanguage() == null ? dto.defaultLanguage
        : bo.getCurrentLanguage().getLanuage();
    dto.partnerId = bo.getPartner().getId();
    dto.partnerName = bo.getPartner().getName();
    dto.favourite = bo.isFavourite();
    return dto;
  }

  public static SightDTO getFullDTO(Sight bo) {
    SightDTO dto = getDTOWithTags(bo);
    dto.availableLanguageVersions = bo.getAvailableLanguageVersions().stream()
        .map(lang -> lang.getLanuage()).collect(Collectors.toSet());
    dto.images = new ArrayList<>();
    if (dto.mainImage != null) {
      dto.images.add(dto.mainImage);
    }
    if (bo.getImages() != null) {
      dto.images.addAll(bo.getImages().stream().map(DtoMapper::getDTO).collect(toList()));
    }
    if (bo.getSightEvents() != null) {
      dto.sightEvents = bo.getSightEvents().stream().map(DtoMapper::getFullDTO).collect(toList());
    }
    if (bo.getOpeningHours() != null && !bo.getOpeningHours().isEmpty()) {
      dto.openingHours = bo.getOpeningHours().stream().map(DtoMapper::getDTO).collect(toList());
    }
    if (bo.getAgreements() != null && !bo.getAgreements().isEmpty()) {
      dto.agreements = bo.getAgreements().stream().map(DtoMapper::getDTO).collect(toList());
    }
    if (bo.getCategories() != null && !bo.getCategories().isEmpty()) {
      dto.categories = bo.getCategories().stream().map(DtoMapper::getDTO).collect(toSet());
    }
    return dto;
  }

  public static SightDTO getDTOWithTags(Sight bo) {
    SightDTO dto = getDTO(bo);
    if (bo.getTags() != null && !bo.getTags().isEmpty()) {
      dto.tags = bo.getTags().stream().map(DtoMapper::getDTO).collect(toSet());
    }
    return dto;
  }

  public static SightEventDTO getDTO(SightEvent bo) {
    SightEventDTO dto = new SightEventDTO();
    Partner partner = bo.getSight() != null && bo.getSight().getPartner() != null
        ? bo.getSight().getPartner()
        : bo.getPartner();
    dto.id = bo.getId();
    dto.hptId = bo.getHptId();
    dto.name = bo.getName();
    dto.lead = bo.getLead();
    dto.description = bo.getDescription();
    dto.mainImage = bo.getMainImage() != null ? getDTO(bo.getMainImage()) : null;
    dto.email = bo.getEmail();
    dto.phone = bo.getPhone();
    dto.duration = bo.getDuration();
    dto.location = ofNullable(bo.getLocation()).map(DtoMapper::getDTO).orElse(null);
    dto.generalAdmission = bo.getGeneralAdmission();
    dto.score = bo.getScore();
    dto.sightId = bo.getSight() != null ? bo.getSight().getId() : null;
    dto.blocked = bo.isBlocked();
    dto.published = bo.isPublished();
    dto.seasonallyClosed = bo.isSeasonallyClosed();
    dto.seasonalClosureFrom = SeasonalClosure.format(bo.getSeasonalClosureFrom());
    dto.seasonalClosureTo = SeasonalClosure.format(bo.getSeasonalClosureTo());
    dto.seasonalClosureActive = SeasonalClosure.isActive(bo.isSeasonallyClosed(),
        bo.getSeasonalClosureFrom(), bo.getSeasonalClosureTo());
    dto.partnerAffiliateCode = partner.getAffiliateCode();
    dto.defaultLanguage = bo.getDefaultLanguage().getLanuage();
    dto.promotion = bo.getPromotion();
    dto.promoted = dto.promotion != null;
    dto.sightId = bo.getSight().getId();
    dto.sightName = bo.getSight().getName();
    dto.partnerId = partner.getId();
    dto.partnerName = partner.getName();
    dto.minPrice = bo.getMinPrice();
    dto.minDiscountPrice = bo.getMinDiscountPrice();
    dto.language = bo.getCurrentLanguage() == null ? dto.defaultLanguage
        : bo.getCurrentLanguage().getLanuage();
    dto.favourite = bo.isFavourite();
    return dto;
  }

  public static SightEventDTO getFullDTO(SightEvent bo) {
    SightEventDTO dto = getDTOWithTags(bo);
    dto.availableLanguageVersions = bo.getAvailableLanguageVersions().stream()
        .map(lang -> lang.getLanuage()).collect(Collectors.toSet());
    dto.images = new ArrayList<>();
    if (dto.mainImage != null) {
      dto.images.add(dto.mainImage);
    }
    if (bo.getImages() != null) {
      dto.images.addAll(bo.getImages().stream().map(DtoMapper::getDTO).collect(toList()));
    }
    if (bo.getOpeningHours() != null && !bo.getOpeningHours().isEmpty()) {
      dto.openingHours = bo.getOpeningHours().stream().map(DtoMapper::getDTO)
          .collect(toList());
    }
    if (bo.getAgreements() != null && !bo.getAgreements().isEmpty()) {
      dto.agreements = bo.getAgreements().stream().map(DtoMapper::getDTO).collect(toList());
    }
    if (bo.getCategories() != null && !bo.getCategories().isEmpty()) {
      dto.categories = bo.getCategories().stream().map(SightEventCategory::getCategory)
          .map(DtoMapper::getDTO).collect(toSet());
    }
    dto.pdfAttachment = bo.getPdfAttachment() != null ? getFullDTO(bo.getPdfAttachment()) : null;
    return dto;
  }

  public static SightEventDTO getDTOWithTags(SightEvent bo) {
    SightEventDTO dto = getDTO(bo);
    if (bo.getTags() != null && !bo.getTags().isEmpty()) {
      dto.tags = bo.getTags().stream().map(SightEventTag::getTag)
          .map(DtoMapper::getDTO).collect(toSet());
    }
    return dto;
  }

  public static FileDescriptorDTO getDTO(FileDescriptor bo) {
    var dto = new FileDescriptorDTO();
    dto.id = bo.getId();
    dto.created = bo.getCreated();
    dto.type = bo.getMimeType().toString();
    dto.name = bo.getFileName();
    dto.originalName = bo.getOriginalName();
    return dto;

  }

  public static FileDescriptorDTO getFullDTO(FileDescriptor bo) {
    var dto = getDTO(bo);
    dto.path = bo.getPath();
    dto.downloadUrl = bo.getDownloadUrl();
    return dto;
  }

  private static OpeningHoursDTO getDTO(OpeningHours bo) {
    var dto = new OpeningHoursDTO();
    dto.sight = ofNullable(bo.getSight()).map(DtoMapper::getDTO).orElse(null);
    dto.sightEvent = ofNullable(bo.getSightEvent()).map(DtoMapper::getDTO).orElse(null);
    dto.day = bo.getDay();
    dto.openTime = bo.getOpenTime();
    dto.closeTime = bo.getCloseTime();
    return dto;
  }

  public static LocationDTO getDTO(Location bo) {
    LocationDTO dto = new LocationDTO();
    dto.latitude = bo.getLatitude();
    dto.longitude = bo.getLongitude();
    dto.street = bo.getStreet();
    dto.zipCode = bo.getZipCode();
    dto.city = bo.getCity();
    dto.country = bo.getCountry();
    dto.directions = bo.getDirections();
    dto.voivodeship = bo.getVoivodeship();
    dto.county = bo.getCounty();
    dto.commune = bo.getCommune();
    return dto;
  }

  private static LocationDTO getDTO(Address address) {
    if (address != null) {
      LocationDTO dto = new LocationDTO();
      dto.street = address.getStreet();
      dto.zipCode = address.getPostCode();
      dto.city = address.getCity();
      dto.country = address.getCountry();
      dto.directions = address.getDirections();
      dto.commune = address.getCommune();
      dto.county = address.getCounty();
      dto.voivodeship = address.getVoivodeship();

      return dto;
    }
    return null;
  }

  public static ImageDTO getDTO(ImageCollector bo) {
    if (bo == null) {
      return null;
    }
    ImageDTO dto = new ImageDTO();
    dto.id = bo.getId();
    if (bo.getOrginal() != null) {
      dto.original = bo.getOrginal().getDownloadUrl();
    }
    if (bo.getOrginalWebp() != null) {
      dto.originalWebp = bo.getOrginalWebp().getDownloadUrl();
    }
    if (bo.getFhd() != null) {
      dto.fhd = bo.getFhd().getDownloadUrl();
    }
    if (bo.getFhdWebp() != null) {
      dto.fhdWebp = bo.getFhdWebp().getDownloadUrl();
    }
    if (bo.getFourK() != null) {
      dto.fourK = bo.getFourK().getDownloadUrl();
    }
    if (bo.getFourKWebp() != null) {
      dto.fourKWebp = bo.getFourKWebp().getDownloadUrl();
    }
    if (bo.getHd() != null) {
      dto.hd = bo.getHd().getDownloadUrl();
    }
    if (bo.getHdWebp() != null) {
      dto.hdWebp = bo.getHdWebp().getDownloadUrl();
    }
    if (bo.getQvga() != null) {
      dto.qvg = bo.getQvga().getDownloadUrl();
    }
    if (bo.getQvgaWebp() != null) {
      dto.qvgWebp = bo.getQvgaWebp().getDownloadUrl();
    }
    if (bo.getSxga() != null) {
      dto.sxga = bo.getSxga().getDownloadUrl();
    }
    if (bo.getSxgaWebp() != null) {
      dto.sxgaWebp = bo.getSxgaWebp().getDownloadUrl();
    }
    if (bo.getVga() != null) {
      dto.vga = bo.getVga().getDownloadUrl();
    }
    if (bo.getVgaWebp() != null) {
      dto.vgaWebp = bo.getVgaWebp().getDownloadUrl();
    }
    if (bo.getXga() != null) {
      dto.xga = bo.getXga().getDownloadUrl();
    }
    if (bo.getXgaWebp() != null) {
      dto.xgaWebp = bo.getXgaWebp().getDownloadUrl();
    }
    return dto;
  }

  private static void copyLocation(LocationDTO source, Located target) {
    Location location = target.getLocation();
    if (source != null) {
      if (location == null) {
        location = new Location();
      }
      location.setLatitude(source.latitude);
      location.setLongitude(source.longitude);
      location.setStreet(source.street);
      location.setZipCode(source.zipCode);
      location.setCity(source.city);
      location.setCountry(source.country);
      location.setDirections(source.directions);
      location.setVoivodeship(source.voivodeship);
      location.setCounty(source.county);
      location.setCommune(source.commune);
      target.setLocation(location);
    } else {
      target.setLocation(null);
    }
  }

  public static SightEventDTO getGAEventDTO(Sight bo) {
    SightEventDTO dto = new SightEventDTO();
    dto.sightId = bo.getId();
    dto.name = bo.getName();
    dto.description = bo.getDescription();
    dto.mainImage = getDTO(bo.getMainImage());
    dto.email = bo.getEmail();
    dto.phone = bo.getPhone();
    dto.lead = bo.getLead();
    dto.location = ofNullable(bo.getLocation()).map(DtoMapper::getDTO).orElse(null);
    dto.generalAdmission = true;
    return dto;
  }

  public static void copy(SightEventDTO source, SightEvent target) {
    target.setName(source.name);
    target.setLead(source.lead);
    target.setDescription(source.description);
    target.setEmail(source.email);
    target.setPhone(source.phone);
    target.setGeneralAdmission(source.generalAdmission);
    target.setHptId(source.id);
    target.setScore(source.score);
    if (source.defaultLanguage != null) {
      target
          .setDefaultLanguage(LanguageVersion.getForCreateAndUpdateEntity(source.defaultLanguage));
    }
    if (source.availableLanguageVersions != null && !source.availableLanguageVersions.isEmpty()) {
      target.setAvailableLanguageVersions(source.availableLanguageVersions.stream()
          .map(ver -> LanguageVersion.getForCreateAndUpdateEntity(ver))
          .collect(Collectors.toSet()));
    }
    if (source.blocked != null) {
      target.setBlocked(source.blocked);
    }
    // Older clients omit this flag: do not erase the stored closure on their updates.
    if (source.seasonallyClosed != null) {
      SeasonalClosure.validate(source.seasonallyClosed, source.seasonalClosureFrom, source.seasonalClosureTo);
      target.setSeasonallyClosed(source.seasonallyClosed);
      target.setSeasonalClosureFrom(SeasonalClosure.parse(source.seasonalClosureFrom));
      target.setSeasonalClosureTo(SeasonalClosure.parse(source.seasonalClosureTo));
    }
    if (source.published != null) {
      target.setPublished(source.published);
    }
    copyLocation(source.location, target);
  }

  public static AgreementDTO getDTO(Agreement bo) {
    var dto = new AgreementDTO();
    dto.id = bo.getId();
    dto.linkUrl = bo.getLinkUrl();
    dto.obligatory = bo.isObligatory();
    dto.text = bo.getText();
    return dto;
  }

  public static RoleDTO getDTO(UserRole bo) {
    return RoleDTO.valueOf(bo.getRole().name());
  }

  public static UserDTO getDTO(User bo) {
    var dto = new UserDTO();
    dto.id = bo.getId();
    if (bo.getDetails() != null) {
      dto.name = Stream.of(bo.getDetails().getFirstName(), bo.getDetails().getLastName())
          .filter(Objects::nonNull)
          .filter(name -> !name.isBlank())
          .collect(Collectors.joining(" "));
    }
    dto.email = bo.getEmail();
    dto.picture = bo.getPicture();
    dto.blocked = bo.isBlocked();
    dto.roles = Optional.ofNullable(bo.getRoles()).orElse(Collections.emptyList()).stream()
        .map(DtoMapper::getDTO).collect(Collectors.toSet());
    dto.allowedSightIds = Optional.ofNullable(bo.getAllowedPartnerSights())
        .orElse(Collections.emptySet()).stream()
        .map(Sight::getId)
        .collect(Collectors.toList());
    dto.allowedHelpdeskPartnerIds = Optional.ofNullable(bo.getAllowedHelpdeskPartners())
        .orElse(Collections.emptySet()).stream()
        .map(Partner::getId)
        .collect(Collectors.toList());
    dto.allowedHelpdeskSightIds = Optional.ofNullable(bo.getAllowedHelpdeskSights())
        .orElse(Collections.emptySet()).stream()
        .map(Sight::getId)
        .collect(Collectors.toList());
    return dto;
  }

  public static PartnerDTO getDTO(Partner bo) {
    var dto = new PartnerDTO();
    dto.id = bo.getId();
    dto.name = bo.getName();
    dto.commission = bo.getCommission();
    dto.email = bo.getEmail();
    dto.affiliateCode = bo.getAffiliateCode();
    dto.bankAccount = bo.getBankAccount();
    dto.invoiceEmail = bo.getInvoiceEmail();
    dto.krs = bo.getKrs();
    dto.taxNumber = bo.getTaxNumber();
    dto.socialNumber = String.valueOf(bo.getSocialNumber());
    dto.phone = bo.getPhone();
    dto.regon = bo.getRegon();
    dto.servicesDescription = bo.getServicesDescription();
    dto.shopUrl = bo.getShopUrl();
    dto.description = bo.getDescription();
    dto.defaultLanguage = bo.getDefaultLanguage().getLanuage();
    dto.businessType = bo.getBusinessType() != null ? bo.getBusinessType().getValue() : null;
    dto.language = bo.getCurrentLanguage() == null ? dto.defaultLanguage
        : bo.getCurrentLanguage().getLanuage();
    dto.blocked = bo.isBlocked();
    dto.mainImage = DtoMapper.getDTO(bo.getMainImage());
    dto.location = getDTO(bo.getAddress());
    return dto;
  }

  public static MarketPartnerDTO getMarketDTO(Partner bo) {
    var dto = new MarketPartnerDTO();
    dto.id = bo.getId();
    dto.name = bo.getName();
    dto.mainImage = getDTO(bo.getMainImage());
    dto.defaultLanguage = bo.getDefaultLanguage().getLanuage();
    dto.language = bo.getCurrentLanguage() == null ? dto.defaultLanguage
        : bo.getCurrentLanguage().getLanuage();
    return dto;
  }

  public static MarketPartnerDTO getFullMarketPartnerDTO(Partner bo) {
    var dto = getMarketDTO(bo);
    dto.location = getDTO(bo.getAddress());
    dto.description = bo.getDescription();
    if (bo.getCategories() != null) {
      dto.categories =
          bo.getCategories().stream().map(DtoMapper::getDTO).collect(Collectors.toList());
    }
    if (bo.getSight() != null) {
      dto.sights = bo.getSight().stream().map(DtoMapper::getDTOWithTags)
          .collect(Collectors.toList());
    }
    if (bo.getSightEvents() != null) {
      dto.sightEvents =
          bo.getSightEvents().stream().map(DtoMapper::getDTOWithTags)
              .collect(Collectors.toList());
    }
    dto.availableLanguageVersions = bo.getAvailableLanguageVersions().stream()
        .map(lang -> lang.getLanuage()).collect(Collectors.toSet());
    dto.cities = bo.getCities();
    dto.email = bo.getEmail();
    dto.phone = bo.getPhone();
    return dto;
  }

  public static PartnerDTO getFullDTO(Partner bo) {
    var dto = getDTO(bo);
    dto.users = Optional.ofNullable(bo.getUsers()).orElse(Collections.emptyList()).stream()
        .map(DtoMapper::getDTO).collect(Collectors.toList());
    dto.location = getDTO(bo.getAddress());
    dto.correspondenceAddress = getDTO(bo.getCorrespondenceAddress());
    dto.technicalContact =
        bo.getTechnicalContact() != null ? getDTO(bo.getTechnicalContact()) : null;
    dto.contactPerson = bo.getContactPerson() != null ? getDTO(bo.getContactPerson()) : null;
    dto.representatives =
        Optional.ofNullable(bo.getRepresentatives()).orElse(Collections.emptyList()).stream()
            .map(DtoMapper::getDTO).collect(Collectors.toList());
    dto.availableLanguageVersions = bo.getAvailableLanguageVersions().stream()
        .map(lang -> lang.getLanuage()).collect(Collectors.toSet());
    return dto;
  }

  private static ContactPersonDTO getDTO(ContactPerson bo) {
    var dto = new ContactPersonDTO();
    dto.name = bo.getName();
    dto.email = bo.getEmail();
    dto.phone = bo.getPhone();
    return dto;
  }

  private static PartnerRepresentativeDTO getDTO(PartnerRepresentative bo) {
    var dto = new PartnerRepresentativeDTO();
    dto.name = bo.getName();
    dto.socialNumber = String.valueOf(bo.getSocialNumber());
    return dto;
  }

  public static CategoryDTO getDTO(Category bo) {
    var dto = new CategoryDTO();
    dto.id = bo.getId();
    dto.label = bo.getLabel();
    dto.iconUrl = bo.getIconUrl();
    dto.backgroundUrl = bo.getBackgroundUrl();
    dto.displayOrder = bo.getDisplayOrder();
    dto.restricted = bo.isRestricted();
    dto.assignedItemsCount = bo.getAssignedItemsCount();
    dto.recommended = bo.isRecommended();
    dto.defaultLanguage = bo.getDefaultLanguage().getLanuage();
    dto.language = bo.getCurrentLanguage() == null ? dto.defaultLanguage
        : bo.getCurrentLanguage().getLanuage();
    return dto;
  }

  public static CategoryDTO getFullDTO(Category bo) {
    var dto = getDTO(bo);
    dto.availableLanguageVersions = bo.getAvailableLanguageVersions().stream()
        .map(lv -> lv.getLanuage()).collect(Collectors.toSet());
    return dto;
  }

  public static TagDTO getDTO(Tag bo) {
    var dto = new TagDTO();
    dto.id = bo.getId();
    dto.label = bo.getLabel();
    dto.iconUrl = bo.getIconUrl();
    dto.restricted = bo.isRestricted();
    dto.assignedItemsCount = bo.getAssignedItemsCount();
    dto.recommended = bo.isRecommended();
    dto.promotional = bo.isPromotional();
    dto.defaultLanguage = bo.getDefaultLanguage().getLanuage();
    dto.language = bo.getCurrentLanguage() == null ? dto.defaultLanguage
        : bo.getCurrentLanguage().getLanuage();
    return dto;
  }

  public static TagDTO getFullDTO(Tag bo) {
    var dto = getDTO(bo);
    dto.availableLanguageVersions = bo.getAvailableLanguageVersions().stream()
        .map(lv -> lv.getLanuage()).collect(Collectors.toSet());
    return dto;
  }

  public static void copy(MarketPartnerDTO dto, Partner bo) {
    bo.setDescription(dto.description);
  }

  public static void copy(PartnerDTO dto, Partner bo) {
    bo.setAffiliateCode(dto.affiliateCode);
    bo.setBankAccount(dto.bankAccount);
    bo.setBlocked(dto.blocked);
    bo.setCommission(dto.commission);
    if (bo.getAddress() == null) {
      bo.setAddress(new Address());
    }
    copy(dto.location, bo.getAddress());
    if (bo.getContactPerson() == null) {
      bo.setContactPerson(new ContactPerson());
    }
    copy(dto.contactPerson, bo.getContactPerson());
    if (bo.getTechnicalContact() == null) {
      bo.setTechnicalContact(new ContactPerson());
    }
    copy(dto.technicalContact, bo.getTechnicalContact());
    bo.setDescription(dto.description);
    bo.setEmail(dto.email);
    bo.setInvoiceEmail(dto.invoiceEmail);
    bo.setKrs(dto.krs);
    bo.setName(dto.name);
    bo.setPhone(dto.phone);
    bo.setRegon(dto.regon);
    bo.setServicesDescription(dto.servicesDescription);
    bo.setShopUrl(dto.shopUrl);
    try {
      bo.setSocialNumber(Long.valueOf(dto.socialNumber));
    } catch (Exception e) {
      logger.log(Level.DEBUG, "social number cant be parsed");
    }
    bo.setTaxNumber(dto.taxNumber);
  }

  private static void copy(ContactPersonDTO dto, ContactPerson bo) {
    bo.setName(dto.name);
    bo.setEmail(dto.email);
    bo.setPhone(dto.phone);
  }

  private static void copy(LocationDTO dto, Address bo) {
    bo.setCity(dto.city);
    bo.setCountry(dto.country);
    bo.setPostCode(dto.zipCode);
    bo.setStreet(dto.street);
    bo.setDirections(dto.directions);
    bo.setCommune(dto.commune);
    bo.setCounty(dto.county);
    bo.setVoivodeship(dto.voivodeship);
  }

}
