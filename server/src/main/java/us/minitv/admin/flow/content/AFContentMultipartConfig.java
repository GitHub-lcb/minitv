package us.minitv.admin.flow.content;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Raises the servlet multipart limits for this module's upload routes.
 *
 * <p>The base {@code application.yml} keeps Spring Boot's 1MB default, but the
 * Python endpoints accept 5MB covers ({@code POST /admin/v1/dramas/{id}/cover})
 * and 2MB subtitles; their size/shape validation lives in
 * {@link AFContentMediaService} so the container only needs to let the body
 * through. Spring Boot's auto-configuration backs off as soon as a
 * {@code MultipartConfigElement} bean exists.</p>
 */
@Component
public class AFContentMultipartConfig {

    private static final long MAX_FILE_BYTES = 30L * 1024 * 1024;
    private static final long MAX_REQUEST_BYTES = 35L * 1024 * 1024;

    @Bean
    public MultipartConfigElement afContentMultipartConfigElement() {
        return new MultipartConfigElement("", MAX_FILE_BYTES, MAX_REQUEST_BYTES, 0);
    }
}
