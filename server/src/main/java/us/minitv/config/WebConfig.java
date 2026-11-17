package us.minitv.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
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
            "https://minitv.us",
            "https://www.minitv.us",
            "https://admin.minitv.us",
            "https://h5.minitv.us");

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
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userResolver);
        resolvers.add(staffResolver);
        resolvers.add(partnerResolver);
    }
}
