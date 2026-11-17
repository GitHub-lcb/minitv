package us.minitv.partner;

/** Mirrors {@code content.service.InvalidTransition}: an illegal status-machine move. */
public class PartnerInvalidTransition extends RuntimeException {

    private final String entity;
    private final String fromStatus;
    private final String toStatus;

    public PartnerInvalidTransition(String entity, String fromStatus, String toStatus) {
        super("illegal " + entity + " transition: " + fromStatus + " -> " + toStatus);
        this.entity = entity;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public String entity() {
        return entity;
    }

    public String fromStatus() {
        return fromStatus;
    }

    public String toStatus() {
        return toStatus;
    }
}
