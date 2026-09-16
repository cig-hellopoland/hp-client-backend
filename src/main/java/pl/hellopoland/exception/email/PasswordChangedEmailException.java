package pl.hellopoland.exception.email;

import jakarta.ejb.ApplicationException;
import pl.hellopoland.exception.conflict.ConflictingException;

/** The credentials may already be synchronized with the external ticket system. */
@ApplicationException(rollback = false)
public class PasswordChangedEmailException extends ConflictingException {
  public PasswordChangedEmailException() {
    super("Dane logowania zostały zmienione, ale nie udało się wysłać wiadomości "
        + "do użytkownika lub kopii do HelpDesku. Skontaktuj się z HelpDeskiem.");
  }
}
