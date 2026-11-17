package us.minitv.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Serialise stored UTC datetimes exactly like Python's {@code isoformat()} on an
 * aware datetime: {@code 2026-10-14T12:00:00+00:00}, with six fractional digits
 * only when the stored value has microseconds.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer fastApiDateTimeShape() {
        return builder -> builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                // Python keeps microsecond precision only: truncate to micros.
                int micros = value.getNano() / 1_000;
                StringBuilder text = new StringBuilder(value.format(SECONDS));
                if (micros > 0) {
                    text.append('.').append(String.format(Locale.ROOT, "%06d", micros));
                }
                text.append("+00:00");
                gen.writeString(text.toString());
            }
        });
    }
}
