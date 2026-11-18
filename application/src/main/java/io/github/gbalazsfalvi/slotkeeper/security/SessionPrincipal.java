package io.github.gbalazsfalvi.slotkeeper.security;

import java.io.Serial;
import java.util.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Session identity only: workspace authority is deliberately absent and always read from the DB.
 */
public final class SessionPrincipal implements UserDetails, CredentialsContainer {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Stable synthetic user identity.
   *
   * @serial authenticated subject identity
   */
  private final UUID id;

  /**
   * Login handle.
   *
   * @serial local synthetic login handle
   */
  private final String username;

  /**
   * Public display label.
   *
   * @serial synthetic display label
   */
  private final String displayName;

  // Credential material cannot survive Java session serialization, even before credential erasure.
  private transient String passwordHash;

  /**
   * Creates a login-time identity; the provider erases the temporary password hash after
   * verification.
   *
   * @param id immutable subject
   * @param username local handle
   * @param displayName synthetic display label
   * @param passwordHash ephemeral encoded credential
   */
  public SessionPrincipal(UUID id, String username, String displayName, String passwordHash) {
    this.id = id;
    this.username = username;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
  }

  /**
   * Returns the immutable authenticated subject identity.
   *
   * @return immutable subject identity
   */
  public UUID id() {
    return id;
  }

  /**
   * Returns the safe synthetic display label.
   *
   * @return public synthetic label
   */
  public String displayName() {
    return displayName;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Override
  public void eraseCredentials() {
    passwordHash = null;
  }
}
