package com.devdesk.agent;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SecurityApiController {

    @GetMapping("/me")
    public UserInfo currentUser(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new UserInfo(authentication.getName(), roles);
    }

    /** Loads a fresh SPA token and causes CookieCsrfTokenRepository to set its readable cookie. */
    @GetMapping("/csrf")
    public CsrfInfo csrf(CsrfToken csrfToken) {
        return new CsrfInfo(csrfToken.getToken(), csrfToken.getHeaderName());
    }

    public record UserInfo(String username, List<String> roles) {}

    public record CsrfInfo(String token, String headerName) {}
}
