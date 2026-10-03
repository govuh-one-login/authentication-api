package uk.gov.di.accountmanagement.services;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import uk.gov.di.authentication.shared.configuration.DynamoConfiguration;
import uk.gov.di.authentication.shared.dynamodb.DynamoClientHelper;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.TableNameHelper;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Atomic, subject-conditional update: never writes a complete stale UserProfile
 * or accepts platform-link attributes from an account form.
 */
public final class DynamoUhProfileStore implements UhProfileStore {
    private final DynamoDbClient client;
    private final String tableName;

    public DynamoUhProfileStore(DynamoConfiguration configuration) {
        this(
                DynamoClientHelper.createDynamoClient(configuration),
                TableNameHelper.getFullTableName("user-profile", configuration));
    }

    public DynamoUhProfileStore(DynamoDbClient client, String tableName) {
        this.client = Objects.requireNonNull(client);
        this.tableName = Objects.requireNonNull(tableName);
    }

    @Override
    public void updateDeclaredName(UserProfile authorisedProfile, String fullName, Instant declaredAt) {
        Objects.requireNonNull(authorisedProfile);
        Objects.requireNonNull(fullName);
        Objects.requireNonNull(declaredAt);
        if (authorisedProfile.getEmail() == null || authorisedProfile.getSubjectID() == null) {
            throw new IllegalArgumentException("A current authenticated account must be identified");
        }

        // Compare the stored subject at write time, not only at the earlier read/authorisation.
        client.updateItem(
                UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                UserProfile.ATTRIBUTE_EMAIL,
                                AttributeValue.fromS(authorisedProfile.getEmail().toLowerCase(Locale.ROOT))))
                        .conditionExpression("attribute_exists(#email) AND #subject = :expectedSubject")
                        .updateExpression("SET #name = :name, #declaredAt = :when, #updated = :when")
                        .expressionAttributeNames(Map.of(
                                "#email", UserProfile.ATTRIBUTE_EMAIL,
                                "#subject", UserProfile.ATTRIBUTE_SUBJECT_ID,
                                "#name", UserProfile.ATTRIBUTE_UH_FULL_NAME,
                                "#declaredAt", UserProfile.ATTRIBUTE_UH_NAME_DECLARED_AT,
                                "#updated", UserProfile.ATTRIBUTE_UPDATED))
                        .expressionAttributeValues(Map.of(
                                ":expectedSubject", AttributeValue.fromS(authorisedProfile.getSubjectID()),
                                ":name", AttributeValue.fromS(fullName),
                                ":when", AttributeValue.fromS(declaredAt.toString())))
                        .build());
    }
}
