package pl.hellopoland.util;

import static org.junit.Assert.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.Test;
import pl.hellopoland.bo.Partner;
import pl.hellopoland.bo.Sight;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.dto.SightDTO;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.exception.badrequest.BadRequestException;

public class SeasonalClosureTest {
  private final LocalDate start = LocalDate.of(2026, 10, 29);
  private final LocalDate end = LocalDate.of(2026, 10, 30);

  private boolean active(boolean enabled, LocalDate from, LocalDate to, String instant) {
    return SeasonalClosure.isActive(enabled, from, to, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  @Test public void respectsInclusiveDatesAndWarsawMidnight() {
    assertFalse(active(true, start, end, "2026-10-28T22:59:59Z"));
    assertTrue(active(true, start, end, "2026-10-28T23:00:00Z"));
    assertTrue(active(true, start, end, "2026-10-30T22:59:59Z"));
    assertFalse(active(true, start, end, "2026-10-30T23:00:00Z"));
    LocalDate summer = LocalDate.of(2026, 7, 1);
    assertTrue(active(true, summer, summer, "2026-06-30T22:00:00Z"));
    assertFalse(active(true, summer, summer, "2026-07-01T22:00:00Z"));
  }

  @Test public void supportsManualAndOpenEndedRanges() {
    assertTrue(active(true, null, null, "2026-10-29T12:00:00Z"));
    assertFalse(active(false, null, null, "2026-10-29T12:00:00Z"));
    assertTrue(active(true, start, null, "2026-11-01T12:00:00Z"));
    assertFalse(active(true, start, null, "2026-10-01T12:00:00Z"));
    assertTrue(active(true, null, end, "2026-10-01T12:00:00Z"));
    assertFalse(active(true, null, end, "2026-11-01T12:00:00Z"));
    assertFalse(active(false, start, end, "2026-10-29T12:00:00Z"));
  }

  @Test(expected = BadRequestException.class)
  public void rejectsReversedDates() { SeasonalClosure.validate(true, "2026-11-01", "2026-10-29"); }

  @Test(expected = BadRequestException.class)
  public void rejectsImpossibleDate() { SeasonalClosure.parse("2026-02-30"); }

  @Test(expected = BadRequestException.class)
  public void rejectsNonIsoDate() { SeasonalClosure.parse("29.10.2026"); }

  @Test public void mapsIndependentStatusesAndPreservesOldClientUpdates() {
    Partner partner = new Partner();
    partner.setId(1L);
    Sight sight = new Sight();
    sight.setPartner(partner);
    sight.setDefaultLanguage(LanguageVersion.PL_PL);
    sight.setPublished(true);
    SightEvent offer = new SightEvent();
    offer.setSight(sight);
    offer.setDefaultLanguage(LanguageVersion.PL_PL);
    offer.setPublished(true);
    SightEventDTO dto = new SightEventDTO();
    dto.seasonallyClosed = true;
    DtoMapper.copy(dto, offer);
    assertTrue(DtoMapper.getDTO(offer).seasonalClosureActive);
    assertFalse(DtoMapper.getDTO(sight).seasonalClosureActive);
    assertTrue(offer.isAccessible());
    assertTrue(sight.isAccessible());
    DtoMapper.copy(new SightEventDTO(), offer);
    assertTrue(offer.isSeasonallyClosed());
    dto.seasonallyClosed = false;
    DtoMapper.copy(dto, offer);
    SightDTO sightDto = new SightDTO();
    sightDto.seasonallyClosed = true;
    sightDto.seasonalClosureFrom = "2026-10-29";
    DtoMapper.copy(sightDto, sight);
    DtoMapper.copy(new SightDTO(), sight);
    assertTrue(sight.isSeasonallyClosed());
    assertEquals(start, sight.getSeasonalClosureFrom());
    assertEquals("2026-10-29", DtoMapper.getDTO(sight).seasonalClosureFrom);
    assertFalse(DtoMapper.getDTO(offer).seasonalClosureActive);
    sightDto.seasonalClosureFrom = "";
    DtoMapper.copy(sightDto, sight);
    assertNull(sight.getSeasonalClosureFrom());
  }
}
