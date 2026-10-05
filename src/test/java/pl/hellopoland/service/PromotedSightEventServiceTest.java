package pl.hellopoland.service;

import static org.junit.Assert.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.exception.conflict.ConflictingException;

public class PromotedSightEventServiceTest {
  private final PromotedSightEventService service = new PromotedSightEventService();
  private final List<SightEvent> offers = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();
  private final List<List<Integer>> flushedPositions = new ArrayList<>();

  @Before
  public void setupPersistence() {
    service.em = (EntityManager) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {EntityManager.class}, (proxy, method, args) -> {
          switch (method.getName()) {
            case "createNativeQuery":
              assertEquals("select 1 from pg_advisory_xact_lock(724801, 1)", args[0]);
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Query.class},
                  (query, queryMethod, queryArgs) -> {
                    if (queryMethod.getName().equals("getSingleResult")) { calls.add("lock"); return 1; }
                    throw new AssertionError(queryMethod);
                  });
            case "createQuery":
              assertEquals("lock", calls.get(calls.size() - 1));
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TypedQuery.class},
                  (query, queryMethod, queryArgs) -> {
                    if (queryMethod.getName().equals("getResultList")) {
                      calls.add("read");
                      return promoted();
                    }
                    throw new AssertionError(queryMethod);
                  });
            case "find":
              return offers.stream().filter(offer -> offer.getId().equals(args[1])).findFirst().orElse(null);
            case "flush":
              List<Integer> positions = promoted().stream().map(SightEvent::getPromotion)
                  .collect(Collectors.toList());
              assertEquals("Positions must remain unique at every flush", positions.size(),
                  new HashSet<>(positions).size());
              flushedPositions.add(positions);
              return null;
            default:
              throw new AssertionError(method);
          }
        });
  }

  @Test
  public void appendsFourthOfferWithoutReplacingExistingOnes() {
    seed(3);
    service.add(4L);
    assertOrder(1L, 2L, 3L, 4L);
    assertEquals(List.of(), flushedPositions.get(0));
    assertEquals(List.of(1, 2, 3, 4), flushedPositions.get(1));
  }

  @Test
  public void allowsEightAndRejectsNinthWithoutChanges() {
    seed(0);
    for (long id = 1; id <= 8; id++) service.add(id);
    expectConflict(() -> service.add(9L));
    assertOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    assertNull(offers.get(8).getPromotion());
  }

  @Test
  public void repeatedAddIsIdempotentEvenWhenFull() {
    seed(8);
    service.add(8L);
    assertOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    assertTrue(flushedPositions.isEmpty());
  }

  @Test
  public void reorderSwapsPositionsWithoutRemovingOffers() {
    seed(4);
    service.reorder(List.of(4L, 2L, 1L, 3L));
    assertOrder(4L, 2L, 1L, 3L);
    assertEquals(List.of(), flushedPositions.get(0));
  }

  @Test
  public void rejectsStaleDuplicateUnknownAndNullOrderBeforeWriting() {
    seed(3);
    for (List<Long> ids : Arrays.asList(null, List.of(1L, 2L), List.of(1L, 2L, 2L),
        List.of(1L, 2L, 9L), Arrays.asList(1L, 2L, null))) {
      expectConflict(() -> service.reorder(ids));
      assertOrder(1L, 2L, 3L);
    }
    assertTrue(flushedPositions.isEmpty());
  }

  @Test
  public void deletionClosesGapAndMakesRoomForAnotherOffer() {
    seed(8);
    service.remove(2L);
    assertOrder(1L, 3L, 4L, 5L, 6L, 7L, 8L);
    service.add(9L);
    assertOrder(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    assertNull(offers.get(1).getPromotion());
  }

  @Test
  public void oldPositionEndpointAlsoPreservesMembership() {
    seed(3);
    service.move(3L, 1);
    assertOrder(3L, 1L, 2L);
    service.move(4L, 2);
    assertOrder(3L, 4L, 1L, 2L);
  }

  @Test
  public void inactivePromotedOfferStillCountsAndCanBeRemoved() {
    seed(8);
    offers.get(0).setActive(false);
    expectConflict(() -> service.add(9L));
    service.remove(1L);
    service.add(9L);
    assertOrder(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
  }

  @Test
  public void inactiveOrMissingCandidateCannotChangeTheList() {
    seed(3);
    offers.get(3).setActive(false);
    expectConflict(() -> service.add(4L));
    expectConflict(() -> service.add(99L));
    assertOrder(1L, 2L, 3L);
    assertTrue(flushedPositions.isEmpty());
  }

  private void seed(int count) {
    for (int id = 1; id <= 9; id++) {
      SightEvent offer = new SightEvent();
      offer.setId((long) id);
      offer.setActive(true);
      offer.setPromotion(id <= count ? id : null);
      offers.add(offer);
    }
  }

  private List<SightEvent> promoted() {
    return offers.stream().filter(offer -> offer.getPromotion() != null)
        .sorted(Comparator.comparing(SightEvent::getPromotion)).collect(Collectors.toList());
  }

  private void assertOrder(Long... ids) {
    List<SightEvent> selected = promoted();
    assertEquals(Arrays.asList(ids), selected.stream().map(SightEvent::getId).collect(Collectors.toList()));
    for (int i = 0; i < selected.size(); i++) assertEquals(Integer.valueOf(i + 1), selected.get(i).getPromotion());
  }

  private void expectConflict(Runnable operation) {
    try { operation.run(); fail("Expected conflict"); } catch (ConflictingException expected) { }
  }
}
