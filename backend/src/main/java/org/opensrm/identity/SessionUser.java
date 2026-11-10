package org.opensrm.identity;

import java.io.Serializable;
import java.util.UUID;
import java.security.Principal;

/** Only identity and revocation version go into the JDBC session, never password hashes. */
public record SessionUser(UUID id, String login, long sessionVersion) implements Serializable, Principal {
    @Override public String getName() { return login; }
}
