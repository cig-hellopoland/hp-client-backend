# Changelog — Hello Poland Backend

Każda zmiana numeru `<version>` w `pom.xml` wymaga dodania odpowiadającej jej sekcji
w tym pliku. Zmiany przygotowywane do następnego wydania zapisujemy w sekcji
`Unreleased`, a podczas podnoszenia wersji przenosimy je pod numer zgodny z POM-em.

## Unreleased

- Dodano informacyjne zamknięcie sezonowe atrakcji i ofert: przełącznik, opcjonalne daty od/do oraz status aktywności liczony według czasu `Europe/Warsaw`, z uwzględnieniem dnia końcowego. Komunikat nie blokuje sprzedaży biletów.
- Dodano walidację dat, zachowanie pól zamknięcia przy synchronizacji ofert z Hello Ticket oraz zgodność z klientami, które jeszcze nie przesyłają tych pól.
- Podniesiono zależność `hellopoland-dto` do `1.3.19`. Przed uruchomieniem backendu należy wykonać migrację `src/main/resources/sql/add_seasonal_closure.sql` dla tabel `sight` i `sightevent`.
- Dodano testy dat zamknięcia sezonowego i zachowania tych danych podczas tworzenia oraz aktualizacji ofert.

- CSV ze Statystyk sprzedaży ma wspólny układ 12 kolumn od numeru zamówienia do statusu; Partner otrzymuje dokładnie te kolumny, a Helpdesk dodatkowo dotychczasowe pola administracyjne i prowizję na końcu.
- Dodano cenę brutto jednej sztuki po rabacie i jednoznaczne nagłówki liczby oraz wartości sprzedanych biletów; kwoty zapisujemy z dwoma miejscami po przecinku.
- CSV zapisujemy jako UTF-8 z BOM dla polskich znaków w Excelu; poprawiono literówki w nagłówkach użytkownika i telefonu.

- Raport CSV Partnera pomija ID i nazwę partnera, afiliację, telefon, platformę, zalogowanie oraz fakturę; Helpdesk zachowuje pełny zestaw kolumn.
- Raporty CSV pobierane ze Statystyk sprzedaży Partnera i Helpdesku rozpoznają promocję przy konkretnej pozycji na podstawie rabatu lub powiązania z kodem promocyjnym, także dla biletów z ceną promocyjną bez rabatu i zamówień anulowanych.

- Eksport CSV sprzedaży Partnera i Helpdesku zawiera polski status zamówienia przy każdej pozycji biletu, również dla anulowanych zamówień.

- Raport ostatniej sprzedaży Helpdesku zwraca wartość całego zamówienia po rabatach.

- Raport ostatniej sprzedaży Helpdesku zwraca nazwę biletu i liczbę biletów z pozycji zamówienia.

## 2.0.20.24 — 2026-09-30

### Naprawiono

- Poprawiono składnię HQL pobierania promowanych ofert w Helpdesku; błąd
  parsera Hibernate blokował odświeżanie listy i przyciski zarządzania promocjami.
- Dodano testy rzeczywistego parsera Hibernate dla zapytania listy promowanych
  ofert, również z ograniczeniem dostępu do partnerów, oraz kontrolę wiązania parametrów.

## 2.0.20.23 — 2026-09-30

### Naprawiono

- Dodawanie promowanej oferty dopisuje ją na końcu listy, bez zastępowania
  innej oferty; backend odrzuca dziewiątą pozycję i bezpiecznie obsługuje ponowienie dodania.
- Kolejność całej listy jest zapisywana w jednej transakcji, z zachowaniem
  unikalności pozycji i blokadą równoczesnych zmian; nieaktualna lista jest odrzucana.
- Usunięcie promocji porządkuje pozostałe pozycje, a stary endpoint pozycji
  przesuwa ofertę zamiast usuwać promocję innej oferty.
- Osobna lista promowanych ofert w Helpdesku uwzględnia również nieaktywne
  oferty i zachowuje dotychczasowe ograniczenia dostępu użytkownika.

### Dodano

- Endpointy dodawania promocji, odczytu listy promowanych ofert i zbiorczego
  zapisu kolejności oraz testy zachowania listy, limitu ośmiu i nieprawidłowych zmian.

## 2.0.20.22 — 2026-09-28

### Zmieniono

- Rozszerzono zakres pozycji promowanych ofert w Helpdesku z 1–3 do 1–8;
  wartości spoza zakresu są odrzucane.
- Endpoint portalu `/market/sight-events/promoted` zwraca do ośmiu ofert
  w kolejności ustalonej w Helpdesku.

### Dodano

- Trzy testy regresyjne akceptacji pozycji 1–8, odrzucania nieprawidłowych
  pozycji oraz zakresu i kolejności ofert pobieranych przez portal.

## 2.0.20.21 — 2026-09-16

### Naprawiono

- Przywrócono obsługę `tr_status` udanej płatności Tpay niezależnie od wielkości liter:
  sandboxowe `TRUE` jest akceptowane tak samo jak `true`, zamiast pozostawiać zamówienie
  w statusie `NEW`; ta sama interpretacja jest używana podczas walidacji i potwierdzania.

### Dodano

- Test regresyjny powiadomienia z `tr_status=TRUE` oraz ponowień ze zmienioną wielkością
  liter, sprawdzający odpowiedź `TRUE` i jednokrotne potwierdzenie zamówienia.

## 2.0.20.20 — 2026-09-15

### Dodano

- Tymczasowy log diagnostyczny powiadomień Tpay do testów na TST: po poprawnej
  weryfikacji JWS zapisuje `orderHash`, `tr_crc`, `tr_status` i `tr_id` jako JSON,
  bez pełnej treści powiadomienia i danych płatnika; reguły walidacji pozostają bez zmian.

## 2.0.20.19 — 2026-09-15

### Naprawiono

- Zmiana loginu partnera w HelpDesku i zmiany hasła użytkownika wysyłają nowe dane
  na login użytkownika, z kopią DW na `mail.partner.credentials.recipient`.
- Powiadomienia obejmują zmianę własnego hasła, zmianę przez HelpDesk lub panel
  partnera oraz zatwierdzony reset hasła przez link.
- Przekierowanie poczty na TST obejmuje również kopię DW i usuwa duplikat odbiorcy.
- Błąd SMTP podczas powiadamiania o zmianie danych jest zgłaszany użytkownikowi;
  treść wiadomości z hasłem nie trafia do logu przy wyłączonym SMTP.

### Dodano

- 17 testów regresyjnych zmian danych logowania, odbiorców wiadomości,
  kopii DW, przekierowania na TST i błędów wysyłki.

## 2.0.20.18 — 2026-09-14

### Naprawiono

- Ujednolicono i poprawiono polskie komunikaty błędów zwracane podczas dodawania partnera.
- Komunikat błędu wysyłki danych dostępowych informuje, że partner nie został utworzony.
- Endpoint powiadomień Tpay `ackPayment` zwraca teraz `200`, `text/plain` i dokładnie
  `TRUE` dopiero po poprawnym zakończeniu transakcji bazodanowej; odrzucone powiadomienia
  i błędy przetwarzania zwracają `500` i `FALSE`.
- Ponowienia płatności dla zamówienia `CONFIRMED` nie uruchamiają ponownie potwierdzania
  w systemie biletowym; blokada rekordu i ponowny odczyt statusu zabezpieczają równoczesne powiadomienia.
- Przed obsługą powiadomienia sprawdzane są podpis JWS, zgodność `tr_crc` z zamówieniem,
  zgodność `tr_id` z zapisaną transakcją oraz obsługiwany status płatności.

### Dodano

- 14 testów regresyjnych powiadomień Tpay obejmujących odpowiedzi HTTP, ponowienia,
  walidację oraz błędy systemu biletowego i bazy danych.

## 2.0.20.17 — 2026-09-02

### Dodano

- Opcjonalne globalne przekierowanie wiadomości przez `mail.redirect.all.to`, używane na TST.
- Konfigurowalny techniczny odbiorca danych dostępowych partnerów w
  `mail.partner.credentials.recipient`.

### Zmieniono

- Wiadomości z wygenerowanymi i resetowanymi hasłami partnerów trafiają do skrzynki
  technicznej, bez zmiany właściwego loginu partnera.
- Domyślne techniczne adresy pocztowe wskazują skrzynki w domenie `hello-poland.pl`.

## 2.0.20.16 — 2026-09-01

### Zmieniono

- Dane raportu „Ostatnia sprzedaż” zwracają cenę kupionego biletu zamiast danych partnera.

### Naprawiono

- Pobieranie raportu sprzedaży CSV, które kończyło się błędem `401 Unauthorized` z powodu
  ponownej autoryzacji wywołania w warstwie EJB.
- Generowanie raportu sprzedaży dla starszych promocji bez określonego udziału Hello Poland
  w finansowaniu rabatu.

## 2.0.20.15 — 2026-08-30

### Dodano

- Synchronizację nazwy i adresu e-mail partnera z Hello Poland do Hello Ticket.
- Synchronizację loginu głównego konta partnera w HP i HT podczas edycji danych partnera.
- Obsługę daty ważności biletów wydawanych w kampanii promocyjnej (`ticketValidTo`).
- Rozpoznawanie zamówień pochodzących z widgetu i właściwy adres powrotu z płatności TPay.

### Zmieniono

- Walidację kodów promocyjnych z uwzględnieniem dat biletów w koszyku oraz czytelniejszymi
  komunikatami o niespełnionych warunkach promocji.
- Walidację dat i konfiguracji cyklicznych pul biletowych oraz komunikaty zwracane do HelpDesku.
- Komunikaty błędów rezerwacji biletów zwracane użytkownikowi.

### Naprawiono

- Rozjazd adresu powiadomień partnera pomiędzy bazami HP i HT po zmianie loginu partnera.
- Wybór głównego konta partnera, gdy partner posiada więcej niż jednego użytkownika z rolą `PARTNER`.
- Jednoczesne filtrowanie wydarzeń po kategoriach i tagach dzięki rozdzieleniu nazw parametrów zapytania.
