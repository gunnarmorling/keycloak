package org.keycloak.services.managers;

import org.jboss.resteasy.logging.Logger;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.util.JsonSerialization;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.Constants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.SkeletonKeyScope;
import org.keycloak.representations.SkeletonKeyToken;
import org.keycloak.util.Base64Url;

import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful object that creates tokens and manages oauth access codes
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class TokenManager {
    protected static final Logger logger = Logger.getLogger(TokenManager.class);

    protected Map<String, AccessCodeEntry> accessCodeMap = new ConcurrentHashMap<String, AccessCodeEntry>();

    public void clearAccessCodes() {
        accessCodeMap.clear();
    }

    public AccessCodeEntry getAccessCode(String key) {
        return accessCodeMap.get(key);
    }

    public AccessCodeEntry pullAccessCode(String key) {
        return accessCodeMap.remove(key);
    }


    public AccessCodeEntry createAccessCode(String scopeParam, String state, String redirect, RealmModel realm, UserModel client, UserModel user) {
        boolean applicationResource = realm.hasRole(client, realm.getRole(Constants.APPLICATION_ROLE));

        AccessCodeEntry code = new AccessCodeEntry();
        SkeletonKeyScope scopeMap = null;
        if (scopeParam != null) scopeMap = decodeScope(scopeParam);
        List<RoleModel> realmRolesRequested = code.getRealmRolesRequested();
        MultivaluedMap<String, RoleModel> resourceRolesRequested = code.getResourceRolesRequested();
        Set<String> realmMapping = realm.getRoleMappingValues(user);

        if (realmMapping != null && realmMapping.size() > 0 && (scopeMap == null || scopeMap.containsKey("realm"))) {
            Set<String> scope = realm.getScopeMappingValues(client);
            if (scope.size() > 0) {
                Set<String> scopeRequest = scopeMap != null ? new HashSet<String>(scopeMap.get("realm")) : null;
                for (String role : realmMapping) {
                    if ((scopeRequest == null || scopeRequest.contains(role)) && scope.contains(role))
                        realmRolesRequested.add(realm.getRole(role));
                }
            }
        }
        for (ApplicationModel resource : realm.getApplications()) {
            if (applicationResource && resource.getApplicationUser().getLoginName().equals(client.getLoginName())) {
                for (String role : resource.getRoleMappingValues(user)) {
                    resourceRolesRequested.addAll(resource.getName(), resource.getRole(role));
                }
            } else {
                Set<String> mapping = resource.getRoleMappingValues(user);
                if (mapping != null && mapping.size() > 0 && (scopeMap == null || scopeMap.containsKey(resource.getName()))) {
                    Set<String> scope = resource.getScopeMappingValues(client);
                    if (scope.size() > 0) {
                        Set<String> scopeRequest = scopeMap != null ? new HashSet<String>(scopeMap.get(resource.getName())) : null;
                        for (String role : mapping) {
                            if ((scopeRequest == null || scopeRequest.contains(role)) && scope.contains(role))
                                resourceRolesRequested.add(resource.getName(), resource.getRole(role));
                        }
                    }
                }
            }
        }


        createToken(code, realm, client, user);
        code.setRealm(realm);
        code.setExpiration((System.currentTimeMillis() / 1000) + realm.getAccessCodeLifespan());
        code.setClient(client);
        code.setUser(user);
        code.setState(state);
        code.setRedirectUri(redirect);
        accessCodeMap.put(code.getId(), code);
        String accessCode = null;
        try {
            accessCode = new JWSBuilder().content(code.getId().getBytes("UTF-8")).rsa256(realm.getPrivateKey());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        code.setCode(accessCode);
        return code;
    }

    protected SkeletonKeyToken initToken(RealmModel realm, UserModel client, UserModel user) {
        SkeletonKeyToken token = new SkeletonKeyToken();
        token.id(RealmManager.generateId());
        token.principal(user.getLoginName());
        token.audience(realm.getName());
        token.issuedNow();
        token.issuedFor(client.getLoginName());
        if (realm.getTokenLifespan() > 0) {
            token.expiration((System.currentTimeMillis() / 1000) + realm.getTokenLifespan());
        }
        Set<String> allowedOrigins = client.getWebOrigins();
        if (allowedOrigins != null) {
            token.setAllowedOrigins(allowedOrigins);
        }
        return token;
    }

    protected void createToken(AccessCodeEntry accessCodeEntry, RealmModel realm, UserModel client, UserModel user) {

        SkeletonKeyToken token = initToken(realm, client, user);

        if (accessCodeEntry.getRealmRolesRequested().size() > 0) {
            SkeletonKeyToken.Access access = new SkeletonKeyToken.Access();
            for (RoleModel role : accessCodeEntry.getRealmRolesRequested()) {
                access.addRole(role.getName());
            }
            token.setRealmAccess(access);
        }

        if (accessCodeEntry.getResourceRolesRequested().size() > 0) {
            Map<String, ApplicationModel> resourceMap = realm.getApplicationNameMap();
            for (String resourceName : accessCodeEntry.getResourceRolesRequested().keySet()) {
                ApplicationModel resource = resourceMap.get(resourceName);
                SkeletonKeyToken.Access access = token.addAccess(resourceName).verifyCaller(resource.isSurrogateAuthRequired());
                for (RoleModel role : accessCodeEntry.getResourceRolesRequested().get(resourceName)) {
                    access.addRole(role.getName());
                }
            }
        }
        accessCodeEntry.setToken(token);
    }

    public String encodeScope(SkeletonKeyScope scope) {
        String token = null;
        try {
            token = JsonSerialization.writeValueAsString(scope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return Base64Url.encode(token.getBytes());
    }

    public SkeletonKeyScope decodeScope(String scopeParam) {
        SkeletonKeyScope scope = null;
        byte[] bytes = Base64Url.decode(scopeParam);
        try {
            scope = JsonSerialization.readValue(bytes, SkeletonKeyScope.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return scope;
    }


    public SkeletonKeyToken createAccessToken(RealmModel realm, UserModel user) {
        List<ApplicationModel> resources = realm.getApplications();
        SkeletonKeyToken token = new SkeletonKeyToken();
        token.id(RealmManager.generateId());
        token.issuedNow();
        token.principal(user.getLoginName());
        token.audience(realm.getName());
        if (realm.getTokenLifespan() > 0) {
            token.expiration((System.currentTimeMillis() / 1000) + realm.getTokenLifespan());
        }

        Set<String> realmMapping = realm.getRoleMappingValues(user);

        if (realmMapping != null && realmMapping.size() > 0) {
            SkeletonKeyToken.Access access = new SkeletonKeyToken.Access();
            for (String role : realmMapping) {
                access.addRole(role);
            }
            token.setRealmAccess(access);
        }
        if (resources != null) {
            for (ApplicationModel resource : resources) {
                Set<String> mapping = resource.getRoleMappingValues(user);
                if (mapping == null) continue;
                SkeletonKeyToken.Access access = token.addAccess(resource.getName())
                        .verifyCaller(resource.isSurrogateAuthRequired());
                for (String role : mapping) {
                    access.addRole(role);
                }
            }
        }
        return token;
    }


    public String encodeToken(RealmModel realm, Object token) {
        String encodedToken = new JWSBuilder()
                .jsonContent(token)
                .rsa256(realm.getPrivateKey());
        return encodedToken;
    }
}
