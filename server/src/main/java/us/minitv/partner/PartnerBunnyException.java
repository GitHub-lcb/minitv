package us.minitv.partner;

/** Mirrors {@code app.modules.common.bunny.BunnyError}: an upstream Bunny failure or missing config. */
public class PartnerBunnyException extends RuntimeException {

    public PartnerBunnyException(String message) {
        super(message);
    }

    public PartnerBunnyException(String message, Throwable cause) {
        super(message, cause);
    }
}
