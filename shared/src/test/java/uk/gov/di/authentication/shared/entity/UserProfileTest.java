package uk.gov.di.authentication.shared.entity;

import com.nimbusds.oauth2.sdk.Scope;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.junit.jupiter.api.Test;
import uk.gov.di.authentication.shared.helpers.NowHelper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class UserProfileTest {

    private static final String EMAIL = "user.one@test.com";
    private static final String PHONE_NUMBER = "01234567890";
    private static final String CLIENT_ID = "client-id";
    private static final Date CREATED_DATE_TIME = NowHelper.nowMinus(30, ChronoUnit.SECONDS);
    private static final Date UPDATED_DATE_TIME = NowHelper.now();
    private static final Date LAST_SIGNED_IN_DATE_TIME = NowHelper.now();
    private static final String LEGACY_SUBJECT_ID = new Subject("legacy-subject-id-1").getValue();
    private static final String PUBLIC_SUBJECT_ID = new Subject("public-subject-id-2").getValue();
    private static final String SUBJECT_ID = new Subject("subject-id-3").getValue();
    private static final TermsAndConditions TERMS_AND_CONDITIONS =
            new TermsAndConditions("1.0", CREATED_DATE_TIME.toString());
    private static final Scope SCOPES =
            new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL, OIDCScopeValue.OFFLINE_ACCESS);
    private static final ByteBuffer SALT =
            ByteBuffer.wrap("a-test-salt".getBytes(StandardCharsets.UTF_8));

    @Test
    void shouldCreateUserProfile() {

        UserProfile userProfile = generateUserProfile();

        assertThat(userProfile.getEmail(), equalTo(EMAIL));
        assertThat(userProfile.getSubjectID(), equalTo(SUBJECT_ID));
        assertThat(userProfile.isEmailVerified(), equalTo(true));
        assertThat(userProfile.getPhoneNumber(), equalTo(PHONE_NUMBER));
        assertThat(userProfile.isPhoneNumberVerified(), equalTo(true));
        assertThat(userProfile.getCreated(), equalTo(CREATED_DATE_TIME.toString()));
        assertThat(userProfile.getUpdated(), equalTo(UPDATED_DATE_TIME.toString()));
        assertThat(userProfile.getLastSignedIn(), equalTo(LAST_SIGNED_IN_DATE_TIME.toString()));
        assertThat(userProfile.getTermsAndConditions(), equalTo(TERMS_AND_CONDITIONS));
        assertThat(userProfile.getLegacySubjectID(), equalTo(LEGACY_SUBJECT_ID));
        assertThat(userProfile.getSalt(), equalTo(SALT));
    }

    @Test
    void shouldRoundTripOptionalUhIdentityAndVerifiedProviderData() {
        var profile =
                new UserProfile()
                        .withEmail(EMAIL)
                        .withSubjectID(SUBJECT_ID)
                        .withUhFullName("Example Person")
                        .withUhNameDeclaredAt("2026-10-03T09:00:00Z");
        profile.setRobloxUserId("12345678");
        profile.setRobloxUsername("ExampleRoblox");
        profile.setRobloxLinkedAt("2026-10-03T09:01:00Z");
        profile.setDiscordUserId("234567890123456789");
        profile.setDiscordUsername("example_discord");
        profile.setDiscordLinkedAt("2026-10-03T09:02:00Z");
        profile.setDiscordContactOptIn(true);

        TableSchema<UserProfile> schema = TableSchema.fromBean(UserProfile.class);
        Map<String, AttributeValue> stored = schema.itemToMap(profile, true);

        assertThat(stored.get(UserProfile.ATTRIBUTE_UH_FULL_NAME).s(), equalTo("Example Person"));
        assertThat(stored.get(UserProfile.ATTRIBUTE_ROBLOX_USER_ID).s(), equalTo("12345678"));
        assertThat(
                stored.get(UserProfile.ATTRIBUTE_DISCORD_USER_ID).s(),
                equalTo("234567890123456789"));
        assertThat(stored.get(UserProfile.ATTRIBUTE_DISCORD_CONTACT_OPT_IN).n(), equalTo("1"));
        assertThat(stored.containsKey(UserProfile.ATTRIBUTE_PHONE_NUMBER), equalTo(false));

        UserProfile restored = schema.mapToItem(stored);
        assertThat(restored.getUhFullName(), equalTo("Example Person"));
        assertThat(restored.getUhNameDeclaredAt(), equalTo("2026-10-03T09:00:00Z"));
        assertThat(restored.getRobloxUsername(), equalTo("ExampleRoblox"));
        assertThat(restored.getRobloxLinkedAt(), equalTo("2026-10-03T09:01:00Z"));
        assertThat(restored.getDiscordUsername(), equalTo("example_discord"));
        assertThat(restored.getDiscordLinkedAt(), equalTo("2026-10-03T09:02:00Z"));
        assertThat(restored.isDiscordContactOptIn(), equalTo(true));
    }

    @Test
    void shouldReadExistingAccountWithoutNewProfileAttributesOrPhoneNumber() {
        TableSchema<UserProfile> schema = TableSchema.fromBean(UserProfile.class);
        UserProfile existing =
                schema.mapToItem(
                        Map.of(
                                UserProfile.ATTRIBUTE_EMAIL, AttributeValue.fromS(EMAIL),
                                UserProfile.ATTRIBUTE_SUBJECT_ID, AttributeValue.fromS(SUBJECT_ID)));

        assertThat(existing.getEmail(), equalTo(EMAIL));
        assertThat(existing.getUhFullName(), equalTo(null));
        assertThat(existing.getRobloxUserId(), equalTo(null));
        assertThat(existing.getDiscordUserId(), equalTo(null));
        assertThat(existing.isDiscordContactOptIn(), equalTo(false));
        assertThat(existing.getPhoneNumber(), equalTo(null));
    }

    private UserProfile generateUserProfile() {
        return new UserProfile()
                .withEmail(EMAIL)
                .withEmailVerified(true)
                .withPhoneNumber(PHONE_NUMBER)
                .withPhoneNumberVerified(true)
                .withPublicSubjectID(PUBLIC_SUBJECT_ID)
                .withSubjectID(SUBJECT_ID)
                .withLegacySubjectID(LEGACY_SUBJECT_ID)
                .withTermsAndConditions(TERMS_AND_CONDITIONS)
                .withSalt(SALT)
                .withCreated(CREATED_DATE_TIME.toString())
                .withUpdated(UPDATED_DATE_TIME.toString())
                .withLastSignedIn(LAST_SIGNED_IN_DATE_TIME.toString());
    }
}
