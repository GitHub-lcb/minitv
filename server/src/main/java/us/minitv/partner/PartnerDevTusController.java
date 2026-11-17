package us.minitv.partner;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import us.minitv.security.CurrentPartner;
import us.minitv.security.PartnerContext;

import java.io.IOException;

/**
 * Fake-mode TUS receiver for local development, mirroring Bunny's {@code POST
 * /tusupload} + {@code PATCH Location} dance. Never used in production: real
 * mode answers 404 exactly like the Python route.
 */
@RestController
public class PartnerDevTusController {

    private final PartnerVideoUploadService uploads;

    public PartnerDevTusController(PartnerVideoUploadService uploads) {
        this.uploads = uploads;
    }

    @RequestMapping(path = "/partner/v1/dev-tus/{video_id}", method = RequestMethod.POST)
    public ResponseEntity<Void> create(
            @CurrentPartner PartnerContext partner,
            @PathVariable("video_id") String videoId) {
        HttpHeaders headers = new HttpHeaders();
        uploads.devTusCreate(partner.user(), videoId).forEach(headers::set);
        return new ResponseEntity<>(headers, HttpStatus.CREATED);
    }

    @RequestMapping(path = "/partner/v1/dev-tus/{video_id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(
            @CurrentPartner PartnerContext partner,
            @PathVariable("video_id") String videoId) {
        HttpHeaders headers = new HttpHeaders();
        uploads.devTusHead(partner.user(), videoId).forEach(headers::set);
        return new ResponseEntity<>(headers, HttpStatus.OK);
    }

    @RequestMapping(path = "/partner/v1/dev-tus/{video_id}", method = RequestMethod.PATCH)
    public ResponseEntity<Void> patch(
            @CurrentPartner PartnerContext partner,
            @PathVariable("video_id") String videoId,
            @RequestHeader(name = "upload-offset", required = false) String uploadOffset,
            HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        PartnerDevTusResult result = uploads.devTusPatch(partner.user(), videoId, uploadOffset, body);
        HttpHeaders headers = new HttpHeaders();
        result.headers().forEach(headers::set);
        return new ResponseEntity<>(headers, HttpStatus.valueOf(result.status()));
    }
}
