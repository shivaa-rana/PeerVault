package com.coreissuer.api.security;

import com.coreissuer.common.domain.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

public final class AuthenticatedUser implements UserDetails {
    private final UUID id;
    private final String email;
    private final UserRole role;

    public AuthenticatedUser(UUID id, String email, UserRole role) {
        this.id = id;
        this.email = email;
        this.role = role;
    }

    public UUID id() { return id; }
    public String email() { return email; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
