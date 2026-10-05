package pl.hellopoland.rest.helpdesk;

import pl.hellopoland.config.SightEventPagedCollectionConfig;
import pl.hellopoland.dto.SightEventDTO;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.exception.conflict.ConflictingException;
import pl.hellopoland.rest.RestService;
import pl.hellopoland.rest.dto.PagedCollection;
import pl.hellopoland.service.api.helpdesk.SightEventServiceHelpdeskAPI;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@RequestScoped
@Path("/helpdesk/sight-events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HelpdeskSightEventRestService {

  @Inject
  private SightEventServiceHelpdeskAPI service;

  @POST
  public SightEventDTO create(
      @HeaderParam("Content-Language") String contentLanguage,
      SightEventDTO dto) {
    LanguageVersion lang = RestService.parseLang(contentLanguage);
    if (dto.id == null) {
      return service.create(dto, lang);
    }
    return service.createLanguageVesrion(dto, lang);
  }

  @GET
  public PagedCollection<SightEventDTO> list(
      @HeaderParam("Content-Language") String contentLanguage) {
    return service.list(new SightEventPagedCollectionConfig(),
        RestService.parseLang(contentLanguage));
  }

  @GET
  @Path("/promoted")
  public PagedCollection<SightEventDTO> promoted(
      @HeaderParam("Content-Language") String contentLanguage) {
    return service.listPromoted(RestService.parseLang(contentLanguage));
  }

  @POST
  @Path("/{id}/promotion")
  public Response addPromotion(@PathParam("id") Long id) {
    service.addPromotion(id);
    return Response.ok().build();
  }

  @PUT
  @Path("/promoted/order")
  public Response reorderPromotions(List<Long> ids) {
    service.reorderPromotions(ids);
    return Response.ok().build();
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") Long id) {
    service.delete(id);
    return Response.ok().build();
  }

  @GET
  @Path("/{id}")
  public SightEventDTO get(
      @HeaderParam("Content-Language") String contentLanguage,
      @PathParam("id") Long id) {
    return service.get(id, RestService.parseLang(contentLanguage));
  }

  @DELETE
  @Path("/{id}/languageVersion/{language}")
  public Response deleteLanguageVersion(
      @PathParam("id") Long id,
      @PathParam("language") String language) {
    LanguageVersion lang = RestService.parseLang(language);
    service.deleteLanguageVersion(id, lang);
    return Response.ok().build();
  }

  @PATCH
  @Path("/{id}/defaultLanguage")
  public SightEventDTO changeDefaultLanguage(
      @HeaderParam("Content-Language") String contentLanguage,
      @PathParam("id") Long id) {
    LanguageVersion lang = RestService.parseLang(contentLanguage);
    return service.changeDefaultLanguage(id, lang);
  }

  @PUT
  @Path("/{id}/languageVersion/{language}")
  public SightEventDTO update(@PathParam("id") Long id,
      @PathParam("language") String language,
      SightEventDTO dto) {
    LanguageVersion lang = RestService.parseLang(language);
    dto.id = id;
    return service.update(dto, lang);
  }

  @DELETE
  @Path("/{id}/promotion")
  public Response setPromotion(
      @PathParam("id") Long id) {
    service.removePromotion(id);
    return Response.ok().build();
  }

  @PATCH
  @Path("/{id}/promotion/{value}")
  public Response setPromotion(
      @PathParam("id") Long id,
      @PathParam("value") Integer promotion) {
    if (promotion == null || promotion < 1 || promotion > 8) {
      throw new ConflictingException("The 'value' parameter must be an integer from 1 to 8.");
    }
    service.setPromotion(id, promotion);
    return Response.ok().build();
  }

  @PATCH
  @Path("/{id}/categories/{cId}")
  public SightEventDTO addCategory(
      @PathParam("id") Long id,
      @PathParam("cId") Long categoryId) {
    return service.addCategory(id, categoryId);
  }

  @DELETE
  @Path("/{id}/categories/{cId}")
  public SightEventDTO removeCategory(
      @PathParam("id") Long id,
      @PathParam("cId") Long categoryId) {
    return service.removeCategory(id, categoryId);
  }

  @PATCH
  @Path("/{id}/tags/{tId}")
  public SightEventDTO addTag(
      @PathParam("id") Long id,
      @PathParam("tId") Long tagId) {
    return service.addTag(id, tagId);
  }

  @DELETE
  @Path("/{id}/tags/{tId}")
  public SightEventDTO removeTag(
      @PathParam("id") Long id,
      @PathParam("tId") Long tagId) {
    return service.removeTag(id, tagId);
  }

}
