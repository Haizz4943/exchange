package com.haizz.exchange.auth.api;

import com.haizz.exchange.auth.domain.exception.SsoNotEnabledException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SSO endpoint — stubbed for Stage 2 activation.
 * Returns 501 until auth.sso.enabled=true and OidcIdentityProvider is wired in.
 */
@RestController
@RequestMapping("/auth/sso")
@RequiredArgsConstructor
public class SsoController {

    @PostMapping("/exchange")
    public ResponseEntity<?> exchangeToken(@RequestBody Map<String, String> body) {
        throw new SsoNotEnabledException();
    }
}
