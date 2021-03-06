package org.pac4j.core.engine;

import org.junit.Before;
import org.junit.Test;
import org.pac4j.core.client.*;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.MockWebContext;
import org.pac4j.core.util.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.MockCredentials;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.StatusAction;
import org.pac4j.core.exception.http.FoundAction;
import org.pac4j.core.http.adapter.HttpActionAdapter;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.TestsConstants;
import org.pac4j.core.util.TestsHelper;

import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Tests {@link DefaultSecurityLogic}.
 *
 * @author Jerome Leleu
 * @since 1.9.0
 */
public final class DefaultSecurityLogicTests implements TestsConstants {

    private DefaultSecurityLogic<Object, WebContext> logic;

    private MockWebContext context;

    private Config config;

    private SecurityGrantedAccessAdapter<Object, WebContext> securityGrantedAccessAdapter;

    private HttpActionAdapter<Object, WebContext> httpActionAdapter;

    private String clients;

    private String authorizers;

    private String matchers;

    private Boolean multiProfile;

    private int nbCall;

    private HttpAction action;

    @Before
    public void setUp() {
        logic = new DefaultSecurityLogic();
        context = MockWebContext.create();
        config = new Config();
        securityGrantedAccessAdapter = (context, profiles, parameters) -> { nbCall++; return null; };
        httpActionAdapter = (act, ctx) -> { action = act; return null; };
        clients = null;
        authorizers = null;
        matchers = null;
        multiProfile = null;
        nbCall = 0;
    }

    private void call() {
        logic.perform(context, config, securityGrantedAccessAdapter, httpActionAdapter, clients, authorizers, matchers, multiProfile);
    }

    @Test
    public void testNullConfig() {
        config = null;
        TestsHelper.expectException(() -> call(), TechnicalException.class, "config cannot be null");
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testNullContext() {
        context = null;
        TestsHelper.expectException(() -> call(), TechnicalException.class, "context cannot be null");
    }

    @Test
    public void testNullHttpActionAdapter() {
        httpActionAdapter = null;
        TestsHelper.expectException(() -> call(), TechnicalException.class, "httpActionAdapter cannot be null");
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testNullClients() {
        config.setClients(null);
        TestsHelper.expectException(() -> call(), TechnicalException.class, "configClients cannot be null");
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testNullClientFinder() {
        logic.setClientFinder(null);
        TestsHelper.expectException(() -> call(), TechnicalException.class, "clientFinder cannot be null");
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testNullAuthorizationChecker() {
        logic.setAuthorizationChecker(null);
        TestsHelper.expectException(() -> call(), TechnicalException.class, "authorizationChecker cannot be null");
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testNullMatchingChecker() {
        logic.setMatchingChecker(null);
        TestsHelper.expectException(() -> call(), TechnicalException.class, "matchingChecker cannot be null");
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testNotAuthenticated() {
        final IndirectClient indirectClient = new MockIndirectClient(NAME, null, Optional.of(new MockCredentials()), new CommonProfile());
        config.setClients(new Clients(CALLBACK_URL, indirectClient));
        clients = "";
        call();
        assertEquals(401, action.getCode());
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testNotAuthenticatedButMatcher() {
        final IndirectClient indirectClient = new MockIndirectClient(NAME, null, Optional.of(new MockCredentials()), new CommonProfile());
        config.setClients(new Clients(CALLBACK_URL, indirectClient));
        config.addMatcher(NAME, context -> false);
        matchers = NAME;
        call();
        assertNull(action);
        assertEquals(1, nbCall);
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).isPresent());
    }

    @Test
    public void testAlreadyAuthenticatedAndAuthorized() {
        final CommonProfile profile = new CommonProfile();
        profile.setId(ID);
        final LinkedHashMap<String, CommonProfile> profiles = new LinkedHashMap<>();
        profiles.put(NAME, profile);
        context.getSessionStore().set(context, Pac4jConstants.USER_PROFILES, profiles);
        final IndirectClient indirectClient = new MockIndirectClient(NAME, null, Optional.of(new MockCredentials()), new CommonProfile());
        authorizers = NAME;
        config.setClients(new Clients(CALLBACK_URL, indirectClient));
        config.addAuthorizer(NAME, (context, prof) -> ID.equals(((CommonProfile) prof.get(0)).getId()));
        call();
        assertNull(action);
        assertEquals(1, nbCall);
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testAlreadyAuthenticatedNotAuthorized() {
        final CommonProfile profile = new CommonProfile();
        final LinkedHashMap<String, CommonProfile> profiles = new LinkedHashMap<>();
        profiles.put(NAME, profile);
        context.getSessionStore().set(context, Pac4jConstants.USER_PROFILES, profiles);
        final IndirectClient indirectClient = new MockIndirectClient(NAME, null, Optional.of(new MockCredentials()), new CommonProfile());
        authorizers = NAME;
        config.setClients(new Clients(CALLBACK_URL, indirectClient));
        config.addAuthorizer(NAME, (context, prof) -> ID.equals(((CommonProfile) prof.get(0)).getId()));
        call();
        assertEquals(403, action.getCode());
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testAuthorizerThrowsRequiresHttpAction() {
        final CommonProfile profile = new CommonProfile();
        final LinkedHashMap<String, CommonProfile> profiles = new LinkedHashMap<>();
        profiles.put(NAME, profile);
        context.getSessionStore().set(context, Pac4jConstants.USER_PROFILES, profiles);
        final IndirectClient indirectClient = new MockIndirectClient(NAME, null, Optional.of(new MockCredentials()), new CommonProfile());
        authorizers = NAME;
        config.setClients(new Clients(CALLBACK_URL, indirectClient));
        config.addAuthorizer(NAME, (context, prof) -> { throw new StatusAction(400); } );
        call();
        assertEquals(400, action.getCode());
        assertEquals(0, nbCall);
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDoubleDirectClient() {
        final CommonProfile profile = new CommonProfile();
        profile.setId(NAME);
        final CommonProfile profile2 = new CommonProfile();
        profile2.setId(VALUE);
        final DirectClient directClient = new MockDirectClient(NAME, Optional.of(new MockCredentials()), profile);
        final DirectClient directClient2 = new MockDirectClient(VALUE, Optional.of(new MockCredentials()), profile2);
        config.setClients(new Clients(CALLBACK_URL, directClient, directClient2));
        clients = NAME + "," + VALUE;
        call();
        assertEquals(-1, context.getResponseStatus());
        assertEquals(1, nbCall);
        final LinkedHashMap<String, CommonProfile> profiles =
            (LinkedHashMap<String, CommonProfile>) context.getRequestAttribute(Pac4jConstants.USER_PROFILES).get();
        assertEquals(1, profiles.size());
        assertTrue(profiles.containsValue(profile));
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDirectClientThrowsRequiresHttpAction() {
        final CommonProfile profile = new CommonProfile();
        profile.setId(NAME);
        final DirectClient directClient = new MockDirectClient(NAME, () -> { throw new StatusAction(400); },
            profile);
        config.setClients(new Clients(CALLBACK_URL, directClient));
        clients = NAME;
        call();
        assertEquals(400, action.getCode());
        assertEquals(0, nbCall);
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDoubleDirectClientSupportingMultiProfile() {
        final CommonProfile profile = new CommonProfile();
        profile.setId(NAME);
        final CommonProfile profile2 = new CommonProfile();
        profile2.setId(VALUE);
        final DirectClient directClient = new MockDirectClient(NAME, Optional.of(new MockCredentials()), profile);
        final DirectClient directClient2 = new MockDirectClient(VALUE, Optional.of(new MockCredentials()), profile2);
        config.setClients(new Clients(CALLBACK_URL, directClient, directClient2));
        clients = NAME + "," + VALUE;
        multiProfile = true;
        call();
        assertEquals(-1, context.getResponseStatus());
        assertEquals(1, nbCall);
        final LinkedHashMap<String, CommonProfile> profiles =
            (LinkedHashMap<String, CommonProfile>) context.getRequestAttribute(Pac4jConstants.USER_PROFILES).get();
        assertEquals(2, profiles.size());
        assertTrue(profiles.containsValue(profile));
        assertTrue(profiles.containsValue(profile2));
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDoubleDirectClientChooseDirectClient() {
        final CommonProfile profile = new CommonProfile();
        profile.setId(NAME);
        final CommonProfile profile2 = new CommonProfile();
        profile2.setId(VALUE);
        final DirectClient directClient = new MockDirectClient(NAME, Optional.of(new MockCredentials()), profile);
        final DirectClient directClient2 = new MockDirectClient(VALUE, Optional.of(new MockCredentials()), profile2);
        config.setClients(new Clients(CALLBACK_URL, directClient, directClient2));
        clients = NAME + "," + VALUE;
        context.addRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER, VALUE);
        multiProfile = true;
        call();
        assertEquals(-1, context.getResponseStatus());
        assertEquals(1, nbCall);
        final LinkedHashMap<String, CommonProfile> profiles =
            (LinkedHashMap<String, CommonProfile>) context.getRequestAttribute(Pac4jConstants.USER_PROFILES).get();
        assertEquals(1, profiles.size());
        assertTrue(profiles.containsValue(profile2));
        assertEquals(false, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDoubleDirectClientChooseBadDirectClient() {
        final CommonProfile profile = new CommonProfile();
        profile.setId(NAME);
        final CommonProfile profile2 = new CommonProfile();
        profile2.setId(VALUE);
        final DirectClient directClient = new MockDirectClient(NAME, Optional.of(new MockCredentials()), profile);
        final DirectClient directClient2 = new MockDirectClient(VALUE, Optional.of(new MockCredentials()), profile2);
        config.setClients(new Clients(CALLBACK_URL, directClient, directClient2));
        clients = NAME;
        context.addRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER, VALUE);
        multiProfile = true;
        call();
        assertEquals(401, action.getCode());
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testRedirectByIndirectClient() {
        final IndirectClient indirectClient =
            new MockIndirectClient(NAME, new FoundAction(PAC4J_URL), Optional.of(new MockCredentials()), new CommonProfile());
        config.setClients(new Clients(CALLBACK_URL, indirectClient));
        clients = NAME;
        call();
        assertEquals(302, action.getCode());
        assertEquals(PAC4J_URL, ((FoundAction) action).getLocation());
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDoubleIndirectClientOneChosen() {
        final IndirectClient indirectClient =
            new MockIndirectClient(NAME, new FoundAction(PAC4J_URL), Optional.of(new MockCredentials()), new CommonProfile());
        final IndirectClient indirectClient2 =
            new MockIndirectClient(VALUE, new FoundAction(PAC4J_BASE_URL), Optional.of(new MockCredentials()), new CommonProfile());
        config.setClients(new Clients(CALLBACK_URL, indirectClient, indirectClient2));
        clients = NAME + "," + VALUE;
        context.addRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER, VALUE);
        call();
        assertEquals(302, action.getCode());
        assertEquals(PAC4J_BASE_URL, ((FoundAction) action).getLocation());
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }

    @Test
    public void testDoubleIndirectClientBadOneChosen() {
        final IndirectClient indirectClient =
            new MockIndirectClient(NAME, new FoundAction(PAC4J_URL), Optional.of(new MockCredentials()), new CommonProfile());
        final IndirectClient indirectClient2 =
            new MockIndirectClient(VALUE, new FoundAction(PAC4J_BASE_URL), Optional.of(new MockCredentials()), new CommonProfile());
        config.setClients(new Clients(CALLBACK_URL, indirectClient, indirectClient2));
        clients = NAME;
        context.addRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER, VALUE);
        call();
        assertEquals(401, action.getCode());
        assertEquals(true, context.getRequestAttribute(Pac4jConstants.LOAD_PROFILES_FROM_SESSION).get());
    }
}
