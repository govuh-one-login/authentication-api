package uk.gov.di.accountmanagement.services;

import uk.gov.di.authentication.shared.entity.UserProfile;
import java.time.Instant;

/** Writes only permitted declared-name attributes after subject authorisation. */
public interface UhProfileStore {
    void updateDeclaredName(UserProfile authorisedProfile, String fullName, Instant declaredAt);
}
