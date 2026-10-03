package uk.gov.di.accountmanagement.helpers;

import java.text.Normalizer;

/** Validates a self-declared account name; this is not an identity-proofing result. */
public final class UhProfileName {
    private UhProfileName() {}

    public static String normalise(String suppliedName) {
        if (suppliedName == null) {
            throw new IllegalArgumentException("A full name must be provided");
        }
        String normalised = Normalizer.normalize(suppliedName, Normalizer.Form.NFC);
        for (int offset = 0; offset < normalised.length(); ) {
            int character = normalised.codePointAt(offset);
            if (Character.isISOControl(character)
                    || Character.getType(character) == Character.LINE_SEPARATOR
                    || Character.getType(character) == Character.PARAGRAPH_SEPARATOR
                    || (character >= 0x202A && character <= 0x202E)
                    || (character >= 0x2066 && character <= 0x2069)) {
                throw new IllegalArgumentException("A full name contains invalid characters");
            }
            offset += Character.charCount(character);
        }
        normalised = normalised.strip().replaceAll("(?U)\\s+", " ");
        int length = normalised.codePointCount(0, normalised.length());
        if (length < 1 || length > 120) {
            throw new IllegalArgumentException("A full name must be between 1 and 120 characters");
        }
        return normalised;
    }
}
