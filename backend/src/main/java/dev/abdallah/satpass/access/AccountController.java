package dev.abdallah.satpass.access;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/account/api")
@ConditionalOnProperty(name = "account.enabled", havingValue = "true")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfToken> csrf(CsrfToken token) { return privateResponse(token); }

    @GetMapping("/me")
    public ResponseEntity<AccountService.Dashboard> me(@AuthenticationPrincipal OAuth2User user) {
        return privateResponse(accounts.dashboard(user.getName()));
    }

    @PostMapping("/key")
    public ResponseEntity<AccessService.IssuedKey> regenerate(@AuthenticationPrincipal OAuth2User user) {
        return privateResponse(accounts.regenerate(user.getName()));
    }

    @DeleteMapping("/key")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal OAuth2User user) {
        accounts.revoke(user.getName());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public void unavailable(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        AccountSecurityConfiguration.problem(response, 503);
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
