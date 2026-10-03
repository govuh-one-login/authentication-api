package uk.gov.di.accountmanagement.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import uk.gov.di.accountmanagement.helpers.PrincipalValidationHelper;
import uk.gov.di.accountmanagement.helpers.UhProfileName;
import uk.gov.di.accountmanagement.services.DynamoUhProfileStore;
import uk.gov.di.accountmanagement.services.UhProfileStore;
import uk.gov.di.audit.AuditContext;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.ClientSessionIdHelper;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.helpers.RequestHeaderHelper;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import static uk.gov.di.accountmanagement.domain.AccountManagementAuditableEvent.AUTH_UPDATE_UH_DECLARED_NAME;
import static uk.gov.di.authentication.shared.domain.RequestHeaders.SESSION_ID_HEADER;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateEmptySuccessApiGatewayResponse;

/**
 * Native account-management operation for a self-declared name only.
 *
 * A Roblox/Discord link cannot be asserted via this endpoint: its JSON request
 * contains exactly one allowed field and it verifies the token-derived principal.
 */
public class UpdateUhProfileHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger LOG = LogManager.getLogger(UpdateUhProfileHandler.class);
    private final ConfigurationService configuration;
    private final DynamoService dynamoService;
    private final UhProfileStore profileStore;
    private final AuditService auditService;
    private final Clock clock;

    public UpdateUhProfileHandler() {
        this(ConfigurationService.getInstance());
    }

    public UpdateUhProfileHandler(ConfigurationService configuration) {
        this(
                configuration,
                new DynamoService(configuration),
                new DynamoUhProfileStore(configuration),
                new AuditService(configuration),
                Clock.systemUTC());
    }

    public UpdateUhProfileHandler(
            ConfigurationService configuration,
            DynamoService dynamoService,
            UhProfileStore profileStore,
            AuditService auditService,
            Clock clock) {
        this.configuration = configuration;
        this.dynamoService = dynamoService;
        this.profileStore = profileStore;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        if (input == null
                || input.getRequestContext() == null
                || input.getRequestContext().getAuthorizer() == null
                || !input.getRequestContext().getAuthorizer().containsKey("principalId")) {
            return generateApiGatewayProxyErrorResponse(401, ErrorResponse.INVALID_PRINCIPAL);
        }

        Map<String, String> path = input.getPathParameters();
        String publicSubjectId = path == null ? null : path.get("publicSubjectId");
        if (publicSubjectId == null || publicSubjectId.isBlank()) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.REQUEST_MISSING_PARAMS);
        }

        Optional<UserProfile> account =
                dynamoService.getOptionalUserProfileFromPublicSubject(publicSubjectId);
        if (account.isEmpty()) {
            return generateApiGatewayProxyErrorResponse(404, ErrorResponse.USER_NOT_FOUND);
        }

        UserProfile profile = account.get();
        Map<String, Object> authorizer = input.getRequestContext().getAuthorizer();
        if (PrincipalValidationHelper.principalIsInvalid(
                profile, configuration.getInternalSectorUri(), dynamoService, authorizer)) {
            LOG.warn("UH profile update rejected due to invalid account principal");
            return generateApiGatewayProxyErrorResponse(401, ErrorResponse.INVALID_PRINCIPAL);
        }

        try {
            String name = extractDeclaredName(input.getBody());
            profileStore.updateDeclaredName(profile, name, clock.instant());
            String sessionId =
                    RequestHeaderHelper.getHeaderValueOrElse(input.getHeaders(), SESSION_ID_HEADER, "");
            String clientId = String.valueOf(authorizer.getOrDefault("clientId", ""));
            AuditContext audit = new AuditContext(
                    clientId,
                    ClientSessionIdHelper.extractSessionIdFromHeaders(input.getHeaders()),
                    sessionId,
                    String.valueOf(authorizer.get("principalId")),
                    profile.getEmail(),
                    IpAddressHelper.extractIpAddress(input),
                    null,
                    "",
                    "");
            // Do not include the self-declared name or linked-provider identities in the audit event.
            auditService.submitAuditEvent(AUTH_UPDATE_UH_DECLARED_NAME, audit);
            return generateEmptySuccessApiGatewayResponse();
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.INVALID_REQUEST_BODY);
        } catch (ConditionalCheckFailedException e) {
            LOG.warn("UH profile update rejected because the current account subject changed");
            return generateApiGatewayProxyErrorResponse(401, ErrorResponse.INVALID_PRINCIPAL);
        }
    }

    static String extractDeclaredName(String body) {
        if (body == null || body.length() > 1024) {
            throw new IllegalArgumentException("Invalid declared-name request");
        }
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Expected an object");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (object.size() != 1 || !object.has("fullName")) {
            throw new IllegalArgumentException("Only fullName may be changed using this endpoint");
        }
        JsonElement name = object.get("fullName");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("A full name must be a string");
        }
        return UhProfileName.normalise(name.getAsString());
    }
}
