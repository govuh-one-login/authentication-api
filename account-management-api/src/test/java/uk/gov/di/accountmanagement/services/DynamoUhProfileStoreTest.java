package uk.gov.di.accountmanagement.services;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import uk.gov.di.authentication.shared.entity.UserProfile;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoUhProfileStoreTest {
    @Test
    void updatesOnlyTheNameAndAuditTimestampsUnderAStoredSubjectCondition() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        var store = new DynamoUhProfileStore(client, "test-user-profile");
        UserProfile profile = new UserProfile()
                .withEmail("PERSON@EXAMPLE.ORG")
                .withSubjectID("internal-subject");
        store.updateDeclaredName(profile, "Example Person", Instant.parse("2026-10-03T12:00:00Z"));

        var request = org.mockito.ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(request.capture());
        UpdateItemRequest saved = request.getValue();
        assertEquals("test-user-profile", saved.tableName());
        assertEquals("person@example.org", saved.key().get(UserProfile.ATTRIBUTE_EMAIL).s());
        assertTrue(saved.conditionExpression().contains("#subject = :expectedSubject"));
        assertEquals("internal-subject", saved.expressionAttributeValues().get(":expectedSubject").s());
        assertEquals("Example Person", saved.expressionAttributeValues().get(":name").s());
        assertEquals("2026-10-03T12:00:00Z", saved.expressionAttributeValues().get(":when").s());

        // A self-declared name is not a provider verification or contact-consent operation.
        for (String forbidden : new String[]{
                UserProfile.ATTRIBUTE_ROBLOX_USER_ID,
                UserProfile.ATTRIBUTE_ROBLOX_LINKED_AT,
                UserProfile.ATTRIBUTE_DISCORD_USER_ID,
                UserProfile.ATTRIBUTE_DISCORD_LINKED_AT,
                UserProfile.ATTRIBUTE_DISCORD_CONTACT_OPT_IN,
                UserProfile.ATTRIBUTE_PHONE_NUMBER
        }) {
            assertFalse(saved.expressionAttributeNames().containsValue(forbidden));
        }
        verifyNoMoreInteractions(client);
    }

    @Test
    void doesNotWriteIfThereIsNoResolvedAccountSubject() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        var store = new DynamoUhProfileStore(client, "test-user-profile");
        assertThrows(IllegalArgumentException.class, () -> store.updateDeclaredName(
                new UserProfile().withEmail("person@example.org"),
                "Example Person",
                Instant.now()));
        verify(client, never()).updateItem(any(UpdateItemRequest.class));
    }
}
