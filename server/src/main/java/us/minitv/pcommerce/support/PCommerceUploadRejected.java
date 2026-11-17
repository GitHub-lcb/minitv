package us.minitv.pcommerce.support;

/** UploadRejected equivalent: a rejected media callback, mapped to 400/401. */
public class PCommerceUploadRejected extends RuntimeException {

    public PCommerceUploadRejected(String reason) {
        super(reason);
    }

    public String reason() {
        return getMessage();
    }
}
