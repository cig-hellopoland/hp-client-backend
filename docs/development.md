# Backend HP — architektura i praca nad modułem

Stan: **31.10.2026**, sprawdzenie lokalnego kodu, POM i Dockerfile. Polecenia budowania i testów są instrukcjami, nie raportem ich wykonania.

## Odpowiedzialność i granice

Backend wystawia API rynku (`market`), zespołu operacyjnego (`helpdesk`) i partnerów (`partner`). Obsługuje dane ofert, atrakcji, partnerów, użytkowników, zamówienia i promocje. Operacje biletowe deleguje do HelloTicket przez klienta HTTP.

WordPress i panele są klientami API. Dane transakcyjne nie stają się danymi WordPressa tylko dlatego, że portal je wyświetla.

## Mapa kodu

| Lokalizacja | Rola |
| --- | --- |
| [rest](../src/main/java/pl/hellopoland/rest) | Zasoby JAX-RS, wejście HTTP i DTO właściwe dla endpointów |
| [service](../src/main/java/pl/hellopoland/service) | Reguły biznesowe, operacje na danych, integracje i zadania aplikacji |
| [bo](../src/main/java/pl/hellopoland/bo) | Model biznesowy i encje |
| [security](../src/main/java/pl/hellopoland/security) | Autoryzacja i tokeny |
| [HelloTicket.java](../src/main/java/pl/hellopoland/util/HelloTicket.java) | Klient wewnętrznego API biletowego |
| [persistence.xml](../src/main/resources/META-INF/persistence.xml) | Persistence unit i datasource `java:module/hellopolandDS` |
| [web.xml](../src/main/webapp/WEB-INF/web.xml) | Konfiguracja aplikacji webowej i zasoby filtrowane przy budowaniu |

Typowy przepływ to zasób REST → serwis → encje/JPA lub klient HelloTicket → mapowanie wyniku do odpowiedzi API. Przy zmianie pola sprawdzamy wejście, zapis i mapper odpowiedzi, nie tylko klasę DTO.

## Budowanie i konfiguracja

Wymagany jest JDK 21 i Maven. POM deklaruje Jakarta EE 10 i DTO 1.3.19; Dockerfile wskazuje `quay.io/wildfly/wildfly:34.0.0.Final-jdk21`.

Z katalogu `hp-client-backend`:

```sh
mvn -P tst clean package
```

Wynik: `target/hellopoland.war`. Dla produkcji profil trzeba wskazać jawnie jako `prod`. Profil `tst` jest domyślny, więc samo `mvn package` nie oznacza budowania dla PROD.

Maven czyta `maven.build.properties` oraz plik wskazany przez `local.maven.build.properties`; profile ustawiają odpowiednio pliki TST/PROD. Ich zmiana wymaga ponownego zbudowania zasobów.

[ServiceSuperclass](../src/main/java/pl/hellopoland/service/ServiceSuperclass.java) korzysta z `System.getProperties()`, ładuje zasób `runtime.properties`, a następnie plik wskazany przez właściwość JVM `local.runtime.properties`. Zewnętrzny plik nadpisuje domyślne wartości. Wczytanie odbywa się w inicjalizacji klasy, więc podmiana pliku nie jest mechanizmem przeładowania konfiguracji na żywo.

Przed uruchomieniem trzeba przygotować bazę/datasource, zewnętrzną konfigurację, DMS i dostęp do HelloTicket. Nie kopiujemy konfiguracji produkcyjnej do przykładów dokumentacji.

## API i sprawdzenie działania

[JaxRsActivator](../src/main/java/pl/hellopoland/rest/JaxRsActivator.java) definiuje `/v1`. Dla bezpośrednio wdrożonego WAR-a ścieżka ma zwykle postać `/hellopoland/v1`; publiczny reverse proxy może udostępniać `/v1`.

[RestService](../src/main/java/pl/hellopoland/rest/RestService.java) udostępnia `GET /ping` zwracający `pong`. Przykład dla lokalnie wystawionego kontenera na porcie 8180:

```sh
curl -i http://127.0.0.1:8180/hellopoland/v1/ping
```

To sprawdza odpowiedź aplikacji. Osobno sprawdzamy autoryzację, odczyt oferty, media z DMS i połączenie z HT. Nie używamy tworzenia zamówień produkcyjnych jako zwykłego testu dostępności.

## Funkcje wymagające uwagi

- [Promocje i kody](promotions.md): jeden utrzymywany opis, ujednolicony z lokalnymi endpointami, DTO i serwisami 31.10.2026. Zawiera reguły dat, zakresu ofert, rezerwacji, integracji HT oraz ograniczenia implementacji; stare ścieżki są odnośnikami.
- [SeasonalClosure](../src/main/java/pl/hellopoland/util/SeasonalClosure.java): zamknięcie sezonowe jest informacyjne. Daty są opcjonalne, granice włączne, czas `Europe/Warsaw`; odwrócony zakres jest odrzucany. Sama flaga nie zmienia dostępności biletów.
- [TechnicalOpsAgentClient](../src/main/java/pl/hellopoland/service/TechnicalOpsAgentClient.java): integracja z ograniczonym agentem operacyjnym. Instalacja agenta jest opisana w repozytorium WP.

## Testowanie zmian

Z katalogu modułu, po przygotowaniu JDK 21, Maven i zależności:

```sh
mvn -P tst test
```

[src/test](../src/test) zawiera m.in. testy promocji, mapowania DTO, zamknięcia sezonowego, raportów CSV i obsługi kont. Plik `.http` z przepływem promocji jest scenariuszem ręcznym, a nie automatycznym testem wykonywanym przez Maven. Przed jego użyciem trzeba wybrać TST i dane testowe.

## Wdrożenie i wycofanie

Przy zmianie DTO najpierw udostępniamy wymaganą bibliotekę, potem budujemy backend. Przed podmianą WAR-a ustalamy środowisko, zachowujemy poprzedni artefakt i konfigurację oraz sprawdzamy ewentualną migrację bazy. Wycofanie WAR-a nie cofa automatycznie migracji danych.

Logi serwera zbieramy przez uzgodniony skrypt wykonywany przez operatora. Przegląd z 31.10.2026 nie obejmował połączenia z serwerem, migracji, budowania ani wdrożenia.
