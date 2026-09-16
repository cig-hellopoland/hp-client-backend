package pl.hellopoland.rest.market;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pl.hellopoland.rest.dto.OrderIRO;
import pl.hellopoland.service.api.market.OrderServiceMarketAPI;

@Path("/market/orders")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarketOrderRestService {

  @Inject
  OrderServiceMarketAPI service;

  @POST
  public RedirectUrl create(OrderIRO iro) {
    String url = service.create(iro);
    return new RedirectUrl(url);
  }

  public record RedirectUrl(String redirectUrl) {}

  @GET
  @Path("/status/{hash}")
  public Response checkStatus(@PathParam("hash") String hash) {
    return Response.ok(
        Json.createObjectBuilder().add("order_status", service.checkStatus(hash).name()).build())
        .build();
  }

  @POST
  @Path("/{hash}/ackPayment")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response ackPayment(@PathParam("hash") String hash, @HeaderParam("x-jws-signature") String jws, String ack) {
    boolean accepted = service.ack(hash, ack, jws);
    return Response.status(accepted ? Response.Status.OK : Response.Status.INTERNAL_SERVER_ERROR)
        .type(MediaType.TEXT_PLAIN)
        .entity(accepted ? "TRUE" : "FALSE")
        .build();
  }

}
