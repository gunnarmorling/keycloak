package org.keycloak.services.resources.admin;

import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OAuthClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.representations.adapters.config.BaseAdapterConfig;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.OAuthClientRepresentation;
import org.keycloak.services.managers.ApplicationManager;
import org.keycloak.services.managers.OAuthClientManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.KeycloakApplication;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.List;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class OAuthClientResource  {
    protected static final Logger logger = Logger.getLogger(RealmAdminResource.class);
    protected RealmModel realm;
    protected OAuthClientModel oauthClient;
    protected KeycloakSession session;
    @Context
    protected UriInfo uriInfo;

    @Context
    protected Application application;

    protected KeycloakApplication getApplication() {
        return (KeycloakApplication)application;
    }

    public OAuthClientResource(RealmModel realm, OAuthClientModel oauthClient, KeycloakSession session) {
        this.realm = realm;
        this.oauthClient = oauthClient;
        this.session = session;
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public void update(final OAuthClientRepresentation rep) {
        OAuthClientManager manager = new OAuthClientManager(realm);
        manager.update(rep, oauthClient);
    }


    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public OAuthClientRepresentation getOAuthClient() {
        return OAuthClientManager.toRepresentation(oauthClient);
    }

    @GET
    @NoCache
    @Path("installation")
    @Produces(MediaType.APPLICATION_JSON)
    public String getInstallation() throws IOException {
        OAuthClientManager manager = new OAuthClientManager(realm);
        BaseAdapterConfig rep = manager.toInstallationRepresentation(realm, oauthClient, getApplication().getBaseUri(uriInfo));

        // TODO Temporary solution to pretty-print
        return JsonSerialization.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rep);
    }

    @DELETE
    @NoCache
    public void deleteOAuthClient() {
        realm.removeOAuthClient(oauthClient.getId());
    }

    @Path("credentials")
    @PUT
    @Consumes("application/json")
    public void updateCredentials(List<CredentialRepresentation> credentials) {
        logger.debug("updateCredentials");
        if (credentials == null) return;

        for (CredentialRepresentation rep : credentials) {
            UserCredentialModel cred = RealmManager.fromRepresentation(rep);
            realm.updateCredential(oauthClient.getOAuthAgent(), cred);
        }
    }

    @Path("scope-mappings")
    public ScopeMappedResource getScopeMappedResource() {
        return new ScopeMappedResource(realm, oauthClient.getOAuthAgent(), session);
    }


}
