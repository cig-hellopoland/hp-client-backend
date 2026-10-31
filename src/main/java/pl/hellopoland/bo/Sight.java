package pl.hellopoland.bo;

import org.hibernate.Hibernate;
import pl.hellopoland.annotation.Multilingual;
import pl.hellopoland.enums.LanguageVersion;
import pl.hellopoland.util.Imaged;
import pl.hellopoland.util.Located;
import pl.hellopoland.util.Partnered;
import pl.hellopoland.util.Translated;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Entity
public class Sight extends ModelSuperclass implements Located, Imaged, Translated, Partnered {

  private static final long serialVersionUID = -6821312294116712881L;

  @Multilingual
  private String name;
  @Multilingual
  private String lead;
  @Column(columnDefinition = "varchar(2500)")
  @Multilingual
  private String description;
  private Float score;
  @ManyToOne(fetch = FetchType.LAZY)
  private ImageCollector mainImage;
  @OneToMany(fetch = FetchType.LAZY)
  @JoinTable(name = "sight_images",
      joinColumns = {@JoinColumn(name = "sight_id", referencedColumnName = "id")},
      inverseJoinColumns = {
          @JoinColumn(name = "imagecollector_id", referencedColumnName = "id", unique = true)})
  private List<ImageCollector> images = new ArrayList<>();
  private String email;
  private String phone;
  @Embedded
  private Location location;
  @Column(name = "google_place_id", columnDefinition = "text")
  private String googlePlaceId;
  @ManyToOne(fetch = FetchType.LAZY)
  private Partner partner;
  private boolean active = true;
  @OneToMany(mappedBy = "sight", fetch = FetchType.LAZY)
  private List<SightEvent> sightEvents = new ArrayList<>();
  @OneToMany(mappedBy = "sight", fetch = FetchType.LAZY)
  @OrderBy("day asc")
  private List<OpeningHours> openingHours = new ArrayList<>();
  @ManyToMany(fetch = FetchType.LAZY)
  private Set<Agreement> agreements = new HashSet<>();
  private boolean published;
  private boolean blocked;
  @Column(name = "seasonally_closed", nullable = false)
  private boolean seasonallyClosed;
  @Column(name = "seasonal_closure_from")
  private LocalDate seasonalClosureFrom;
  @Column(name = "seasonal_closure_to")
  private LocalDate seasonalClosureTo;
  @Column(name = "animals_allowed")
  private boolean animalsAllowed; // null w DB -> false po stronie BO

  @Column(name = "car_park_available")
  private boolean carParkAvailable;
  @Column(name = "disabled_access_hearing")
  private boolean disabledAccessHearing;
  @Column(name = "disabled_access_movement")
  private boolean disabledAccessMovement;
  @Column(name = "disabled_access_vision")
  private boolean disabledAccessVision;
  @Column(name = "food_and_drink_available")
  private boolean foodAndDrinkAvailable;


  @NotNull
  @Column(length = 5, nullable = false)
  @Enumerated(EnumType.STRING)
  private LanguageVersion defaultLanguage;
  @Column(nullable = false)
  @ElementCollection(fetch = FetchType.LAZY)
  @Enumerated(EnumType.STRING)
  private Set<LanguageVersion> availableLanguageVersions;
  @Transient
  private LanguageVersion currentLanguage;
  @Column(columnDefinition = "varchar")
  private String searchIndex;
  @Transient
  private Set<Category> categories;
  @Transient
  private Set<Tag> tags;
  @ManyToMany(fetch = FetchType.LAZY)
  private Set<User> users = new HashSet<>();
  @Transient
  private boolean favourite;

  public Sight() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getLead() {
    return lead;
  }

  public void setLead(String lead) {
    this.lead = lead;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public ImageCollector getMainImage() {
    return mainImage;
  }

  @Override
  public void setMainImage(ImageCollector mainImage) {
    this.mainImage = mainImage;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  @Override
  public void setLocation(Location sightLocation) {
    this.location = sightLocation;
  }

  public String getGooglePlaceId() {
    return googlePlaceId;
  }

  public void setGooglePlaceId(String googlePlaceId) {
    this.googlePlaceId = googlePlaceId;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Partner getPartner() {
    return partner;
  }

  public void setPartner(Partner partner) {
    this.partner = partner;
  }

  public List<SightEvent> getSightEvents() {
    return sightEvents;
  }

  public void setSightEvents(List<SightEvent> sightEvents) {
    this.sightEvents = sightEvents;
  }

  public Float getScore() {
    return score;
  }

  public void setScore(Float score) {
    this.score = score;
  }

  public void generateRandomScore() {
    float score = (float) (4.8 + new Random().nextDouble() / 5);
    this.score = new BigDecimal(score).setScale(1, RoundingMode.HALF_UP).floatValue();
  }

  public List<ImageCollector> getImages() {
    return images;
  }

  public void setImages(List<ImageCollector> images) {
    this.images = images;
  }

  public List<OpeningHours> getOpeningHours() {
    return openingHours;
  }

  public void setOpeningHours(List<OpeningHours> openingHours) {
    this.openingHours = openingHours;
  }

  public Collection<Agreement> getAgreements() {
    return agreements;
  }

  public void setAgreements(Set<Agreement> agreements) {
    this.agreements = agreements;
  }

  /**
   * Check whether this Sight is active, published and not blocked.
   * 
   * @return boolean
   */
  public boolean isAccessible() {
    return published && active && !blocked;
  }

  public boolean isPublished() {
    return published;
  }

  public void setPublished(boolean published) {
    this.published = published;
  }

  public boolean isSeasonallyClosed() { return seasonallyClosed; }
  public void setSeasonallyClosed(boolean value) { seasonallyClosed = value; }
  public LocalDate getSeasonalClosureFrom() { return seasonalClosureFrom; }
  public void setSeasonalClosureFrom(LocalDate value) { seasonalClosureFrom = value; }
  public LocalDate getSeasonalClosureTo() { return seasonalClosureTo; }
  public void setSeasonalClosureTo(LocalDate value) { seasonalClosureTo = value; }

  public boolean isBlocked() {
    return blocked;
  }

  public void setBlocked(boolean blocked) {
    this.blocked = blocked;
  }

  @Override
  public LanguageVersion getDefaultLanguage() {
    return defaultLanguage;
  }

  @Override
  public void setDefaultLanguage(LanguageVersion defaultLanguage) {
    this.defaultLanguage = defaultLanguage;
  }

  @Override
  public Set<LanguageVersion> getAvailableLanguageVersions() {
    return availableLanguageVersions;
  }

  @Override
  public void setAvailableLanguageVersions(Set<LanguageVersion> availableLanguageVersions) {
    this.availableLanguageVersions = availableLanguageVersions;
  }

  @Override
  public boolean addAvailableLanguageVersion(LanguageVersion languageVersion) {
    if (availableLanguageVersions == null) {
      availableLanguageVersions = new HashSet<>();
    }
    return availableLanguageVersions.add(languageVersion);
  }

  @Override
  public boolean deleteAvailableLanguageVersion(LanguageVersion languageVersion) {
    if (availableLanguageVersions == null) {
      availableLanguageVersions = new HashSet<>();
    }
    return availableLanguageVersions.remove(languageVersion);
  }

  @Override
  public LanguageVersion getCurrentLanguage() {
    return currentLanguage;
  }

  @Override
  public void setCurrentLanguage(LanguageVersion currentLanguage) {
    this.currentLanguage = currentLanguage;
  }

  public String getSearchIndex() {
    return searchIndex;
  }

  public void setSearchIndex(String searchIndex) {
    this.searchIndex = searchIndex;
  }

  public void recreateSearchIndex(Set<String> words) {
    this.searchIndex =
        Stream.concat(
            Stream.of(email, name, phone,location.getStreet(),location.getCity(), partner.getName()),
            words.stream())
            .filter(Objects::nonNull)
            .flatMap(s -> Stream.of(s.split(" ")))
            .map(w -> w.replaceAll("[^a-zA-Z0-9ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]", " "))
            .distinct()
            .filter(w -> !w.isBlank())
            .collect(Collectors.joining(","));
  }

  public Set<Category> getCategories() {
    return categories;
  }

  public void setCategories(Set<Category> categories) {
    this.categories = categories;
  }

  public Set<Tag> getTags() {
    return tags;
  }

  public void setTags(Set<Tag> tags) {
    this.tags = tags;
  }

  public Set<User> getUsers() {
    return users;
  }

  public void addUser(User user) {
    this.users.add(user);
  }

  public void removeUser(User user) {
    this.users.remove(user);
  }

  public boolean isFavourite() {
    return favourite;
  }

  public void setFavourite(boolean favourite) {
    this.favourite = favourite;
  }

  @Transient
  public String getCommune() { return location == null ? null : location.getCommune(); }
  public void setCommune(String v) { if (location == null) location = new Location(); location.setCommune(v); }


  @Transient
  public String getCounty() { return location == null ? null : location.getCounty(); }
  public void setCounty(String v) { if (location == null) location = new Location(); location.setCounty(v); }

  @Transient
  public String getVoivodeship() { return location == null ? null : location.getVoivodeship(); }
  public void setVoivodeship(String v) { if (location == null) location = new Location(); location.setVoivodeship(v); }

  public boolean isAnimalsAllowed() { return animalsAllowed; }
  public void setAnimalsAllowed(boolean animalsAllowed) { this.animalsAllowed = animalsAllowed; }

  public boolean isCarParkAvailable() { return carParkAvailable; }

  public void setCarParkAvailable(boolean carParkAvailable) { this.carParkAvailable = carParkAvailable; }

  public boolean isDisabledAccessHearing() { return disabledAccessHearing; }

  public void setDisabledAccessHearing(boolean disabledAccessHearing) { this.disabledAccessHearing = disabledAccessHearing; }

  public boolean isDisabledAccessMovement() { return disabledAccessMovement; }
  public void setDisabledAccessMovement(boolean disabledAccessMovement) { this.disabledAccessMovement = disabledAccessMovement; }

    public boolean isDisabledAccessVision() {
        return disabledAccessVision;
    }

    public void setDisabledAccessVision(boolean disabledAccessVision) {
        this.disabledAccessVision = disabledAccessVision;
    }

    public boolean isFoodAndDrinkAvailable() {
        return foodAndDrinkAvailable;
    }

    public void setFoodAndDrinkAvailable(boolean foodAndDrinkAvailable) {
        this.foodAndDrinkAvailable = foodAndDrinkAvailable;
    }

    public void fetchRelations() {
    Hibernate.initialize(this.getMainImage());
    Hibernate.initialize(this.getOpeningHours());
    Hibernate.initialize(this.getImages());
    Hibernate.initialize(this.getAgreements());
    Hibernate.initialize(this.getAvailableLanguageVersions());
  }

}
