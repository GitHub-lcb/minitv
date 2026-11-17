package us.minitv.partner;

/** Mirrors {@code upload_service.UploadRejected}: validation failure rendered as a 422 string detail. */
public class PartnerUploadRejected extends RuntimeException {

    private final String reason;

    public PartnerUploadRejected(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
