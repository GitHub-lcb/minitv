package us.minitv.paccount;

/** Small string helpers shared by the account port. */
final class PAccountText {

    private PAccountText() {
    }

    /** Python slice {@code value[:max]}; null stays null. */
    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
