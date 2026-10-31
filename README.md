# Hello! Poland – Backend (Helpdesk API)

**Dokumentacja techniczna:** [architektura, endpointy, konfiguracja i zależności](docs/technical.md).

Przegląd lokalnych źródeł: **31.10.2026**. [Architektura, konfiguracja i praca nad modułem](docs/development.md) zawiera mapę kodu, zasady testowania i ograniczenia weryfikacji. [Mapa systemu](https://github.com/cig-hellopoland/hellopoland-docs/blob/main/README.md) prowadzi do repozytorium dokumentacji na GitHubie.

Backend projektu **Hello! Poland** to aplikacja oparta na **Jakarta EE 10**, udostępniająca interfejs **REST API** wykorzystywany przez frontendy:
* **Helpdesk Web**
* **Partner Web**

Aplikacja jest pakowana jako artefakt `WAR` i uruchamiana na serwerze **WildFly 34** (tryb standalone), najczęściej wewnątrz kontenera **Docker**.

> [!IMPORTANT]
> WAR zawiera domyślne zasoby konfiguracyjne i zasoby filtrowane podczas budowania. Ustawienia właściwe dla środowiska wymagają sprawdzenia oraz zewnętrznego pliku nadpisującego; nie zakładamy, że sam WAR wystarczy do uruchomienia na dowolnym środowisku.

---

## 🛠 Stack Techniczny

| Komponent | Wersja / Technologia |
| :--- | :--- |
| **Java (Runtime)** | Java 21 — zgodnie z poziomem kompilacji i Dockerfile |
| **Java (Build-time)** | Java 21 |
| **Serwer aplikacji** | WildFly 34 |
| **Baza danych** | PostgreSQL |
| **Komunikacja** | REST (JAX-RS) |
| **ORM / JPA** | Hibernate |
| **Build Tool** | Maven 3.8+ |
| **Konteneryzacja** | Docker |

---

## 📦 Repozytorium i artefakt

* **Repozytorium:** [hp-client-backend](https://github.com/cig-hellopoland/hp-client-backend)
* **Artefakt:** `hellopoland.war`
* **Packaging:** `WAR`
* **Wersja:** Zgodnie z definicją w `pom.xml`

---

## 🏗 Wymagania do builda

Aby zbudować projekt, Twoje środowisko musi spełniać poniższe wymagania:
* **JDK 21**
* **Maven 3.8+**
* **Git**

> [!CAUTION]
> Kod jest kompilowany dla **Javy 21**. Dotyczy to również uruchomienia tego WAR-a; lokalny Dockerfile używa WildFly 34 z JDK 21. Stan obrazu na serwerze wymaga osobnego sprawdzenia.

---

## 🚀 Profile Maven

Dostępne profile:
1.  `tst` – ustawiony jako domyślny (`activeByDefault=true`).
2.  `prod` – profil produkcyjny.

**Zalecenie:** Zawsze jawnie podawaj profil w procesach CI/CD.

### Budowa pliku WAR:
```bash
# Budowa dla profilu testowego
mvn -P tst clean package

# Budowa dla profilu produkcyjnego
mvn -P prod clean package
```
**Wynik:** `target/hellopoland.war`

---

## ⚙️ Build-time vs Runtime (Kluczowe rozróżnienie)

### 1. Build-time (Zasoby Maven)
Wykorzystywane do resource filtering i konfiguracji zasobów (np. `web.xml`).
* **Pliki:** `maven.build.tst.properties`, `maven.build.prod.properties`
* **Uwaga:** Każda zmiana w tych plikach **wymaga rebuilda pliku WAR**.

Można również wskazać własny plik właściwości:
```bash
mvn clean package -Dlocal.maven.build.properties=/path/to/maven.build.properties
```

### 2. Runtime (Konfiguracja serwera)
* **Plik:** `local.runtime.properties`
* **Cechy:** Plik nadpisujący jest dostarczany z zewnątrz (parametr JVM wskazuje plik, zwykle montowany przez wolumen). Kod najpierw ładuje do właściwości JVM domyślne `runtime.properties` z WAR-a, a następnie plik zewnętrzny. Wartości zewnętrzne mają pierwszeństwo nad wartościami z zasobu.
* **Uwaga:** Zmiana wymaga restartu, **bez rebuilda WAR**.

**Przykład (WildFly):**
```bash
standalone.sh -Dlocal.runtime.properties=/path/to/local.runtime.properties
```

---

## 🐳 Docker

Obraz Dockera zawiera WildFly, Javę i WAR z domyślnymi zasobami konfiguracyjnymi. Zewnętrzny plik nadpisujący oraz dane i zasoby DMS dostarczamy osobno.

### Przykład uruchomienia:
```bash
docker run -d \
  -p 8180:8080 \
  -v hellopoland.war:/opt/jboss/wildfly/standalone/deployments/hellopoland.war:ro \
  -v local.runtime.properties:/opt/jboss/wildfly/standalone/deployments/local.runtime.properties:ro \
  -v HELLO_DMS:/DMS \
  hpl-backend:<tag>
```

---

## 📂 DMS (Document Management System)

Backend wykorzystuje katalog DMS do przechowywania plików binarnych (obrazy, załączniki, multimedia).

* **Domyślna ścieżka:** `/DMS`
* **Wymagania:** W Dockerze musi być podmontowany **named volume** (np. `HELLO_DMS`) lub **bind-mount**.

**Skutki braku DMS:**
* Brak obrazów w odpowiedziach API.
* Błędy uploadu plików.
* Błędy 500 w niektórych endpointach REST.

---

## 🛣 Endpointy API

API dostępne jest pod kontekstem: `/hellopoland/v1`

**Przykładowe ścieżki:**
* `/hellopoland/v1/market/...`
* `/hellopoland/v1/helpdesk/...`
* `/hellopoland/v1/partner/...`

### Informacja o wersji
Podczas builda generowany jest plik `target/classes/git.properties`, który zawiera:
* Hash commitu
* Nazwę brancha
* Timestamp buildu

---

## ⚠️ Najczęstsze problemy

* ❌ **Błąd kompilacji:** Użycie Javy starszej niż 21.
* ❌ **Zły profil:** Pomylenie profilu `tst` z `prod` podczas pakowania.
* ❌ **Brak konfiguracji:** Brak pliku `local.runtime.properties` przy starcie WildFly.
* ❌ **Brak mediów:** Zapomnienie o zamontowaniu wolumenu pod `/DMS`.

---

## 📜 Zasady projektu

1. **Konfiguracja:** Ustawienia środowiska dostarczamy zewnętrznym plikiem; WAR zawiera także domyślne `runtime.properties`.
2. **Profile:** `tst` i `prod` wpływają na zasoby podczas budowania. Nie zakładamy zamienności artefaktów bez sprawdzenia wynikowej konfiguracji.
3. **Weryfikacja:** Wersję i konfigurację działającej usługi potwierdzamy osobno; opis repozytorium nie jest potwierdzeniem stanu PROD.

---

## 📚 Dokumentacja uzupełniająca

* **Promocje i kody:** [aktualny dokument modułu](docs/promotions.md) — kontrakty, daty ważności, zakres ofert, pule HT, rezerwacje i ograniczenia potwierdzone w lokalnym kodzie 31.10.2026.

* **Wiki projektu:** [Hello! Poland: API](https://wiki.coigdzie.pl/wiki/Hello!_Poland:_API) (Wdrożenia, Środowiska, DMS)
* **Repozytorium Backend:** [hp-client-backend](https://github.com/cig-hellopoland/hp-client-backend)
