# Promocje i kody — dokumentacja backendu HP

**Dokument obowiązujący dla lokalnego kodu sprawdzonego 31.10.2026.** Zastępuje obie wcześniejsze wersje `PROMOCJE_KODY_HELPDESK_BACKEND.md` z katalogu nadrzędnego i `src/main/resources/doc`.

Punkt odniesienia kodu backendu: lokalny HEAD `9201f2fe`. W tym zadaniu zmieniono wyłącznie dokumentację; hash nie oznacza wersji potwierdzonej na serwerze.

Zakres: administracja kampaniami w Helpdesku, kody, zakres ofert, pule HelloTicket, walidacja w portalu i powiązanie rezerwacji z zamówieniem. Sprawdzenie było statycznym przeglądem źródeł; nie potwierdza migracji bazy, wdrożenia ani zachowania działającego TST/PROD.

## 1. Źródła i mapa implementacji

| Źródło | Zakres |
| --- | --- |
| [HelpdeskPromotionRestService](../src/main/java/pl/hellopoland/rest/helpdesk/HelpdeskPromotionRestService.java) | Metody i ścieżki administracji |
| [PromotionManagementServiceHelpdeskAPI](../src/main/java/pl/hellopoland/service/api/helpdesk/PromotionManagementServiceHelpdeskAPI.java) | Role `root` i `admin` |
| [PromotionManagementService](../src/main/java/pl/hellopoland/service/PromotionManagementService.java) | Kampanie, kody, zakres, statusy i integracja z pulami HT |
| [MarketPromotionCodeRestService](../src/main/java/pl/hellopoland/rest/market/MarketPromotionCodeRestService.java), [API rynku](../src/main/java/pl/hellopoland/service/api/market/PromotionCodeServiceMarketAPI.java) | Publiczne operacje walidacji i zwalniania rezerwacji (`@PermitAll`) |
| [PromotionCodeService](../src/main/java/pl/hellopoland/service/PromotionCodeService.java) | Walidacja, efekty, limity, rezerwacje i zastosowanie promocji do zamówienia |
| [OrderService](../src/main/java/pl/hellopoland/service/OrderService.java), [OrderIRO](../src/main/java/pl/hellopoland/rest/dto/OrderIRO.java) | Przekazanie tokenu, potwierdzenie, anulowanie i problem zamówienia |
| [DTO promocji](../src/main/java/pl/hellopoland/rest/dto) i [encje](../src/main/java/pl/hellopoland/bo) | Pola wejściowe, odpowiedzi i zapis danych |
| [HelloTicket](../src/main/java/pl/hellopoland/util/HelloTicket.java) | Klient HTTP do HT |
| [TagService](../src/main/java/pl/hellopoland/service/TagService.java) | Znacznik promocyjny tagu |
| [PromotionReservationReleaseScheduler](../src/main/java/pl/hellopoland/service/timer/PromotionReservationReleaseScheduler.java) | Zwalnianie wygasłych rezerwacji bez zamówienia |

Ścieżki HTTP poniżej podano względem bazy API, np. `/v1` za reverse proxy lub `/hellopoland/v1` przy bezpośrednim dostępie do WAR-a. Helpdesk używa JSON, z wyjątkiem eksportu CSV.

## 2. Model danych i identyfikatory

| Encja / tabela | Znaczenie |
| --- | --- |
| `PromotionCampaign` / `promotion_campaign` | Typ, status, zakres, daty, rabat, konfiguracja ilościowa, limity i liczniki |
| `PromotionCampaignTag` / `promotion_campaign_tag` | Aktywne tagi zakresu kampanii |
| `PromotionCampaignSightEvent` / `promotion_campaign_sightevent` | Zmaterializowana relacja kampanii z ofertą HP, jej aktywność, źródło i identyfikatory pul HT |
| `PromotionCodeBatch` / `promotion_code_batch` | Źródło `IMPORT` lub `GENERATED`, opis `fileName`, liczba utworzonych kodów |
| `PromotionCode` / `promotion_code` | Unikalny globalnie tekst kodu, typ `ONE_TIME`/`FIXED`, status, limity i liczniki |
| `PromotionCodeRedemption` / `promotion_code_redemption` | Rezerwacja/użycie: token, kampania, kod, zamówienie, opcjonalna pozycja i snapshot promocji |

Nie zamieniamy przestrzeni identyfikatorów:

- `sightEventId` w administracji i `effects[].sightEvent.id` oznacza **ofertę HP**.
- `hptSightEventId` oznacza ofertę/wydarzenie w HT.
- `hptTicketDefinitionId` i `hptTicketPoolDefinitionId` oznaczają definicje HT.
- `hptAtnaId` oznacza powiązanie biletu z pulą w HT (ATNA). To właśnie ATNA jest `cartItems[].id`, `effects[].item.id` i identyfikatorem biletu w `OrderIRO.entries[].id`.
- `relationId` w endpointach ofert jest ID relacji kampanii z ofertą, a nie ID oferty.

Identyfikatory promocyjnego biletu/puli zapisuje backend po operacji w HT. Wysłanie ich do endpointu ręcznego dodania oferty nie konfiguruje integracji.

## 3. Cykl przygotowania kampanii

1. Opcjonalnie wykonać `POST /helpdesk/promotions/targets/preview` z konfiguracją planowanej kampanii.
2. Utworzyć `DRAFT`, podając wymagane pola i zakres. Można pominąć `codeSetup` i `ticketPoolSetup`, aby przygotować je później.
3. Dodać kody przez `POST /{id}/codes`; opcjonalnie pobrać CSV.
4. Dla `TICKET` sprawdzić oferty przez `POST /{id}/ticket-pools/preview` i wygenerować pule przez `POST /{id}/ticket-pools/generate`.
5. Sprawdzić gotowość wszystkich wybranych ofert i aktywować przez `PATCH /{id}/status` z `ACTIVE`.

Podczas tworzenia można też od razu przekazać kody i konfigurację pul. Jeśli żądany status to `ACTIVE`, serwis przygotowuje kampanię jako `DRAFT`, tworzy wskazane zasoby i dopiero po walidacji ustawia `ACTIVE`.

### Aktywacja i statusy

Aktywacja wymaga co najmniej jednego kodu o statusie innym niż `DISABLED`. Dla `TICKET` dodatkowo musi istnieć niepusty wybrany zakres aktywnych ofert z HT; **każda** z tych ofert musi mieć aktywną relację, `ticketPoolStatus=CREATED` i `hptAtnaId`.

Braki zgłaszane są przez `ConflictingException` z kluczami:

- `PROMOTION_CODES_REQUIRED` — brak kodów innych niż wyłączone;
- `PROMOTION_TICKET_TARGETS_REQUIRED` — brak ofert dla `TICKET`;
- `PROMOTION_TICKET_POOLS_REQUIRED` — brak gotowej puli dla części ofert.

Nie jest to sprawdzenie bieżącej dostępności miejsc w HT. Walidacja kodu dodatkowo wymaga identyfikatorów definicji biletu i puli.

`PATCH /{id}/status` dopuszcza pozostanie w tym samym statusie oraz przejścia `DRAFT → ACTIVE`, `DRAFT → DISABLED`, `ACTIVE → DISABLED`. Ponowne ustawienie `ACTIVE` uruchamia walidację gotowości. `ARCHIVED` istnieje w enumie i bazie, ale ten endpoint nie udostępnia przejścia do niego.

Pełny `PUT /{id}` działa wyłącznie dla kampanii będącej `DRAFT`. Przepisuje konfigurację, a podany `dto.status` ustawia bez użycia macierzy przejść z PATCH; `ACTIVE` nadal wymaga gotowości. Nie należy opisywać tej macierzy jako wspólnej walidacji wszystkich sposobów zapisu statusu.

## 4. Endpointy Helpdesku

Baza: **`/helpdesk/promotions`**. Wszystkie operacje przechodzą przez warstwę z rolami `root`/`admin`. Poniżej `{id}` to ID kampanii.

| Metoda | Ścieżka względem bazy | Działanie / ograniczenie |
| --- | --- | --- |
| GET | `/` | Lista kampanii od najnowszej; bez pełnych relacji szczegółowych |
| GET | `/{id}` | Szczegóły, aktywne `tagIds` i relacje ofert, także nieaktywne |
| POST | `/` | Utworzenie kampanii, opcjonalnie kodów i pul |
| POST | `/targets/preview` | Podgląd przed utworzeniem; wejście `PromotionCampaignHelpdeskDTO` |
| PUT | `/{id}` | Pełna konfiguracja tylko w `DRAFT`; nie jest częściową aktualizacją |
| PATCH | `/{id}/status` | Zmiana statusu według zasad powyżej |
| PATCH | `/{id}/marker-tag` | Ustawienie/usunięcie `markerTagId`; bez ograniczenia do `DRAFT` |
| POST | `/{id}/sight-events` | Dodanie/reaktywacja jednej oferty, tylko `DRAFT`; nie tworzy puli |
| PUT | `/{id}/sight-events/{relationId}` | Zmiana `active` relacji, tylko `DRAFT`; nie wywołuje zamknięcia puli |
| DELETE | `/{id}/sight-events/{relationId}` | Dezaktywacja relacji i zamknięcie puli `TICKET` w HT; `DRAFT` lub `ACTIVE` |
| PUT | `/{id}/sight-events` | Zastąpienie wyboru ofert, zamknięcie usuniętych i utworzenie/aktualizacja pul; `DRAFT` lub `ACTIVE` |
| GET | `/{id}/codes` | Lista kodów kampanii |
| POST | `/{id}/codes` | Dodanie kodów: import, stały kod albo generator; tylko `DRAFT` |
| PUT | `/{id}/codes` | Zastąpienie kodów i batchy; `DRAFT` bez żadnej historii redemption |
| POST | `/{id}/codes/import` | Węższy wariant importu listy przez `PromotionCodeImportIRO`; tylko `DRAFT` |
| GET | `/{id}/codes/export` | CSV całej aktualnej listy kodów kampanii |
| PUT | `/{id}/codes/{codeId}` | Aktualizacja statusu; limity `FIXED` tylko w `DRAFT` |
| GET | `/{id}/code-batches` | Lista batchy kodów |
| GET | `/{id}/redemptions` | Historia rezerwacji i użyć |
| POST | `/{id}/ticket-pools/preview` | Podgląd ofert i stanu pul, bez tworzenia zasobów HT |
| POST | `/{id}/ticket-pools/generate` | Generowanie pul `TICKET`, tylko `DRAFT` |

Podglądy zwracają `PromotionTicketPoolTargetPreviewORO`: `count` i `sightEvents`, z ID/nazwami ofert, obiektów i partnerów, powiązaniem HT oraz stanem relacji/puli. Podgląd przed utworzeniem dla `GLOBAL` zwraca pusty wynik. Podgląd istniejącej kampanii nie wymaga `DRAFT`.

## 5. Konfiguracja kampanii i daty

Wejście: [PromotionCampaignHelpdeskDTO](../src/main/java/pl/hellopoland/rest/dto/PromotionCampaignHelpdeskDTO.java).

| Pole | Reguła w aktualnym kodzie |
| --- | --- |
| `name`, `promotionType`, `scopeType` | Wymagane; nazwa nie może być pusta |
| `promotionType` | `TICKET`, `PERCENT`, `AMOUNT` |
| `scopeType` | `MANUAL`, `TAG`, `GLOBAL`; `TICKET + GLOBAL` jest odrzucane |
| `validFrom`, `validTo` | Obie wymagane; koniec nie może poprzedzać początku. Określają czas użycia kodu |
| `ticketValidTo` | Wymagane dla `TICKET`, nie wcześniej niż `validTo`; graniczny dzień wizyty. Dla innych typów serwis zapisuje `null` |
| `discountPercent` | Wymagane dla `PERCENT`; skrypt schematu ogranicza do `(0,100]` |
| `discountAmountGross` | Wymagane dla `AMOUNT`, kwota w groszach; skrypt schematu wymaga wartości dodatniej |
| `requiredTicketQuantity`, `grantedTicketQuantity` | Próg i przyznawana liczba biletów `TICKET`; brak wartości jest w walidacji traktowany jako `1` |
| `globalLimit`, `codeLimit` | Egzekwowane limity kampanii / domyślny limit kodu; `null` oznacza brak limitu |
| `customerLimit`, `dailyLimit` | Zapisywane, ale nieegzekwowane przez aktualny `validateLimits` |
| `tagIds` | Tagi zakresu; wymagane przy `TAG` |
| `targetSetup` | Wybór ofert; przy tworzeniu/pełnym PUT dla `MANUAL` wymagane niepuste `sightEventIds` |
| `markerTagId` | Osobny tag oznaczający promocję, niezależny od tagów wyboru zakresu |
| `codeSetup`, `ticketPoolSetup` | Formularze operacji tworzenia; nie są trwałą konfiguracją zwracaną w szczegółach |

`PUT /{id}` ponownie wymaga nazwy, typu, zakresu i dat. Obsługuje `targetSetup`, ale nie tworzy na nowo kodów ani pul z `codeSetup`/`ticketPoolSetup`. Właściwe operacje mają osobne endpointy.

[DateCustomAdapter](../src/main/java/pl/hellopoland/rest/DateCustomAdapter.java) używa formatu **`yyyy-MM-dd'T'HH:mm`**, np. `2026-11-01T09:00`, bez strefy w tekście. Używana jest domyślna strefa JVM. Także normalizacja dni pul i porównanie dnia wizyty używają `ZoneId.systemDefault()`. Nie zakładamy na tej podstawie wymuszonego `Europe/Warsaw`.

`validTo` jest granicą chwili walidacji kodu, a nie automatycznie końcem ważności biletu. Dzień `ticketValidTo` jest w porównaniu wizyty włączony w całości. Brak daty w koszyku nie blokuje samej walidacji kodu, ale data promocyjnego biletu musi przejść kontrolę przy wiązaniu zamówienia.

## 6. Wybór ofert i zmiany aktywnej kampanii

[PromotionTargetSetupIRO](../src/main/java/pl/hellopoland/rest/dto/PromotionTargetSetupIRO.java) zawiera `tagIds`, `partnerIds`, `sightIds`, `sightEventIds`. Jawnie podane źródła są łączone jako **suma**, z usunięciem duplikatów; nie tworzą przecięcia filtrów.

Jeśli wybór jest niepusty, ma pierwszeństwo przed fallbackiem. Przy generowaniu/podglądzie bez wyboru serwis najpierw używa aktywnych relacji kampanii, jeśli kampania ma jakiekolwiek relacje; dopiero gdy ich nie ma i zakres to `TAG`, wybiera oferty po aktywnych tagach kampanii. Nie dodaje zawsze obu zestawów.

Przy tworzeniu, pełnej aktualizacji i zbiorczym `PUT /sight-events` zakres jest materializowany w relacjach ofert. Sama walidacja kodu sprawdza aktywne relacje, a nie ponownie aktualne przypisania tagów do ofert. Nowa oferta z pasującym tagiem nie wchodzi automatycznie do istniejącej kampanii bez operacji aktualizującej zakres.

Wybór masowy/podgląd filtruje oferty jako `active=true`, `blocked=false`, z niepustym `hptId` oraz powiązaniami partnera i obiektu. Zapytanie nie dodaje osobnej kontroli publikacji ani blokady partnera — nie opisujemy go jako kompletnej walidacji widoczności w publicznym katalogu.

**Zbiorcza edycja aktywnej kampanii** przez `PUT /{id}/sight-events` przyjmuje `PromotionTicketPoolGenerationIRO`:

```json
{
  "targetSetup": {"sightEventIds": [411, 412]},
  "ticketPoolSetup": {
    "ticketPrice": 100,
    "availableTicketsNumber": 10,
    "wholeDay": true,
    "isCyclic": true
  }
}
```

Operacja jest dostępna dla `DRAFT` i `ACTIVE`, odrzuca `GLOBAL` i pusty wynik. Dla `TICKET` wymaga parametrów puli. Dezaktywuje pominięte relacje i zamyka ich pule; dla wybranych tworzy albo aktualizuje zasoby. Aktualizacja istniejącej puli nie resetuje pozostałej liczby biletów, mimo podania `availableTicketsNumber`. Usunięcie jednej relacji przez DELETE również działa w `ACTIVE` i zamyka pulę.

## 7. Kody, import, generator i CSV

[PromotionCodeSetupIRO](../src/main/java/pl/hellopoland/rest/dto/PromotionCodeSetupIRO.java) służy w `codeSetup` przy tworzeniu oraz jako bezpośrednie body `POST/PUT /{id}/codes`. Nie ma pola `mode`.

| Tryb | Przykładowe body | Wynik |
| --- | --- | --- |
| Import | `{"codes":["TEST-001","TEST-002"],"fileName":"test.csv"}` | Batch `IMPORT`; domyślnie `ONE_TIME` |
| Kod stały | `{"fixedCode":"TEST-PROMO","maxRedemptions":100}` | Jeden kod `FIXED`, batch `IMPORT` |
| Generator | `{"generateCount":100,"generatedCodePrefix":"TEST"}` | Batch `GENERATED`; domyślnie `ONE_TIME` |

Nie łączymy niepustej listy `codes`, niepustego `fixedCode` i podanego `generateCount`. Brak źródła kończy się błędem liczby generatora. Import i generator pozwalają wskazać `codeType`; `fixedCode` zawsze tworzy `FIXED`.

Import pomija `null` i puste elementy, obcina białe znaki na końcach, odrzuca pusty wynik, duplikaty w żądaniu i kody już istniejące globalnie. `fileName` jest metadanymi — endpoint przyjmuje listę w JSON, nie upload multipart CSV. Stały kod też jest obcinany przez `trim` przy zapisie.

Walidacja portalowa wyszukuje **dokładny przesłany tekst**: nie wykonuje `trim` ani zamiany wielkości liter. Klient powinien świadomie przygotować wartość, a nie zakładać normalizacji po stronie tego endpointu.

Generator:

- `generateCount`: liczba całkowita `1..100000`;
- `generatedCodeLength`: długość losowej części `1..64`, domyślnie `12`;
- alfabet: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`;
- opcjonalny prefix po `trim`; separator domyślnie `-`, tylko przy niepustym prefixie;
- usuwa kolizje z zapisanymi kodami. Parametry muszą zapewniać odpowiednio dużą przestrzeń kodów; kod nie ogranicza prób wewnętrznej pętli zbierania unikalnych wartości dla zbyt małej przestrzeni.

`ONE_TIME` dostaje `maxRedemptions=1` niezależnie od formularza. `FIXED` korzysta z podanego limitu albo fallbacku kampanii. `maxRedemptionsPerCustomer` i `maxRedemptionsPerDay` są zapisywane, ale nieegzekwowane w obecnej walidacji.

`PUT /{id}/codes` fizycznie usuwa kody i batche kampanii, po czym tworzy nowe. Wymaga `DRAFT` i braku **jakiegokolwiek** redemption, także zwolnionego lub wygasłego. Nie jest operacją dopisania.

`PUT /{id}/codes/{codeId}` nie zmienia tekstu ani typu kodu. W `DRAFT` może ustawić status; dla `FIXED` przepisuje trzy limity z DTO, więc pominięte limity stają się `null`. Poza `DRAFT`, w `ACTIVE` lub `DISABLED`, dopuszcza tylko wyłączenie kodu będącego `ACTIVE`, z `status=DISABLED` i bez pól limitów. Kod zarezerwowany lub użyty nie spełnia tego warunku.

Eksport to `text/csv` z nazwą `promotion-{id}-codes.csv`, nagłówkiem:

```text
code,status,code_type,max_redemptions,max_redemptions_per_customer,max_redemptions_per_day,reserved_redemptions_count,used_redemptions_count,reserved_until,created_at,updated_at,disabled_at
```

Separatorem jest przecinek. Wartości niepuste są cytowane, cudzysłowy podwajane, daty mają format `yyyy-MM-dd'T'HH:mm:ss` w domyślnej strefie JVM. Eksport dotyczy kampanii, nie pojedynczego batcha.

## 8. Pule promocyjne w HelloTicket

[PromotionTicketPoolSetupIRO](../src/main/java/pl/hellopoland/rest/dto/PromotionTicketPoolSetupIRO.java) zawiera `poolName`, `ticketName`, `ticketPrice`, `availableTicketsNumber`, `startDate`, `endDate`, `entryStartDate`, `entryEndDate`, `wholeDay`, `isCyclic`.

Wymagane są **dodatnia cena w groszach** i **dodatnia liczba biletów**. `ticketPrice=100` oznacza 1 PLN. Bezpłatny bilet (`0`) oraz liczba `-1` nie przechodzą tej walidacji. Jawnie podany koniec nie może poprzedzać jawnie podanego początku.

Domyślne daty: początek z `campaign.validFrom`, koniec z **`campaign.ticketValidTo`**. Koniec puli nie może przekroczyć `ticketValidTo`. Domyślnie `wholeDay=true`, `isCyclic=true`.

Dla cyklicznej puli całodniowej serwis ustawia początek/koniec pojedynczego wystąpienia na początek/koniec pierwszego dnia, a koniec codziennej serii na koniec dnia ważności. `frequencyType=DAILY`, `frequency=1`. Daty wejścia domyślnie pochodzą z tak wyliczonych dat puli. Nie jest to pojedynczy bilet obejmujący cały okres kampanii.

Backend używa portalu `Hello Ticket Cloud` i tokenu partnera danej oferty. W HT wyszukuje istniejący typ `SPECJALNY`, tworzy definicję biletu i pulę `PROMOTIONAL`, z `visibleForPartner=false` i `visibleOnPortal=false`. Brak nazwy daje `Bilet promocyjny - {nazwa kampanii}` / `Promocja - {nazwa kampanii}`.

Następnie odczytuje ATNA i zapisuje w relacji ID biletu, puli oraz status `CREATED`. Enum statusu obejmuje `NOT_REQUIRED`, `NOT_CREATED`, `CONFIG_REQUIRED`, `CREATED`, `ERROR`. Zwykłe dodanie relacji przygotowuje `NOT_CREATED` dla `TICKET`, a `NOT_REQUIRED` dla innych typów.

Generator pomija relację tylko wtedy, gdy ma `CREATED` **i** `hptAtnaId`; nie służy do aktualizacji gotowych pul. Zbiorcza edycja listy ofert może natomiast aktualizować istniejący bilet i pulę, zachowując pozostały limit.

Przy wyjątku serwis ustawia `ERROR` i ponownie rzuca wyjątek. Nie jest to gwarancja utrwalenia `ERROR` w bazie: transakcja HP może się wycofać, podczas gdy wcześniejsze operacje HTTP w HT już zaszły. Przed ponowieniem trzeba sprawdzić zasoby HT; nie obiecujemy atomowości HP+HT.

## 9. Przykładowe żądania administracyjne

ID i daty są przykładowe. Do testów należy użyć istniejących danych TST i okresu obejmującego chwilę testowania. Format dat odpowiada adapterowi minutowemu.

### Kampania TICKET przygotowywana etapami

`POST /helpdesk/promotions`:

```json
{
  "name": "Test promocji biletowej",
  "promotionType": "TICKET",
  "scopeType": "MANUAL",
  "status": "DRAFT",
  "validFrom": "2026-11-01T00:00",
  "validTo": "2026-11-30T23:59",
  "ticketValidTo": "2026-12-31T23:59",
  "globalLimit": 100,
  "codeLimit": 1,
  "requiredTicketQuantity": 1,
  "grantedTicketQuantity": 1,
  "targetSetup": {"sightEventIds": [411]}
}
```

Po odczytaniu ID kampanii dodać kody przez `POST /{id}/codes`:

```json
{"codes": ["TEST-PROMO-001"], "codeType": "ONE_TIME"}
```

Potem wykonać podgląd i generowanie z body:

```json
{
  "targetSetup": {"sightEventIds": [411]},
  "ticketPoolSetup": {
    "ticketPrice": 100,
    "availableTicketsNumber": 10,
    "wholeDay": true,
    "isCyclic": true
  }
}
```

Na końcu `PATCH /{id}/status` z `{"status":"ACTIVE"}`. Jeśli ma to być jedna operacja utworzenia, można do pierwszego JSON dodać `codeSetup` i `ticketPoolSetup` z powyższymi wartościami. Same `sightEvents` ani ręczne ID HT nie zastępują `targetSetup`.

### Procentowy rabat globalny

`POST /helpdesk/promotions`:

```json
{
  "name": "Test rabatu 10 procent",
  "promotionType": "PERCENT",
  "scopeType": "GLOBAL",
  "status": "DRAFT",
  "validFrom": "2026-11-01T00:00",
  "validTo": "2026-11-30T23:59",
  "discountPercent": 10,
  "codeSetup": {"fixedCode": "TEST-10", "maxRedemptions": 100}
}
```

Wariant `AMOUNT` wymaga `discountAmountGross` zamiast `discountPercent`, np. `1000` dla 10 PLN. Rabaty nie wymagają tworzenia pul `PROMOTIONAL`.

## 10. Walidacja w portalu i efekty

`POST /market/promotions/validate-code` przyjmuje [PromotionCodeValidationIRO](../src/main/java/pl/hellopoland/rest/dto/PromotionCodeValidationIRO.java):

```json
{
  "code": "TEST-PROMO-001",
  "cartItems": [{"id": 2055, "quantity": 1, "date": "2026-12-15T00:00"}]
}
```

`2055` jest przykładowym ATNA zwykłego biletu, nie ofertą HP. Backend odczytuje bilety z HT, mapuje ich `sightEventId` na `SightEvent.hptId` w HP i buduje kontekst koszyka. Nieznane ID, puste pozycje i ilości niedodatnie są pomijane.

Kolejność sprawdzeń: wymagany kod → dokładne wyszukanie i status kodu → `ACTIVE` oraz okres kampanii → limity → koszyk → aktywne relacje zakresu → efekt → **zapis rezerwacji**. To nie jest bezskutkowy podgląd. Powtórna poprawna walidacja może utworzyć kolejną rezerwację, a kod jednorazowy po pierwszej ma status `RESERVED`.

Odpowiedź [PromotionCodeValidationORO](../src/main/java/pl/hellopoland/rest/dto/PromotionCodeValidationORO.java) zawiera `status=VALID` albo `INVALID`. Dla sukcesu: `promotion` (`id`, `name`, `type`, `scope`), `effects` oraz `reservationToken`. Dla zwykłego odrzucenia: `errorCode` i `message`; takich odpowiedzi nie rozpoznajemy wyłącznie po statusie HTTP. Wyjątki infrastruktury mogą przerwać żądanie oddzielnie.

| Typ kampanii | Efekt | Dane |
| --- | --- | --- |
| `TICKET` | `ADD_TICKET` | `sourceCartItemId` (ATNA pozycji źródłowej), `sightEvent.id` HP, `item` z promocyjnym ATNA, ceną, ilością, typem biletu i pulą |
| `PERCENT` | `APPLY_PERCENT_DISCOUNT` | `discountPercent`; cel `CART` dla GLOBAL albo `ITEMS` z ATNA i ofertami HP |
| `AMOUNT` | `APPLY_AMOUNT_DISCOUNT` | `discountAmountGross`; analogiczny cel rabatu |

Dla `TICKET` ilość jest sumowana osobno dla pasującej oferty, w dniach nie późniejszych niż `ticketValidTo` (brak daty jest na tym etapie dopuszczony). Po osiągnięciu progu zwracane jest `grantedTicketQuantity` **raz dla oferty**, a nie wielokrotność wynikająca z liczby kupowanych biletów. Aktualna implementacja liczenia nie filtruje osobno typu „zwykły bilet”, mimo że taki jest zamysł komunikatu biznesowego. Nie należy traktować tego jako egzekwowanego ograniczenia typu biletu.

### Kody odrzucenia w walidacji

| `errorCode` | Znaczenie |
| --- | --- |
| `CODE_REQUIRED`, `CODE_NOT_FOUND` | Brak kodu / brak dokładnego dopasowania |
| `CODE_ALREADY_USED`, `CODE_RESERVED`, `CODE_DISABLED` | Niedostępny status kodu |
| `PROMOTION_NOT_ACTIVE`, `PROMOTION_NOT_STARTED`, `PROMOTION_EXPIRED` | Status lub czas kampanii |
| `PROMOTION_LIMIT_REACHED`, `CODE_LIMIT_REACHED` | Wyczerpany limit liczony z rezerwacji i użyć |
| `CART_REQUIRED` | Brak rozpoznanych pozycji dla zakresu innego niż GLOBAL |
| `PROMOTION_NOT_AVAILABLE_FOR_OFFER` | Brak pasującej aktywnej relacji oferty |
| `PROMOTION_POOL_NOT_READY`, `PROMOTION_TICKET_NOT_FOUND` | Brak gotowych powiązań / biletu promocyjnego |
| `PROMOTION_TICKET_DATE_EXCEEDED` | Pasujące pozycje z datami po granicznym dniu ważności |
| `PROMOTION_CONDITIONS_NOT_MET` | Niespełniony próg przyznania biletu |
| `INVALID_PROMOTION_CONFIGURATION` | Nieobsługiwany typ lub konfiguracja |

## 11. Rezerwacja, zamówienie i rabaty

Udana walidacja tworzy jedno redemption `RESERVED` z tokenem UUID, snapshotem kodu/nazwy/typu i rabatu oraz ważnością **30 minut**. Zwiększa liczniki rezerwacji kampanii i kodu. `ONE_TIME` zmienia status na `RESERVED`; `FIXED` pozostaje `ACTIVE`, a jego dostępność zależy od liczników i limitów.

Portal uwzględnia efekty w koszyku i przekazuje token w pełnym żądaniu `POST /market/orders`, jako opcjonalne **`promotionReservationToken`**. To inna nazwa pola niż `reservationToken` z walidacji. Dla `TICKET` zamówienie musi zawierać również bilet promocyjny; token sam go nie dodaje.

`attachReservationToOrder` sprawdza istnienie tokenu, status `RESERVED`, ważność i brak przypisania do innego zamówienia. Dla `TICKET` dopasowuje pozycję po definicji biletu i puli HT oraz kontroluje dzień wizyty względem `ticketValidTo`. Dla rabatów stosuje je po stronie backendu do kwalifikujących się pozycji zamówienia.

- `PERCENT`: procent od aktualnej ceny jednostkowej, zaokrąglenie `HALF_UP` do grosza, cena minimalna 0.
- `AMOUNT`: jedna kwota na kwalifikującą się sumę, maksymalnie do jej wysokości; serwis może rozdzielać pozycje zamówienia na jednostki z różnymi cenami. Nie jest to ta sama kwota odejmowana od każdego biletu.
- Dla nieglobalnych rabatów kwalifikacja pozycji zamówienia jest ponownie ustalana po aktualnych aktywnych relacjach kampanii.

Po `OrderService.confirm` (po wywołaniu potwierdzenia w zewnętrznym API) redemption przechodzi na `USED`, liczniki rezerwacji maleją, użyć rosną. `ONE_TIME` staje się `USED`; `FIXED` nie przechodzi na ten status. Anulowanie zamówienia zwalnia aktywne rezerwacje jako `CANCELLED`, ścieżka problemu zamówienia jako `RELEASED`. Obsługa ta dotyczy redemption będących nadal `RESERVED`; nie opisuje automatycznego cofania już wykorzystanej promocji.

`POST /market/promotions/release-reservation` z `{"reservationToken":"token-z-walidacji"}` zwalnia nieprzypisaną rezerwację jako `RELEASED`. Brak tokenu, brak rekordu lub przypisane zamówienie powodują brak działania.

Scheduler EJB uruchamia się co 10 minut i wygasza wyłącznie `RESERVED` bez zamówienia z przekroczonym `reservedUntil`. Ustawia `EXPIRED`, zmniejsza liczniki i przywraca jednorazowy kod z `RESERVED` do `ACTIVE`. Termin 30 minut nie oznacza natychmiastowego zwolnienia kodu: sprzątanie następuje przy kolejnym przebiegu schedulera.

Limity walidacji liczą `reserved + used`. Egzekwowane są `globalLimit` i limit kodu (`code.maxRedemptions`, a przy jego braku `campaign.codeLimit`). Pola klienta/dnia nie są używane do takich sprawdzeń. Wyszukanie kodu i redemption używa `PESSIMISTIC_WRITE`; nie jest to deklaracja osobnej blokady kampanii dla równoległego użycia różnych kodów.

Obecny zapis redemption wiąże dla `TICKET` pierwszą pasującą ofertę efektu i jedną dopasowaną pozycję zamówienia. Lista wielu efektów nie oznacza kompletnej historii wszystkich pozycji pod jednym tokenem; taki przypadek wymaga osobnego sprawdzenia integracyjnego.

## 12. Znacznik promocji i raportowanie

`markerTagId` to osobne powiązanie kampanii z tagiem, a nie to samo co `tagIds` zakresu. Można je ustawić pełnym zapisem kampanii lub `PATCH /{id}/marker-tag` z `{"markerTagId":12}`; `null` usuwa powiązanie.

`TagService.markPromotionalForActiveCampaigns` oznacza w odpowiedziach rynku tagi wskazane przez kampanie `ACTIVE` w okresie `validFrom..validTo`. Nie sprawdza dostępności kodów ani gotowości pul. Znacznik nie jest dowodem, że dana osoba może użyć kodu. To również osobny mechanizm od kolejności promowanych ofert (`SightEvent.promotion`).

Raport sprzedaży w `AnalyticsService` identyfikuje pozycję jako promocyjną, gdy ma rabat albo powiązane redemption; historyczne powiązanie jest brane pod uwagę także dla anulowanego zamówienia.

## 13. Schemat bazy i sprawdzenie działania

Skrypty w repozytorium:

- [add_promotions_and_codes.sql](../src/main/resources/sql/add_promotions_and_codes.sql) — podstawowe tabele, ograniczenia i indeksy;
- [add_promotion_ticket_valid_to.sql](../src/main/resources/sql/add_promotion_ticket_valid_to.sql) — uzupełnienie kolumny daty biletów, backfill do końca roku `validTo` dla istniejących TICKET bez daty i ograniczenia zakresu;
- [add_promotion_marker_tag.sql](../src/main/resources/sql/add_promotion_marker_tag.sql) — powiązanie znacznika kampanii.

Istnienie tych plików nie potwierdza ich wykonania. Przy zmianie aplikacji porównujemy schemat docelowy i wykonane migracje, zamiast uruchamiać skrypty ponownie bez przeglądu. W ramach aktualizacji dokumentacji nie wykonano SQL ani zmian aplikacji.

### Istniejące testy

[PromotionManagementServiceTest](../src/test/java/pl/hellopoland/service/PromotionManagementServiceTest.java) sprawdza m.in. daty pul, granicę ważności, dodatnią cenę oraz liczbę generatora. [PromotionCodeServiceTest](../src/test/java/pl/hellopoland/service/PromotionCodeServiceTest.java) sprawdza graniczny dzień wizyty. Nie są to pełne testy endpointów, migracji ani współpracy z rzeczywistym HT.

Z katalogu `hp-client-backend`, przy przygotowanym JDK 21, Maven i zależnościach, można uruchomić:

```sh
mvn -P tst -Dtest=PromotionManagementServiceTest,PromotionCodeServiceTest test
```

Nie uruchamiano tych testów w ramach aktualizacji tekstu. [promotion-ticket-flow.http](../src/test/resources/promotion-ticket-flow.http) to historyczny materiał ręczny: zawiera domyślny URL produkcyjny, stare daty i tworzenie MANUAL bez wymaganego `targetSetup`. Przed użyciem trzeba zmienić środowisko, dane oraz payload według tego dokumentu; nie jest gotowym scenariuszem do uruchomienia bez poprawek.

### Scenariusze akceptacyjne na przygotowanych danych TST

1. Utworzenie poprawnego DRAFT dla MANUAL/TAG/GLOBAL; odrzucenie TICKET+GLOBAL, braku `ticketValidTo` i końca ważności wcześniejszego od końca promocji.
2. Trzy tryby kodów, odrzucenie konfliktów źródeł i duplikatów, CSV, zastąpienie kodów przed historią i odmowa po powstaniu redemption.
3. Aktywacja bez kodów oraz z pulą brakującą dla jednej z kilku ofert — oczekiwane klucze błędów. Sukces dopiero po przygotowaniu wszystkich pul.
4. Podgląd zakresu bez zasobów HT; tworzenie, pominięcie gotowej puli przez generator i zbiorcza zmiana ofert także w ACTIVE. Osobno kontrola zamknięcia usuniętych pul i zachowania ich pozostałego limitu przy aktualizacji.
5. Walidacja TICKET dla właściwego ATNA, oferty i ilości; data wizyty w ostatnim dniu oraz po granicy; rezerwacja i ponowne użycie kodu jednorazowego.
6. Złożenie zamówienia z tokenem i biletem promocyjnym, brak wymaganej pozycji, przypisanie do innego zamówienia oraz wygasły token.
7. Potwierdzenie → USED, anulowanie/problem → zwolnienie RESERVED, porzucenie → EXPIRED po schedulerze; licznik kampanii i kodu po każdej operacji.
8. PERCENT i AMOUNT dla całego koszyka oraz wybranych ofert, ceny i sumy po stronie backendu; normalny zakup bez `promotionReservationToken`.
9. Widoczność pul `PROMOTIONAL` w portalach, znacznik tagu przed/w trakcie/po okresie kampanii i oznaczenie pozycji w raporcie sprzedaży.
10. Osobno: wiele ofert w koszyku, równoległe różne kody, nieskuteczne limity klienta/dnia i błąd HT w połowie operacji. Wynik tych prób trzeba odnotować; sam opis ani testy dat nie potwierdzają poprawności tych przypadków.

## 14. Rozstrzygnięcie wcześniejszych rozbieżności

| Wcześniejsze opisy | Rozstrzygnięcie na podstawie kodu |
| --- | --- |
| Dwie niezależne wersje opisu | Jeden dokument w `docs/promotions.md`; stare ścieżki kierują tutaj |
| Koniec puli z `validTo` albo `ticketValidTo` | Z `ticketValidTo`; to pole jest wymagane dla TICKET |
| Aktywacja przy co najmniej jednej gotowej ofercie | Gotowość wszystkich ofert wybranych przez resolver |
| Cały zakres edytowalny wyłącznie w DRAFT | Zbiorcza lista ofert i DELETE relacji działają także w ACTIVE; marker nie ma blokady statusu |
| Brak pełnej listy endpointów | Ujęto preview przed utworzeniem, preview pul, zbiorczy PUT ofert, marker, dodawanie/zastępowanie/eksport kodów |
| TAG stale dodaje aktualnie pasujące oferty | Zakres jest materializowany; jawny wybór i fallback mają określoną kolejność |
| Limity klienta/dnia jako dostępne zabezpieczenie | Pola istnieją, ale aktualna walidacja ich nie egzekwuje |
| Kod to bezskutkowe sprawdzenie, a ERROR jest zawsze zapisany | Sukces walidacji rezerwuje użycie; zapis ERROR może podlegać rollbackowi HP |
| Daty przykładów z sekundami i domyślną strefą domniemaną z innych funkcji | Adapter promocji używa minut i domyślnej strefy JVM |

Dalsze zmiany zachowania wymagają aktualizacji tego dokumentu razem z kodem. Potwierdzenie lokalnego kodu, testu TST i wdrożenia PROD zapisujemy jako osobne wyniki.
