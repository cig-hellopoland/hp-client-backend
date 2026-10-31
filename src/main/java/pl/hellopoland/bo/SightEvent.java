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
@Table(uniqueConstraints = {
    @UniqueConstraint(name = "sightevent_promotion_unique", columnNames = {"promotion"})})
public class SightEvent extends ModelSuperclass implements Located, Imaged, Translated, Partnered {

  private static final long serialVersionUID = -34796485244638912L;

  @NotNull
  @Multilingual
  private String name;
  private Date date;
  private Boolean generalAdmission;
  @ManyToOne(fetch = FetchType.LAZY)
  private ImageCollector mainImage;
  @OneToMany(fetch = FetchType.LAZY)
  @JoinTable(name = "sightevent_images",
      joinColumns = {@JoinColumn(name = "sightevent_id", referencedColumnName = "id")},
      inverseJoinColumns = {
          @JoinColumn(name = "imagecollector_id", referencedColumnName = "id", unique = true)})
  private List<ImageCollector> images = new ArrayList<>();
  @ManyToMany(fetch = FetchType.LAZY)
  private Set<Agreement> agreements = new HashSet<>();
  @Multilingual
  private String lead;
  @Column(columnDefinition = "varchar(2500)")
  @Multilingual
  private String description;
  private Integer duration;
  @Transient
  private Integer minPrice;
  @Transient
  private Integer minDiscountPrice;
  private Float score;
  @Embedded
  private Location location;
  private String email;
  private String phone;
  @ManyToOne(fetch = FetchType.LAZY)
  private FileDescriptor pdfAttachment;
  @OneToMany(mappedBy = "sightEvent", fetch = FetchType.LAZY)
  @OrderBy("day asc")
  private Collection<OpeningHours> openingHours = new ArrayList<>();
  @ManyToOne(fetch = FetchType.LAZY)
  private Portal portal;
  @ManyToOne(fetch = FetchType.EAGER)
  private Sight sight;
  @ManyToOne(fetch = FetchType.LAZY)
  private Partner partner;
  @NotNull
  private Long hptId;
  private boolean active = true;
  private boolean published;
  private boolean blocked;
  @Column(name = "seasonally_closed", nullable = false)
  private boolean seasonallyClosed;
  @Column(name = "seasonal_closure_from")
  private LocalDate seasonalClosureFrom;
  @Column(name = "seasonal_closure_to")
  private LocalDate seasonalClosureTo;
  private boolean available;
  @NotNull
  @Column(length = 5, nullable = false)
  @Enumerated(EnumType.STRING)
  private LanguageVersion defaultLanguage;
  @Column(nullable = false)
  @ElementCollection
  @Enumerated(EnumType.STRING)
  private Set<LanguageVersion> availableLanguageVersions = new HashSet<>();
  @Transient
  private LanguageVersion currentLanguage;
  private Integer promotion;
  @OneToMany(mappedBy = "sightEvent", fetch = FetchType.LAZY)
  private Set<SightEventCategory> categories = new HashSet<>();
  @OneToMany(mappedBy = "sightEvent", fetch = FetchType.LAZY)
  private Set<SightEventTag> tags = new HashSet<>();
  @Column(columnDefinition = "varchar")
  private String searchIndex;
  @ManyToMany(fetch = FetchType.LAZY)
  private Set<User> users = new HashSet<>();
  @Transient
  private boolean favourite;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @Override
  public ImageCollector getMainImage() {
    return mainImage;
  }

  @Override
  public void setMainImage(ImageCollector mainImage) {
    this.mainImage = mainImage;
  }

  public Collection<Agreement> getAgreements() {
    return agreements;
  }

  public void setAgreements(Set<Agreement> agreements) {
    this.agreements = agreements;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Integer getMinPrice() {
    return minPrice;
  }

  public void setMinPrice(Integer minPrice) {
    this.minPrice = minPrice;
  }

  public Float getScore() {
    return score;
  }

  public void setScore(Float score) {
    this.score = score;
  }

  public String getLead() {
    return lead;
  }

  public void setLead(String lead) {
    this.lead = lead;
  }

  public Collection<OpeningHours> getOpeningHours() {
    return openingHours;
  }

  public void setOpeningHours(Collection<OpeningHours> openingHours) {
    this.openingHours = openingHours;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  @Override
  public void setLocation(Location location) {
    this.location = location;
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

  public Portal getPortal() {
    return portal;
  }

  public void setPortal(Portal portal) {
    this.portal = portal;
  }

  public void generateRandomScore() {
    float score = (float) (4.8 + new Random().nextDouble() / 5);
    this.score = new BigDecimal(score).setScale(1, RoundingMode.HALF_UP).floatValue();
  }

  public Sight getSight() {
    return sight;
  }

  public void setSight(Sight sight) {
    this.sight = sight;
  }

  public Partner getPartner() {
    return partner;
  }

  public void setPartner(Partner partner) {
    this.partner = partner;
  }

  public Date getDate() {
    return date;
  }

  public void setDate(Date date) {
    this.date = date;
  }

  public Integer getDuration() {
    return duration;
  }

  public void setDuration(Integer duration) {
    this.duration = duration;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Long getHptId() {
    return hptId;
  }

  public void setHptId(Long hptId) {
    this.hptId = hptId;
  }

  public Boolean getGeneralAdmission() {
    return generalAdmission;
  }

  public void setGeneralAdmission(Boolean generalAdmission) {
    this.generalAdmission = generalAdmission;
  }

  public List<ImageCollector> getImages() {
    return images;
  }

  public void setImages(List<ImageCollector> images) {
    this.images = images;
  }


  public FileDescriptor getPdfAttachment() {
    return pdfAttachment;
  }

  public void setPdfAttachment(FileDescriptor pdfAttachment) {
    this.pdfAttachment = pdfAttachment;
  }

  /**
   * Check whether this SightEvent is active, published, not blocked and its Sight is accessible.
   * 
   * @return boolean
   */
  public boolean isAccessible() {
    return published && active && !blocked && sight.isAccessible();
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

  public boolean isAvailable() {
    return available;
  }

  public void setAvailable(boolean available) {
    this.available = available;
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

  public Integer getPromotion() {
    return promotion;
  }

  public void setPromotion(Integer promotion) {
    this.promotion = promotion;
  }

  public Set<SightEventCategory> getCategories() {
    return categories;
  }

  public void setCategories(Set<SightEventCategory> categories) {
    this.categories = categories;
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

  public Set<SightEventTag> getTags() {
    return tags;
  }

  public void setTags(Set<SightEventTag> tags) {
    this.tags = tags;
  }

  public Set<User> getUsers() {
    return users;
  }

  public void setUsers(Set<User> users) {
    this.users = users;
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

  public Integer getMinDiscountPrice() {
    return minDiscountPrice;
  }

  public void setMinDiscountPrice(Integer minDiscountPrice) {
    this.minDiscountPrice = minDiscountPrice;
  }

  public void fetchRelations() {
    Hibernate.initialize(getAvailableLanguageVersions());
    Hibernate.initialize(getAgreements());
    Hibernate.initialize(getImages());
    Hibernate.initialize(getOpeningHours());
    Hibernate.initialize(getCategories());
    Hibernate.initialize(getTags());
    Hibernate.initialize(getPartner());
    Hibernate.initialize(getPortal());
    Hibernate.initialize(getMainImage());
    Hibernate.initialize(getPdfAttachment());
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


    public void recreateSearchIndex(Set<String> words) {
    this.searchIndex =
        Stream.of(
            words.stream(),
            Stream.of(email, phone, name, location.getStreet(),location.getCity(), partner.getName()),
            Stream.of(sight.getSearchIndex().split(",")))
            .flatMap(s -> s)
            .filter(Objects::nonNull)
            .flatMap(s -> Stream.of(s.split(" ")))
            .map(w -> w.replaceAll("[^a-zA-Z0-9ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]", " "))
            .distinct()
            .filter(w -> !w.isBlank())
            .collect(Collectors.joining(","));
  }
}
