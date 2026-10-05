package pl.hellopoland.service;

import jakarta.ejb.Stateless;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.exception.conflict.ConflictingException;

@Stateless
public class PromotedSightEventService extends ServiceSuperclass {
  public static final int MAX_OFFERS = 8;

  public void add(Long id) {
    List<SightEvent> offers = lockAndLoad();
    if (offers.stream().anyMatch(offer -> offer.getId().equals(id))) return;
    ensureCapacity(offers);
    offers.add(getOffer(id));
    saveOrder(offers, List.of());
  }

  public void reorder(List<Long> ids) {
    List<SightEvent> offers = lockAndLoad();
    Map<Long, SightEvent> byId = offers.stream()
        .collect(Collectors.toMap(SightEvent::getId, offer -> offer));
    if (ids == null || ids.size() > MAX_OFFERS || ids.stream().anyMatch(id -> id == null)
        || new HashSet<>(ids).size() != ids.size()
        || ids.size() != offers.size() || !byId.keySet().equals(new HashSet<>(ids))) {
      throw new ConflictingException("Lista promowanych ofert zmieniła się. Odśwież ją i ponów zmianę kolejności.");
    }
    saveOrder(ids.stream().map(byId::get).collect(Collectors.toList()), List.of());
  }

  public void remove(Long id) {
    List<SightEvent> offers = lockAndLoad();
    List<SightEvent> removed = offers.stream()
        .filter(offer -> offer.getId().equals(id)).collect(Collectors.toList());
    offers.removeAll(removed);
    saveOrder(offers, removed);
  }

  // Keep older clients safe: moving an offer must never unpromote another one.
  public void move(Long id, Integer position) {
    if (position == null || position < 1 || position > MAX_OFFERS) {
      throw new ConflictingException("Pozycja musi być liczbą od 1 do 8.");
    }
    List<SightEvent> offers = lockAndLoad();
    SightEvent moved = offers.stream().filter(offer -> offer.getId().equals(id))
        .findFirst().orElse(null);
    if (moved == null) {
      ensureCapacity(offers);
      moved = getOffer(id);
    } else {
      offers.remove(moved);
    }
    offers.add(Math.min(position - 1, offers.size()), moved);
    saveOrder(offers, List.of());
  }

  private List<SightEvent> lockAndLoad() {
    // Promotion positions are globally unique. A transaction-scoped PostgreSQL
    // lock also serializes additions when the promoted list is empty.
    em.createNativeQuery("select 1 from pg_advisory_xact_lock(724801, 1)").getSingleResult();
    return new ArrayList<>(em.createQuery(
        "from SightEvent where promotion is not null order by promotion, id", SightEvent.class)
        .getResultList());
  }

  private SightEvent getOffer(Long id) {
    SightEvent offer = id == null ? null : em.find(SightEvent.class, id);
    if (offer == null || !offer.isActive()) {
      throw new ConflictingException("Wybrana oferta nie jest dostępna do promowania.");
    }
    return offer;
  }

  private void ensureCapacity(List<SightEvent> offers) {
    if (offers.size() >= MAX_OFFERS) {
      throw new ConflictingException("Możesz promować maksymalnie 8 ofert. Najpierw usuń jedną z listy.");
    }
  }

  private void saveOrder(List<SightEvent> offers, List<SightEvent> removed) {
    // Clear positions first to allow swaps under sightevent_promotion_unique.
    // Both flushes belong to the same transaction and roll back together.
    offers.forEach(offer -> offer.setPromotion(null));
    removed.forEach(offer -> offer.setPromotion(null));
    em.flush();
    for (int index = 0; index < offers.size(); index++) {
      offers.get(index).setPromotion(index + 1);
    }
    em.flush();
  }
}
