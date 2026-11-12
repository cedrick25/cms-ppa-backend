package ppa.cms.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ppa.cms.service.AccountService;
import ppa.cms.service.UserRoleService;

@RestController
public class UserRoleController {

    private final UserRoleService roles;

    public UserRoleController(UserRoleService roles) {
        this.roles = roles;
    }

    @GetMapping("/api/user-roles")
    public Map<String, Object> list(Authentication authentication, HttpServletRequest request) {
        return roles.list(AccountService.current(authentication), request);
    }

    @GetMapping("/api/data-permissions")
    public Map<String, Object> permissions(Authentication authentication) {
        return roles.catalog(AccountService.current(authentication));
    }

    @GetMapping("/api/user-roles/{id}")
    public Map<String, Object> show(Authentication authentication, @PathVariable long id) {
        return roles.show(AccountService.current(authentication), id);
    }

    @PostMapping("/api/user-roles")
    public ResponseEntity<Map<String, Object>> create(Authentication authentication, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(roles.create(AccountService.current(authentication), body));
    }

    @PatchMapping("/api/user-roles/{id}")
    public Map<String, Object> update(Authentication authentication, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return roles.update(AccountService.current(authentication), id, body);
    }

    @DeleteMapping("/api/user-roles/{id}")
    public Map<String, Object> delete(Authentication authentication, @PathVariable long id) {
        return roles.delete(AccountService.current(authentication), id);
    }

    @PutMapping("/api/user-roles/{id}/permissions")
    public Map<String, Object> assign(Authentication authentication, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return roles.assign(AccountService.current(authentication), id, body);
    }
}
