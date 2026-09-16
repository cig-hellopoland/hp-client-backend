package pl.hellopoland.service;

import static org.junit.Assert.assertEquals;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import pl.hellopoland.bo.Partner;
import pl.hellopoland.bo.Portal;
import pl.hellopoland.bo.Sight;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.bo.Translation;
import pl.hellopoland.dto.DTOSuperclass;
import pl.hellopoland.dto.LocationDTO;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.util.DtoMapper;
import pl.hellopoland.util.HelloTicket;
import pl.hellopoland.util.Translated;

public class SightEventLocationTest {

  private final Partner partner = new Partner();
  private final Sight sight = new Sight();
  private final Portal portal = new Portal();
  private SightEvent saved;
  private int creates;
  private int updates;

  private final SightEventService service = new SightEventService() {
    @Override
    public Portal getPortal(String name) {
      return portal;
    }

    @Override
    public SightEvent getForPartner(Long id, Partner owner) {
      assertEquals(saved.getId(), id);
      return saved;
    }

    @Override
    HelloTicket createHelloTicket(Portal target) {
      return new HelloTicket("unused") {
        @Override
        public SightEventDTO addSightEvent(SightEventDTO dto, String token) {
          creates++;
          return ticketResponse(dto);
        }

        @Override
        public SightEventDTO updateSightEvent(SightEventDTO dto, String token) {
          updates++;
          assertEquals(Long.valueOf(101L), dto.id);
          return ticketResponse(dto);
        }
      };
    }
  };

  @Before
  public void setUp() {
    partner.setId(1L);
    partner.setName("Partner");
    sight.setId(2L);
    sight.setName("Obiekt");
    sight.setSearchIndex("");
    sight.setPartner(partner);
    portal.setType(Portal.Type.HELLOTICKET_CLOUD_1);
    service.em = (EntityManager) Proxy.newProxyInstance(
        EntityManager.class.getClassLoader(), new Class<?>[] {EntityManager.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "persist":
              saved = (SightEvent) args[0];
              saved.setId(3L);
              return null;
            case "merge":
              return args[0];
            case "refresh":
            case "flush":
              return null;
            default:
              throw new AssertionError("Unexpected database call: " + method.getName());
          }
        });
    service.sightService = new SightService() {
      @Override
      public Sight get(Long id) {
        return sight;
      }

      @Override
      public void recreateSearchIndex(Sight item) {}
    };
    service.iService = new ImageService();
    service.oHoursService = new OpeningHoursService();
    service.pcd = new PostalCodeDictionaryLookup() {
      @Override
      public Optional<AdminDiv> findByZipAndCity(String zip, String city) {
        // The chosen voivodeship must survive even without a dictionary match.
        return Optional.empty();
      }
    };
    service.translationService = new TranslationService() {
      @Override
      public <T extends Translated> boolean isTranslated(T bo, LanguageVersion language) {
        return true;
      }

      @Override
      public List<Translation> getTranslations(Translated bo, LanguageVersion language) {
        return List.of();
      }

      @Override
      public <T extends Translated, D extends DTOSuperclass> T createEntityLanguageVersion(
          T bo, D dto, LanguageVersion language) {
        return bo;
      }

      @Override
      public <T extends Translated, D extends DTOSuperclass> T updateEntityLanguageVersion(
          T bo, D dto, LanguageVersion language) {
        return bo;
      }
    };
  }

  @Test
  public void preservesVoivodeshipWhenDraftIsCreatedAndSavedAgain() {
    SightEvent created = service.create(draft(), partner);
    SightEventDTO reopened = DtoMapper.getFullDTO(created);
    assertEquals("wielkopolskie", reopened.location.voivodeship);
    assertEquals("", reopened.location.county);
    assertEquals("", reopened.location.commune);

    reopened.description = "Uzupełniony opis oferty";
    SightEvent edited = service.update(created, reopened, LanguageVersion.PL_PL);
    SightEventDTO result = DtoMapper.getFullDTO(edited);
    assertEquals("wielkopolskie", result.location.voivodeship);
    assertEquals("Uzupełniony opis oferty", result.description);
    assertEquals(1, creates);
    assertEquals(1, updates);
  }

  @Test
  public void preservesAdministrativeFieldsAndAllowsChangingThemDuringEdit() {
    SightEventDTO input = draft();
    input.location.county = "poznański";
    input.location.commune = "Swarzędz";
    SightEvent created = service.create(input, partner);
    SightEventDTO reopened = DtoMapper.getFullDTO(created);
    assertEquals("poznański", reopened.location.county);
    assertEquals("Swarzędz", reopened.location.commune);

    reopened.location.voivodeship = "mazowieckie";
    reopened.location.county = "Warszawa";
    reopened.location.commune = "Warszawa";
    reopened.location.city = "Warszawa";
    reopened.location.zipCode = "00-001";
    SightEventDTO result = DtoMapper.getFullDTO(
        service.update(created, reopened, LanguageVersion.PL_PL));
    assertEquals("mazowieckie", result.location.voivodeship);
    assertEquals("Warszawa", result.location.county);
    assertEquals("Warszawa", result.location.commune);
    assertEquals("Warszawa", result.location.city);
    assertEquals("00-001", result.location.zipCode);
    assertEquals(Long.valueOf(101L), result.hptId);
  }

  private SightEventDTO draft() {
    SightEventDTO dto = new SightEventDTO();
    dto.sightId = sight.getId();
    dto.name = "Oferta testowa";
    dto.description = "Wstępny opis oferty";
    dto.defaultLanguage = LanguageVersion.PL_PL.getLanuage();
    dto.availableLanguageVersions = Set.of(dto.defaultLanguage);
    dto.published = false;
    dto.location = new LocationDTO();
    dto.location.street = "ul. Poznańska 44";
    dto.location.city = "Kobylnica";
    dto.location.zipCode = "62-006";
    dto.location.country = "Polska";
    dto.location.voivodeship = "wielkopolskie";
    dto.location.county = "";
    dto.location.commune = "";
    return dto;
  }

  private SightEventDTO ticketResponse(SightEventDTO input) {
    // Simulate HelloTicket returning a new DTO with only the location fields it supports.
    SightEventDTO response = new SightEventDTO();
    response.id = 101L;
    response.name = input.name;
    response.description = input.description;
    response.generalAdmission = input.generalAdmission;
    response.published = input.published;
    response.location = new LocationDTO();
    response.location.street = input.location.street;
    response.location.city = input.location.city;
    response.location.zipCode = input.location.zipCode;
    response.location.country = input.location.country;
    response.location.latitude = input.location.latitude;
    response.location.longitude = input.location.longitude;
    response.location.directions = input.location.directions;
    return response;
  }
}
