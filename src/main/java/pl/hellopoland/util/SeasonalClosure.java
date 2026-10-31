package pl.hellopoland.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import pl.hellopoland.exception.badrequest.BadRequestException;

/** Informational status only; never changes accessibility or ticket availability. */
public final class SeasonalClosure {
  private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

  private SeasonalClosure() {}

  public static LocalDate parse(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("ISO date", value, 0);
      LocalDate date = LocalDate.parse(value);
      if (date.getYear() < 1) throw new DateTimeParseException("Positive year", value, 0);
      return date;
    } catch (DateTimeParseException e) {
      throw new BadRequestException("Podaj poprawną datę zamknięcia w formacie RRRR-MM-DD.");
    }
  }

  public static void validate(Boolean enabled, String from, String to) {
    if (enabled == null) return; // Payloads from clients without this feature.
    LocalDate start = parse(from);
    LocalDate end = parse(to);
    if (start != null && end != null && end.isBefore(start)) {
      throw new BadRequestException("Data końca zamknięcia nie może być wcześniejsza niż data początku.");
    }
  }

  public static String format(LocalDate date) {
    return date == null ? null : date.toString();
  }

  public static boolean isActive(boolean enabled, LocalDate from, LocalDate to) {
    return isActive(enabled, from, to, Clock.system(ZONE));
  }

  static boolean isActive(boolean enabled, LocalDate from, LocalDate to, Clock clock) {
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    return enabled && (from == null || !today.isBefore(from)) && (to == null || !today.isAfter(to));
  }
}
