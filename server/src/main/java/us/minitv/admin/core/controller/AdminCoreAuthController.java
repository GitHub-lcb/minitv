package us.minitv.admin.core.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.admin.core.AdminCoreSupport;
import us.minitv.admin.core.service.AdminCoreSessionService;
import us.minitv.security.CurrentStaff;
import us.minitv.security.StaffContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** /admin/v1/auth - staff login, identity, logout and self-service password change. */
@RestController
public class AdminCoreAuthController {

    public record LoginIn(String username, String password) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PasswordChangeIn(String oldPassword, String newPassword) {
    }

    private final AdminCoreSessionService sessions;

    public AdminCoreAuthController(AdminCoreSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/admin/v1/auth/login")
    public Map<String, Object> login(@RequestBody LoginIn body) {
        AdminCoreSupport.required("username", body.username());
        AdminCoreSupport.required("password", body.password());
        return sessions.login(body.username(), body.password());
    }

    @GetMapping("/admin/v1/auth/me")
    public Map<String, Object> me(@CurrentStaff StaffContext staff) {
        return AdminCoreSessionService.adminUserOut(staff.user());
    }

    @PostMapping("/admin/v1/auth/logout")
    public Map<String, Object> logout(@CurrentStaff StaffContext staff) {
        sessions.logout(staff.token());
        return new LinkedHashMap<>(Map.of("ok", Boolean.TRUE));
    }

    @PostMapping("/admin/v1/auth/password")
    public Map<String, Object> changePassword(@CurrentStaff StaffContext staff,
                                              @RequestBody PasswordChangeIn body) {
        // Every session of this account is revoked on success - including this one.
        return sessions.changePassword(staff.user(), body.oldPassword(), body.newPassword());
    }
}
