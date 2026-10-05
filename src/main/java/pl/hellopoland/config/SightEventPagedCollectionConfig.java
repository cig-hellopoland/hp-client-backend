package pl.hellopoland.config;

import org.apache.commons.lang3.text.WordUtils;
import pl.hellopoland.bo.Sight;
import pl.hellopoland.bo.SightEvent;
import pl.hellopoland.enums.LanguageVersion;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SightEventPagedCollectionConfig extends PagedCollectionConfig<SightEvent> {

  public SightEventPagedCollectionConfig() {
      this.setOrderColumn("e.id");
  }

  private boolean currentPartner;
  private boolean loggedUserFavourites;
  private boolean fetchCategories;
  private boolean joinCategories;
  private boolean fetchTags;
  private boolean joinUsers;
  private LanguageVersion language;
  private Set<Long> excludedIds;
  private Date dateFrom;
  private Date dateTo;

  @Override
  public String joins() {
    return "left join fetch e.mainImage mi "
        + "left join fetch mi.orginal mio "
        + "left join fetch mi.qvga miq "
        + "left join fetch mi.vga miv "
        + "left join fetch mi.hd mih "
        + "left join fetch mi.xga mix "
        + "left join fetch mi.sxga mis "
        + "left join fetch mi.fhd mif "
        + "left join fetch mi.fourK mi4 "
        + "left join fetch mi.orginalWebp miow "
        + "left join fetch mi.qvgaWebp miqw "
        + "left join fetch mi.vgaWebp mivw "
        + "left join fetch mi.hdWebp mihw "
        + "left join fetch mi.xgaWebp mixw "
        + "left join fetch mi.sxgaWebp mivsw "
        + "left join fetch mi.fhdWebp mifw "
        + "left join fetch mi.fourKWebp mi4w "
        + "left join fetch e.partner p "
        + "left join fetch p.contactPerson pc "
        + "left join fetch p.technicalContact tc "
        + "left join fetch e.portal port "
        + "join fetch e.sight s "
        + "left join fetch s.mainImage smi "
        + (fetchCategories ? " left join fetch e.categories cs" : "")
        + (joinUsers ? " inner join e.users u" : "")
        + (joinCategories ? " inner join SightEventCategory sec on sec.sightEvent.id=e.id" : "");
  }

    public void setSearchQuery(String searchQuery) {
        if (searchQuery != null && !searchQuery.isBlank()) {

            String normalized = searchQuery
                    .replaceAll("[^\\p{L}\\p{N}]+", " ")
                    .trim();

            String tsQuery = Arrays.stream(normalized.split("\\s+"))
                    .filter(s -> !s.isBlank())
                    .map(s -> s.toLowerCase(Locale.ROOT) + ":*")
                    .collect(Collectors.joining(" & "));

            if (!tsQuery.isBlank()) {
                Map<String, Object> searchParameters = new LinkedHashMap<>();
                searchParameters.put("searchQuery", tsQuery);
                searchParameters.put("searchPhrase", "%" + normalized.toLowerCase(Locale.ROOT) + "%");
                addCondition(
                        searchParameters,
                        "(tsearch('polish_hunspell', e.searchIndex, :searchQuery) = true"
                                + " or lower(e.name) like :searchPhrase"
                                + " or lower(s.name) like :searchPhrase"
                                + " or lower(p.name) like :searchPhrase)"
                );
            }


        }
    }



  public void onlyAvailable() {
    addCondition("available", true, "e.available=:available");
  }

  public void onlyActive() {
    addCondition("active", true, "e.active=:active");
  }

  public void onlyPublished() {
    addCondition("published", true, "e.published=:published");
    addCondition("blocked", false, "e.blocked=:blocked");
    addCondition("pBlocked", false, "e.partner.blocked=:pBlocked");
  }

  public void onlyCurrentPartner(boolean only) {
    currentPartner = only;
  }

  public boolean isCurrentPartner() {
    return currentPartner;
  }

  public void onlyFavourite(Long userId) {
    joinUsers = true;
    addCondition("userId", userId, "u.id=:userId");
  }

  public void setPartner(Long partnerId) {
    addCondition("partner", partnerId, "e.sight.partner.id=:partner");
  }

  public void setPartnerIds(Set<Long> partnerIds) {
    addCondition("partnerIds", partnerIds == null || partnerIds.isEmpty() ? Set.of(-1L) : partnerIds,
        "e.sight.partner.id in (:partnerIds)");
  }

  public void setCity(String city) {
    if (city != null && !city.isBlank()) {
      addCondition("city", city, "lower(e.location.city)=lower(:city)");
    }
  }

  public void setVoivodeship(String voivodeship) {
    if (voivodeship != null && !voivodeship.isBlank()) {
      addCondition("voivodeship", voivodeship.strip(),
          "lower(trim(e.location.voivodeship))=lower(:voivodeship)");
    }
  }

  public void setPromotion(Integer... promotions) {
    if (promotions.length > 0) {
      addCondition("promotions", Arrays.asList(promotions), "e.promotion in (:promotions)");
    }
  }

  public void setCategoriesIds(List<Long> categoriesIds) {
    if (categoriesIds != null && !categoriesIds.isEmpty()) {
      addCondition("categoryIds", categoriesIds,
          "e.id in (select sightEvent.id from SightEventCategory where category.id in (:categoryIds))");
    }
  }

  public void setSameCategorySightEventIds(Set<Long> sameCategorySightEventIds) {
    setJoinCategories(true);
    setExcludedIds(sameCategorySightEventIds);
    if (sameCategorySightEventIds != null && !sameCategorySightEventIds.isEmpty()) {
      addCondition("sameCategorySightEventsIds", sameCategorySightEventIds,
          "sec.category.id in (select distinct category.id from SightEventCategory where sightEvent.id in (:sameCategorySightEventsIds))");
    }
  }

  public void setTagsIds(List<Long> tagsIds) {
    if (tagsIds != null && !tagsIds.isEmpty()) {
      addCondition("tagIds", tagsIds,
          "e.id in (select sightEvent.id from SightEventTag where tag.id in (:tagIds))");
    }
  }

  public void setCategoriesIdsArray(Long[] categoryIds) {
    if (categoryIds != null && categoryIds.length > 0) {
      setCategoriesIds(Arrays.asList(categoryIds));
    }
  }

  public void setTagsIdsArray(Long[] tagIds) {
    if (tagIds != null && tagIds.length > 0) {
      setTagsIds(Arrays.asList(tagIds));
    }
  }

  public void setFetchCategories(boolean fetchCategories) {
    this.fetchCategories = fetchCategories;
  }

  public void setJoinCategories(boolean joinCategories) {
    this.joinCategories = joinCategories;
  }

  public boolean isFetchCategories() {
    return this.fetchCategories;
  }

  public void setSight(Sight sight) {
    addCondition("sight", sight, "e.sight=:sight");
  }

  public void onlyPromoted() {
    addCondition(Map.of(), "e.promotion is not null");
  }

  public void setSightIds(Set<Long> sightIds) {
    if (sightIds == null || sightIds.isEmpty()) {
      addCondition("sightIds", Set.of(-1L), "s.id in (:sightIds)");
      return;
    }
    addCondition("sightIds", sightIds, "s.id in (:sightIds)");
  }

  public void setExcludedIds(Set<Long> ids) {
    this.excludedIds = ids;
    addCondition("ids", ids, "e.id not in (:ids)");
  }

  public Set<Long> getExcludedIds() {
    return this.excludedIds;
  }

  public void setFetchTags(boolean fetchTags) {
    this.fetchTags = fetchTags;
  }

  public boolean isFetchTags() {
    return fetchTags;
  }

  public LanguageVersion getLanguage() {
    return language;
  }

  public void setLanguage(LanguageVersion language) {
    this.language = language;
  }

  public Date getDateFrom() {
    return dateFrom;
  }

  public void setDateFrom(Date dateFrom) {
    this.dateFrom = dateFrom;
  }

  public Date getDateTo() {
    return dateTo;
  }

  public void setDateTo(Date dateTo) {
    this.dateTo = dateTo;
  }

  public boolean isLoggedUserFavourites() {
    return loggedUserFavourites;
  }

  public void setLoggedUserFavourites() {
    this.loggedUserFavourites = true;
  }

}
