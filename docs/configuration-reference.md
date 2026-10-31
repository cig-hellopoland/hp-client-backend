# Zależności i klucze konfiguracji

Inwentaryzacja lokalnych źródeł: **2026-10-31**. Ostatni commit spoza dokumentacji: `9201f2fe`; uwzględniono również bieżące pliki robocze.

Plik generowany przez `hellopoland-docs/scripts/build-technical-reference.py`. Opis architektury i ograniczeń: [technical.md](technical.md). Wykaz nie potwierdza wdrożenia ani wykonania testów.

| Bezpośrednia zależność Maven | Wersja / wyrażenie POM | Scope |
| --- | --- | --- |
| `org.hibernate:hibernate-core` | 6.6.2.Final | provided |
| `org.jboss.ws.cxf:jbossws-cxf-client` | 5.2.4.Final | compile |
| `jakarta.platform:jakarta.jakartaee-api` | 10.0.0 | provided |
| `org.eclipse.angus:angus-mail` | 2.0.3 | provided |
| `org.jboss.resteasy:resteasy-multipart-provider` | 6.2.10.Final | provided |
| `pl.hellopoland:hellopoland-dto` | 1.3.19 | compile |
| `org.sejda.imageio:webp-imageio` | 0.1.6 | compile |
| `io.jsonwebtoken:jjwt-api` | 0.12.6 | compile |
| `io.jsonwebtoken:jjwt-impl` | 0.12.6 | compile |
| `io.jsonwebtoken:jjwt-jackson` | 0.12.6 | compile |
| `com.google.api-client:google-api-client` | 1.30.2 | compile |
| `junit:junit` | 4.12 | test |
| `io.swagger.core.v3:swagger-jaxrs2-jakarta` | 2.2.26 | compile |
| `io.swagger.core.v3:swagger-jaxrs2-servlet-initializer-jakarta` | 2.2.26 | compile |
| `commons-io:commons-io` | 2.16.1 | compile |
| `org.postgresql:postgresql` | 42.7.3 | runtime |
| `com.sendinblue:sib-api-v3-sdk` | 3.0.1 | compile |
| `org.apache.xmlgraphics:batik-transcoder` | 1.17 | compile |
| `org.apache.xmlgraphics:batik-codec` | 1.17 | compile |

## Klucze plików properties

Poniżej wyłącznie nazwy kluczy, bez wartości. Lista wskazuje deklaracje w zasobach, nie gwarantuje użycia każdego klucza ani kompletności zewnętrznego `local.runtime.properties`. Klucze filtrowania Maven i właściwości runtime nie są zamienne.

### maven.build.prod.properties

| Klucz | Deklaracja |
| --- | --- |
| `hibernate.hbm2ddl.auto` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L1) |
| `hibernate.show.sql` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L2) |
| `hibernate.function_contributors` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L3) |
| `jakarta.persistence.jdbc.host` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L4) |
| `jakarta.persistence.jdbc.port` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L5) |
| `jakarta.persistence.jdbc.database` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L6) |
| `jakarta.persistence.jdbc.user` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L7) |
| `jakarta.persistence.jdbc.password` | [maven.build.prod.properties](../src/main/resources/maven.build.prod.properties#L8) |

### maven.build.properties

| Klucz | Deklaracja |
| --- | --- |
| `hibernate.hbm2ddl.auto` | [maven.build.properties](../src/main/resources/maven.build.properties#L1) |
| `hibernate.show.sql` | [maven.build.properties](../src/main/resources/maven.build.properties#L2) |
| `hibernate.function_contributors` | [maven.build.properties](../src/main/resources/maven.build.properties#L3) |
| `jakarta.persistence.jdbc.host` | [maven.build.properties](../src/main/resources/maven.build.properties#L4) |
| `jakarta.persistence.jdbc.port` | [maven.build.properties](../src/main/resources/maven.build.properties#L5) |
| `jakarta.persistence.jdbc.database` | [maven.build.properties](../src/main/resources/maven.build.properties#L6) |
| `jakarta.persistence.jdbc.user` | [maven.build.properties](../src/main/resources/maven.build.properties#L7) |
| `jakarta.persistence.jdbc.password` | [maven.build.properties](../src/main/resources/maven.build.properties#L8) |

### maven.build.tst.properties

| Klucz | Deklaracja |
| --- | --- |
| `hibernate.hbm2ddl.auto` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L1) |
| `hibernate.show.sql` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L2) |
| `hibernate.function_contributors` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L3) |
| `jakarta.persistence.jdbc.host` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L4) |
| `jakarta.persistence.jdbc.port` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L5) |
| `jakarta.persistence.jdbc.database` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L6) |
| `jakarta.persistence.jdbc.user` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L7) |
| `jakarta.persistence.jdbc.password` | [maven.build.tst.properties](../src/main/resources/maven.build.tst.properties#L8) |

### runtime.properties

| Klucz | Deklaracja |
| --- | --- |
| `db.filler.run` | [runtime.properties](../src/main/resources/runtime.properties#L1) |
| `resteasy.preferJacksonOverJsonB` | [runtime.properties](../src/main/resources/runtime.properties#L3) |
| `mail.smtp.enabled` | [runtime.properties](../src/main/resources/runtime.properties#L4) |
| `mail.username` | [runtime.properties](../src/main/resources/runtime.properties#L5) |
| `mail.password` | [runtime.properties](../src/main/resources/runtime.properties#L6) |
| `mail.smtp.host` | [runtime.properties](../src/main/resources/runtime.properties#L7) |
| `mail.smtp.port` | [runtime.properties](../src/main/resources/runtime.properties#L8) |
| `mail.smtp.auth` | [runtime.properties](../src/main/resources/runtime.properties#L9) |
| `mail.smtp.starttls.enable` | [runtime.properties](../src/main/resources/runtime.properties#L10) |
| `tpay.apiUrl` | [runtime.properties](../src/main/resources/runtime.properties#L23) |
| `tpay.issuer` | [runtime.properties](../src/main/resources/runtime.properties#L24) |
| `tpay.certificate.url` | [runtime.properties](../src/main/resources/runtime.properties#L25) |
| `tpay.certificate.root.url` | [runtime.properties](../src/main/resources/runtime.properties#L26) |
| `tpay.clientId` | [runtime.properties](../src/main/resources/runtime.properties#L27) |
| `tpay.clientSecret` | [runtime.properties](../src/main/resources/runtime.properties#L28) |
| `tpay.redirectUrl` | [runtime.properties](../src/main/resources/runtime.properties#L29) |
| `tpay.notification.email` | [runtime.properties](../src/main/resources/runtime.properties#L30) |
| `tpay.notificationUrl` | [runtime.properties](../src/main/resources/runtime.properties#L31) |
| `jwt.access.token.validity.millis` | [runtime.properties](../src/main/resources/runtime.properties#L33) |
| `jwt.refresh.token.validity.millis` | [runtime.properties](../src/main/resources/runtime.properties#L34) |
| `jwt.access.token.secret.key` | [runtime.properties](../src/main/resources/runtime.properties#L35) |
| `jwt.refresh.token.secret.key` | [runtime.properties](../src/main/resources/runtime.properties#L36) |
| `base.url` | [runtime.properties](../src/main/resources/runtime.properties#L39) |
| `hpt.cloud.url` | [runtime.properties](../src/main/resources/runtime.properties#L40) |
| `hpt.helpdesk.token` | [runtime.properties](../src/main/resources/runtime.properties#L41) |
| `FB.fanpage.id` | [runtime.properties](../src/main/resources/runtime.properties#L43) |
| `FB.page.access.token` | [runtime.properties](../src/main/resources/runtime.properties#L44) |
| `FB.posts.fields.query.param` | [runtime.properties](../src/main/resources/runtime.properties#L45) |
| `mail.hellopoland.biuro` | [runtime.properties](../src/main/resources/runtime.properties#L47) |
| `mail.partner.credentials.recipient` | [runtime.properties](../src/main/resources/runtime.properties#L48) |
| `sendInBlue.secret.key` | [runtime.properties](../src/main/resources/runtime.properties#L50) |
| `sendInBlue.list.id` | [runtime.properties](../src/main/resources/runtime.properties#L51) |
| `sendInBlue.confirmation.template.id` | [runtime.properties](../src/main/resources/runtime.properties#L52) |
| `dms.root.path` | [runtime.properties](../src/main/resources/runtime.properties#L54) |
| `password.reset.token.ttl.minutes` | [runtime.properties](../src/main/resources/runtime.properties#L56) |
| `portal.url` | [runtime.properties](../src/main/resources/runtime.properties#L57) |
| `helpdesk.portal.url` | [runtime.properties](../src/main/resources/runtime.properties#L58) |
| `password.reset.mail.subject` | [runtime.properties](../src/main/resources/runtime.properties#L59) |
| `password.reset.mail.body` | [runtime.properties](../src/main/resources/runtime.properties#L60) |
| `ops.agent.base.url` | [runtime.properties](../src/main/resources/runtime.properties#L63) |
| `ops.agent.shared.secret` | [runtime.properties](../src/main/resources/runtime.properties#L64) |
| `email.verification.mail.subject` | [runtime.properties](../src/main/resources/runtime.properties#L65) |
| `email.verification.mail.body` | [runtime.properties](../src/main/resources/runtime.properties#L66) |
| `email.verification.token.ttl.minutes` | [runtime.properties](../src/main/resources/runtime.properties#L67) |

## Skrypty SQL

To pliki obecne w repozytorium, a nie automatyczny plan migracji. Kolejność i stan wykonania wymagają sprawdzenia dla konkretnego wdrożenia.

| Plik |
| --- |
| [add_category_display_order.sql](../src/main/resources/sql/add_category_display_order.sql) |
| [add_helpdesk_technical_operator.sql](../src/main/resources/sql/add_helpdesk_technical_operator.sql) |
| [add_partner_user_allowed_sights.sql](../src/main/resources/sql/add_partner_user_allowed_sights.sql) |
| [add_promotion_marker_tag.sql](../src/main/resources/sql/add_promotion_marker_tag.sql) |
| [add_promotion_ticket_valid_to.sql](../src/main/resources/sql/add_promotion_ticket_valid_to.sql) |
| [add_promotions_and_codes.sql](../src/main/resources/sql/add_promotions_and_codes.sql) |
| [add_seasonal_closure.sql](../src/main/resources/sql/add_seasonal_closure.sql) |
| [add_sight_google_place_id.sql](../src/main/resources/sql/add_sight_google_place_id.sql) |
| [fix_sightevent_partner_consistency.sql](../src/main/resources/sql/fix_sightevent_partner_consistency.sql) |
| [normalize_voivodeship_lowercase.sql](../src/main/resources/sql/normalize_voivodeship_lowercase.sql) |
