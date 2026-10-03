package uk.gov.di.accountmanagement.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UhProfileNameTest {
    @Test
    void acceptsAndNormalisesAUnicodeName() {
        assertEquals("Mary-Jane O’Neil", UhProfileName.normalise("  Mary-Jane   O’Neil  "));
        assertEquals("Renée", UhProfileName.normalise("Rene\u0301e"));
        assertEquals("李", UhProfileName.normalise("李"));
    }

    @Test
    void doesNotCollectANameAsProofOfLegalIdentity() {
        assertEquals("Chosen Name", UhProfileName.normalise("Chosen Name"));
    }

    @Test
    void rejectsNullEmptyAndOverlongNames() {
        assertThrows(IllegalArgumentException.class, () -> UhProfileName.normalise(null));
        assertThrows(IllegalArgumentException.class, () -> UhProfileName.normalise("  "));
        assertThrows(IllegalArgumentException.class, () -> UhProfileName.normalise("a".repeat(121)));
    }

    @Test
    void rejectsControlAndBidirectionalOverrideCharacters() {
        assertThrows(IllegalArgumentException.class, () -> UhProfileName.normalise("First\nLast"));
        assertThrows(IllegalArgumentException.class, () -> UhProfileName.normalise("A\u202E B"));
        assertThrows(IllegalArgumentException.class, () -> UhProfileName.normalise("A\u2066 B"));
    }
}
