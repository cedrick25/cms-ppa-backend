package ppa.cms.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ppa.cms.service.AccountService;

@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "name", "CMS-PPA",
                "message", "This service is a JSON API. Open the CMS-PPA frontend to sign in."
        );
    }

    @GetMapping("/up")
    public Map<String, Object> up() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/sign-in")
    public Map<String, Object> signIn(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return accounts.signIn(body, request);
    }

    @PostMapping("/api/sign-out")
    public Map<String, Object> signOut(Authentication authentication) {
        return accounts.signOut(AccountService.current(authentication), AccountService.currentToken(authentication));
    }

    @GetMapping("/api/user")
    public Map<String, Object> show(Authentication authentication) {
        return accounts.show(AccountService.current(authentication));
    }

    @PatchMapping("/api/user")
    public Map<String, Object> update(Authentication authentication, @RequestBody Map<String, Object> body) {
        return accounts.updateOwn(AccountService.current(authentication), AccountService.currentToken(authentication), body);
    }

    @GetMapping("/api/users")
    public Map<String, Object> users(Authentication authentication, HttpServletRequest request) {
        return accounts.list(AccountService.current(authentication), request);
    }

    @PostMapping("/api/users")
    public ResponseEntity<Map<String, Object>> register(Authentication authentication, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(accounts.register(AccountService.current(authentication), body));
    }

    @PatchMapping("/api/users/{id}")
    public Map<String, Object> updateUser(Authentication authentication, @org.springframework.web.bind.annotation.PathVariable long id, @RequestBody Map<String, Object> body) {
        return accounts.updateUser(AccountService.current(authentication), id, body);
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/users/{id}/password")
    public Map<String, Object> resetPassword(Authentication authentication, @org.springframework.web.bind.annotation.PathVariable long id, @RequestBody Map<String, Object> body) {
        return accounts.resetPassword(AccountService.current(authentication), id, body);
    }

    @PostMapping("/api/users/{id}/disable")
    public Map<String, Object> disable(Authentication authentication, @org.springframework.web.bind.annotation.PathVariable long id) {
        return accounts.disable(AccountService.current(authentication), id);
    }

    @PostMapping("/api/users/{id}/enable")
    public Map<String, Object> enable(Authentication authentication, @org.springframework.web.bind.annotation.PathVariable long id) {
        return accounts.enable(AccountService.current(authentication), id);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/users/{id}")
    public Map<String, Object> destroy(Authentication authentication, @org.springframework.web.bind.annotation.PathVariable long id, @RequestBody Map<String, Object> body) {
        return accounts.destroy(AccountService.current(authentication), id, body);
    }
}
