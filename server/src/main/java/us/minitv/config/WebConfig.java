package us.minitv.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import us.minitv.security.AuthArgumentResolvers;

import java.util.ArrayList;
import java.util.List;

/** CORS and authentication argument resolvers. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final List<String> DEFAULT_ORIGINS = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174",
            // H5 dev server. Python's CORSMiddleware only omitted headers for
            // unknown origins, so 5175 used to work through the Vite proxy
            // (same-origin in the browser). Spring rejects with "Invalid CORS
            // request" instead, and POST /v1/playback therefore 403'd.
            "http://localhost:5175",
            "http://127.0.0.1:5175",
            "https://minitv.us",
            "https://www.minitv.us",
            "https://admin.minitv.us",
            "https://h5.minitv.us");

    /**
     * Extra origins accepted only when APP_ENV is local/dev/test: phone access
     * to a local dev server arrives with the LAN IP / tunnel hostname as Origin
     * (browsers send Origin on POST, which the Vite proxy forwards). Production
     * keeps the explicit allow-list above.
     */
    private static final List<String> LOCAL_ORIGIN_PATTERNS = List.of(
            "http://localhost:[*]",
            "http://127.0.0.1:[*]",
            "http://192.168.*:[*]",
            "http://10.*:[*]",
            "http://172.*:[*]",
            // Tailscale / CGNAT addresses
            "http://100.*:[*]",
            // Common quick tunnels used for phone testing
            "https://*.trycloudflare.com",
            "https://*.loca.lt",
            "https://*.ngrok-free.app",
            "https://*.ngrok-free.dev",
            "https://*.ngrok.io");

    @Value("${APP_ENV:local}")
    private String appEnv;

    @Value("${CORS_ORIGINS:}")
    private String extraOrigins;

    private final AuthArgumentResolvers.UserResolver userResolver;
    private final AuthArgumentResolvers.StaffResolver staffResolver;
    private final AuthArgumentResolvers.PartnerResolver partnerResolver;

    public WebConfig(AuthArgumentResolvers.UserResolver userResolver,
                     AuthArgumentResolvers.StaffResolver staffResolver,
                     AuthArgumentResolvers.PartnerResolver partnerResolver) {
        this.userResolver = userResolver;
        this.staffResolver = staffResolver;
        this.partnerResolver = partnerResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = new ArrayList<>(DEFAULT_ORIGINS);
        if (extraOrigins != null) {
            for (String item : extraOrigins.split(",")) {
                String origin = item.trim();
                if (origin.endsWith("/")) {
                    origin = origin.substring(0, origin.length() - 1);
                }
                if (!origin.isEmpty() && !origins.contains(origin)) {
                    origins.add(origin);
                }
            }
        }
        CorsRegistration registration = registry.addMapping("/**")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
        if (isLocalEnvironment()) {
            List<String> patterns = new ArrayList<>(origins);
            patterns.addAll(LOCAL_ORIGIN_PATTERNS);
            registration.allowedOriginPatterns(patterns.toArray(String[]::new));
        } else {
            registration.allowedOrigins(origins.toArray(String[]::new));
        }
    }

    private boolean isLocalEnvironment() {
        String value = appEnv == null ? "" : appEnv.trim().toLowerCase();
        return value.isEmpty() || "local".equals(value) || "dev".equals(value) || "test".equals(value);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userResolver);
        resolvers.add(staffResolver);
        resolvers.add(partnerResolver);
    }
}
