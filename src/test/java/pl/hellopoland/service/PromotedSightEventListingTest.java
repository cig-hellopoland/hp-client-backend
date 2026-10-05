package pl.hellopoland.service;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;
import org.junit.Test;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.bo.SightEventCategory;
import pl.hellopoland.bo.SightEventTag;
import pl.hellopoland.config.SightEventPagedCollectionConfig;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.service.api.helpdesk.SightEventServiceHelpdeskAPI;
import pl.hellopoland.util.PagedEntityCollection;

public class PromotedSightEventListingTest {
  @Test
  public void promotedQueryParsesWithoutAnyParameterBindings() {
    assertPromotedQueryParses(false);
  }

  @Test
  public void promotedQueryParsesWithAccessScopeAndBindsOnlyScopeParameters() {
    assertPromotedQueryParses(true);
  }

  private void assertPromotedQueryParses(boolean restricted) {
    SightEventPagedCollectionConfig config = new SightEventPagedCollectionConfig();
    config.onlyPromoted();
    config.setOrderColumn("e.promotion, e.id");
    config.setOrderDirection("asc");
    if (restricted) config.setPartnerIds(Set.of(17L));
    Map<String, Object> bindings = new LinkedHashMap<>();
    boolean[] parsed = {false};
    TypedQuery<?> query = (TypedQuery<?>) Proxy.newProxyInstance(
        TypedQuery.class.getClassLoader(), new Class<?>[]{TypedQuery.class},
        (proxy, method, args) -> {
          if (method.getName().equals("setParameter")) {
            bindings.put((String) args[0], args[1]);
            return proxy;
          }
          throw new AssertionError("Unexpected query call: " + method.getName());
        });
    ServiceSuperclass service = new ServiceSuperclass() {};
    service.em = (EntityManager) Proxy.newProxyInstance(
        EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class},
        (proxy, method, args) -> {
          if (method.getName().equals("createQuery")) {
            HqlParser parser = new HqlParser(new CommonTokenStream(
                new HqlLexer(CharStreams.fromString((String) args[0]))));
            parser.statement();
            assertEquals("Generated promoted-offer HQL must parse", 0, parser.getNumberOfSyntaxErrors());
            parsed[0] = true;
            return query;
          }
          throw new AssertionError("Unexpected entity manager call: " + method.getName());
        });
    service.getQuery(config);
    assertTrue(parsed[0]);
    assertEquals(restricted ? Map.of("partnerIds", Set.of(17L)) : Map.of(), bindings);
  }

  @Test
  public void selectedListRetainsInactiveOffersButStillAppliesAccessScope() throws Exception {
    SightEventServiceHelpdeskAPI api = new SightEventServiceHelpdeskAPI();
    SightEventPagedCollectionConfig[] captured = {null};
    boolean[] scopeApplied = {false};
    inject(api, "service", new SightEventService() {
      @Override public PagedEntityCollection<SightEvent> getList(SightEventPagedCollectionConfig config) {
        captured[0] = config;
        return new PagedEntityCollection<>(List.of(), config);
      }
    });
    inject(api, "accessService", new HelpdeskAccessService() {
      @Override public void applySightEventScope(SightEventPagedCollectionConfig config) {
        scopeApplied[0] = true;
      }
    });
    inject(api, "catService", new CategoryService() {
      @Override public List<SightEventCategory> getFor(Collection<SightEvent> offers) { return List.of(); }
    });
    inject(api, "tagService", new TagService() {
      @Override public List<SightEventTag> getFor(Collection<SightEvent> offers) { return List.of(); }
    });
    api.listPromoted(LanguageVersion.PL_PL);
    assertTrue(scopeApplied[0]);
    String conditions = captured[0].getConditions().toString();
    assertTrue(conditions.contains("promotion is not null"));
    assertFalse(conditions.contains("active"));
    assertEquals("e.promotion, e.id asc", captured[0].getOrder());

    api.list(new SightEventPagedCollectionConfig(), LanguageVersion.PL_PL);
    assertTrue(captured[0].getConditions().toString().contains("active"));
  }

  private void inject(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
