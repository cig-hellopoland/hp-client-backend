package pl.hellopoland.rest.market;

import pl.hellopoland.annotation.DateFormat;
import pl.hellopoland.config.SightEventPagedCollectionConfig;
import pl.hellopoland.dto.FiltersContainerDTO;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.rest.RestService;
import pl.hellopoland.rest.dto.AvailableDatesORO;
import pl.hellopoland.rest.dto.AvailableTicketNumberAssociationORO;
import pl.hellopoland.rest.dto.PagedCollection;
import pl.hellopoland.service.api.market.FilterMarketAPI;
import pl.hellopoland.service.api.market.SightEventServiceMarketAPI;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.Date;

@Path("/market/sight-events")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarketSightEventRestService {

  @Inject
  private SightEventServiceMarketAPI service;

  @Inject
  private FilterMarketAPI filterService;

  // listings
  @GET
  public PagedCollection<SightEventDTO> list(@QueryParam("city") String city,
      @QueryParam("fromDate") @DateFormat Date fromDate,
      @QueryParam("toDate") @DateFormat Date toDate,
      @HeaderParam("Accept-Language") String acceptLanguage,
      @HeaderParam("Content-Language") String contentLanguage) {
    LanguageVersion lang =
        RestService.parseLang(contentLanguage != null ? contentLanguage : acceptLanguage);

    var config = new SightEventPagedCollectionConfig();
    config.setCity(city);
    config.setDateFrom(fromDate);
    config.setDateTo(toDate);
    config.setLanguage(lang);
    return service.getList(config);
  }

  @GET
  @Path("/favourites")
  public PagedCollection<SightEventDTO> favourites(
      @HeaderParam("Accept-Language") String acceptLanguage,
      @QueryParam("fromDate") @DateFormat Date fromDate,
      @QueryParam("toDate") @DateFormat Date toDate,
      @HeaderParam("Content-Language") String contentLanguage) {
    LanguageVersion lang =
        RestService.parseLang(contentLanguage != null ? contentLanguage : acceptLanguage);

    var config = new SightEventPagedCollectionConfig();
    config.setDateFrom(fromDate);
    config.setDateTo(toDate);
    config.setLanguage(lang);
    config.setLoggedUserFavourites();
    return service.getList(config);
  }

  @GET
  @Path("/recommended")
  public PagedCollection<SightEventDTO> recommended(
      @HeaderParam("Content-Language") String contentLanguage,
      @QueryParam("count") @DefaultValue("6") Integer count) {
    LanguageVersion lang = RestService.parseLang(contentLanguage);

    var config = new SightEventPagedCollectionConfig();
    config.setLanguage(lang);
    config.setPageSize(6);
    config.setOrderColumn("random()");
    return service.getList(config);
  }

  @POST
  @Path("/personalized")
  public PagedCollection<SightEventDTO> personalized(
      SightEventPagedCollectionConfig config,
      @HeaderParam("Content-Language") String contentLanguage,
      @QueryParam("count") @DefaultValue("6") Integer count) {
    LanguageVersion lang = RestService.parseLang(contentLanguage);
    config.setPageSize(count);
    config.setLanguage(lang);
    config.setDateFrom(new Date());
    return service.getPersonalized(config);
  }

  @GET
  @Path("/promoted")
  public PagedCollection<SightEventDTO> promoted(
      @HeaderParam("Content-Language") String contentLanguage) {
    LanguageVersion lang = RestService.parseLang(contentLanguage);

    var config = new SightEventPagedCollectionConfig();
    config.setLanguage(lang);
    config.setPromotion(1, 2, 3, 4, 5, 6, 7, 8);
    config.setPageSize(8);
    config.setOrderColumn("e.promotion, e.id");
    config.setOrderDirection("asc");
    config.setDateFrom(new Date());
    return service.getList(config);
  }


  // single
  @GET
  @Path("/{id}")
  public SightEventDTO get(@PathParam("id") Long id,
      @HeaderParam("Accept-Language") String acceptLanguage,
      @HeaderParam("Content-Language") String contentLanguage) {
    return service.get(id, contentLanguage != null ? contentLanguage : acceptLanguage);
  }

  @GET
  @Path("/{id}/available-tickets")
  public AvailableTicketNumberAssociationORO checkAvailability(@PathParam("id") Long id,
      @QueryParam("date") @DateFormat final Date date) {
    return service.checkAvailability(id, date, null);
  }

  @GET
  @Path("/{id}/available-dates")
  public AvailableDatesORO checkAvailableDates(@PathParam("id") Long id,
      @QueryParam("date") String dateString) {
    LocalDate date = null;
    if (dateString != null) {
      date = LocalDate.parse(dateString);
    }
    return service.checkAvailableDates(id, date);
  }

  @PATCH
  @Path("/{id}/favourite")
  public SightEventDTO addToFavourite(@PathParam("id") Long id) {
    service.addFavourite(id);
    return get(id, "pl-pl", "pl-pl");
  }

  @DELETE
  @Path("/{id}/favourite")
  public Response removeFavourite(@PathParam("id") Long id) {
    service.removeFavourite(id);
    return Response.ok().build();
  }


  // misc
  @GET
  @Path("/filters")
  public FiltersContainerDTO getFilters() {
    return filterService.getForSightEvents();
  }

}
