package us.minitv.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.security.PasswordHasher;
import us.minitv.security.Tokens;

/**
 * Creates the first back-office account, matching {@code app/seed_admin.py}.
 *
 * Runs only when {@code SEED_ADMIN_RUN=1}, so a normal boot is untouched. The
 * script is safe to re-run: it only acts while {@code admin_users} is empty.
 * With {@code --spring.main.web-application-type=none} the process exits right
 * after seeding, which is what deploy_server.sh uses.
 */
@Component
public class SeedAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedAdminRunner.class);

    private final SeedAdminMapper mapper;

    public SeedAdminRunner(SeedAdminMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"1".equals(us.minitv.common.Env.get("SEED_ADMIN_RUN", ""))) {
            return;
        }
        long existing = mapper.countAdmins();
        if (existing > 0) {
            log.info("skip: admin_users already has {} account(s)", existing);
            return;
        }
        String username = env("SEED_ADMIN_USERNAME", "admin").trim().toLowerCase();
        if (username.isEmpty()) {
            username = "admin";
        }
        String password = env("SEED_ADMIN_PASSWORD", "");
        boolean generated = password.isEmpty();
        if (generated) {
            password = Tokens.urlSafe(18);
        }
        AdminUserRow row = new AdminUserRow();
        row.setUsername(username);
        row.setPasswordHash(PasswordHasher.hash(password));
        row.setDisplayName("管理员");
        row.setRole("admin");
        mapper.insert(row);
        log.info("created: {} (role=admin)", username);
        if (generated) {
            log.info("initial password (shown once): {}", password);
        }
    }

    private static String env(String key, String fallback) {
        String value = us.minitv.common.Env.get(key);
        return value == null ? fallback : value;
    }
}
