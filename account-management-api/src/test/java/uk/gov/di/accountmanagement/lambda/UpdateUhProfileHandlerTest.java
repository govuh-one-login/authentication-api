package uk.gov.di.accountmanagement.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.nimbusds.oauth2.sdk.id.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.accountmanagement.services.UhProfileStore;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UpdateUhProfileHandlerTest {
    private static final String EMAIL = "person@example.org";
    private static final String PUBLIC_SUBJECT = new Subject().getValue();
    private static final String INTERNAL_SUBJECT = new Subject().getValue();
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    private final ConfigurationService configuration = mock(ConfigurationService.class);
    private final DynamoService accounts = mock(DynamoService.class);
    private final UhProfileStore store = mock(UhProfileStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final UserProfile profile = new UserProfile()
            .withEmail(EMAIL)
            .withSubjectID(INTERNAL_SUBJECT)
            .withPublicSubjectID(PUBLIC_SUBJECT);

    private UpdateUhProfileHandler handler;

    @BeforeEach
    void setUp() {
        reset(configuration, accounts, store, audit);
        when(configuration.getInternalSectorUri()).thenReturn("https://account.gov.uhrblx.com");
        when(accounts.getOptionalUserProfileFromPublicSubject(PUBLIC_SUBJECT))
                .thenReturn(Optional.of(profile));
        // The same subject-derived principal check used by native MFA account management.
        var salt = "uh-test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        profile.setSalt(salt);
        when(accounts.getOrGenerateSalt(profile)).thenReturn(salt);
        handler = new UpdateUhProfileHandler(
                configuration,
                accounts,
                store,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String expectedPrincipal() {
        return ClientSubjectHelper.calculatePairwiseIdentifier(
                INTERNAL_SUBJECT, "account.gov.uhrblx.com", "uh-test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private APIGatewayProxyRequestEvent request(String principal, String body) {
        ProxyRequestContext context = new ProxyRequestContext();
        context.setAuthorizer(Map.of("principalId", principal, "clientId", "uh-account-management"));
        context.setIdentity(new APIGatewayProxyRequestEvent.RequestIdentity().withSourceIp("127.0.0.1"));
        return new APIGatewayProxyRequestEvent()
                .withPathParameters(Map.of("publicSubjectId", PUBLIC_SUBJECT))
                .withHeaders(Map.of("Session-Id", "uh-test-session", "Client-Session-Id", "uh-test-client-session"))
                .withRequestContext(context)
                .withBody(body);
    }

    @Test
    void onlyTheAuthenticatedAccountCanChangeItsOwnDeclaredName() {
        var response = handler.handleRequest(
                request(expectedPrincipal(), "{\"fullName\":\"  Mary-Jane   O’Neil  \"}"), null);
        assertEquals(204, response.getStatusCode());
        verify(store).updateDeclaredName(profile, "Mary-Jane O’Neil", NOW);
        verify(audit).submitAuditEvent(eq(uk.gov.di.accountmanagement.domain.AccountManagementAuditableEvent.AUTH_UPDATE_UH_DECLARED_NAME), any());
    }

    @Test
    void aDifferentPrincipalCannotChangeTheAccount() {
        var response = handler.handleRequest(request("someone-else", "{\"fullName\":\"Wrong\"}"), null);
        assertEquals(401, response.getStatusCode());
        verifyNoInteractions(store, audit);
    }

    @Test
    void aMissingPrincipalCannotModifyAnything() {
        var response = handler.handleRequest(
                request(expectedPrincipal(), "{\"fullName\":\"Name\"}")
                        .withRequestContext(new ProxyRequestContext()), null);
        assertEquals(401, response.getStatusCode());
        verifyNoInteractions(store, audit);
    }

    @Test
    void anotherAccountCannotBeSelectedUsingSubmittedEmailOrProviderId() {
        for (String body : new String[] {
                "{\"fullName\":\"Person\",\"email\":\"other@example.org\"}",
                "{\"fullName\":\"Person\",\"robloxUserId\":\"987654321\"}",
                "{\"fullName\":\"Person\",\"discordUserId\":\"987654321\"}",
                "{\"fullName\":\"Person\",\"robloxLinkedAt\":\"2026-10-03T12:00:00Z\"}",
                "{\"fullName\":\"Person\",\"discordContactOptIn\":true}"
        }) {
            assertEquals(400, handler.handleRequest(request(expectedPrincipal(), body), null).getStatusCode());
        }
        verifyNoInteractions(store, audit);
    }

    @Test
    void rejectsMissingNameAndInvalidBody() {
        for (String body : new String[] {"{}", "[]", "{\"fullName\":null}", "{\"fullName\":1}",
                "{\"fullName\":\"\"}", "{\"fullName\":\"A\\nB\"}"}) {
            assertEquals(400, handler.handleRequest(request(expectedPrincipal(), body), null).getStatusCode());
        }
        verifyNoInteractions(store, audit);
    }

    @Test
    void missingOrUnknownPublicSubjectCannotChangeData() {
        var missing = request(expectedPrincipal(), "{\"fullName\":\"Person\"}")
                .withPathParameters(Map.of());
        assertEquals(400, handler.handleRequest(missing, null).getStatusCode());
        when(accounts.getOptionalUserProfileFromPublicSubject(PUBLIC_SUBJECT)).thenReturn(Optional.empty());
        assertEquals(404, handler.handleRequest(request(expectedPrincipal(), "{\"fullName\":\"Person\"}"), null).getStatusCode());
        verifyNoInteractions(store, audit);
    }

    @Test
    void doesNotAcceptAStaleSubjectAtTheStorageBoundary() {
        var conditionalConflict = software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException.builder().message("Subject changed").build();
        doThrow(conditionalConflict).when(store).updateDeclaredName(profile, "Person", NOW);
        var response = handler.handleRequest(request(expectedPrincipal(), "{\"fullName\":\"Person\"}"), null);
        assertEquals(401, response.getStatusCode());
        verifyNoInteractions(audit);
    }

    @Test
    void nameValidatorRejectsControlCharactersAndIncorrectLength() {
        assertThrows(IllegalArgumentException.class,
                () -> uk.gov.di.accountmanagement.helpers.UhProfileName.normalise("a".repeat(121)));
        assertThrows(IllegalArgumentException.class,
                () -> uk.gov.di.accountmanagement.helpers.UhProfileName.normalise("A\u202eB"));
    }
}
