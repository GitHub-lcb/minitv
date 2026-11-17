package us.minitv.partner;

import java.util.Map;

/** Result of a fake-mode TUS PATCH: status code plus the headers Python wrote on the Response. */
public record PartnerDevTusResult(int status, Map<String, String> headers) {
}
