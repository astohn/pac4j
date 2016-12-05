package org.pac4j.saml.client;

import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import net.shibboleth.utilities.java.support.resolver.ResolverException;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.metadata.resolver.ChainingMetadataResolver;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.encryption.Decrypter;
import org.pac4j.core.client.IndirectClient;
import org.pac4j.core.client.RedirectAction;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.profile.definition.CommonProfileDefinition;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.saml.context.SAML2ContextProvider;
import org.pac4j.saml.context.SAML2MessageContext;
import org.pac4j.saml.context.SAMLContextProvider;
import org.pac4j.saml.credentials.SAML2Credentials;
import org.pac4j.saml.crypto.CredentialProvider;
import org.pac4j.saml.crypto.DefaultSignatureSigningParametersProvider;
import org.pac4j.saml.crypto.ExplicitSignatureTrustEngineProvider;
import org.pac4j.saml.crypto.KeyStoreCredentialProvider;
import org.pac4j.saml.crypto.KeyStoreDecryptionProvider;
import org.pac4j.saml.crypto.SAML2SignatureTrustEngineProvider;
import org.pac4j.saml.crypto.SignatureSigningParametersProvider;
import org.pac4j.saml.metadata.SAML2IdentityProviderMetadataResolver;
import org.pac4j.saml.metadata.SAML2MetadataResolver;
import org.pac4j.saml.metadata.SAML2ServiceProviderMetadataResolver;
import org.pac4j.saml.profile.SAML2Profile;
import org.pac4j.saml.sso.SAML2ObjectBuilder;
import org.pac4j.saml.sso.SAML2ProfileHandler;
import org.pac4j.saml.sso.SAML2ResponseValidator;
import org.pac4j.saml.sso.impl.SAML2AuthnRequestBuilder;
import org.pac4j.saml.sso.impl.SAML2DefaultResponseValidator;
import org.pac4j.saml.sso.impl.SAML2LogoutRequestBuilder;
import org.pac4j.saml.sso.impl.SAML2LogoutMessageSender;
import org.pac4j.saml.sso.impl.SAML2LogoutProfileHandler;
import org.pac4j.saml.sso.impl.SAML2LogoutResponseValidator;
import org.pac4j.saml.sso.impl.SAML2WebSSOMessageReceiver;
import org.pac4j.saml.sso.impl.SAML2WebSSOMessageSender;
import org.pac4j.saml.sso.impl.SAML2WebSSOProfileHandler;
import org.pac4j.saml.transport.Pac4jSAMLResponse;
import org.pac4j.saml.util.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is the client to authenticate users with a SAML2 Identity Provider. This implementation relies on the Web
 * Browser SSO profile with HTTP-POST binding. (http://docs.oasis-open.org/security/saml/v2.0/saml-profiles-2.0-os.pdf).
 *
 * @author Michael Remond
 * @author Misagh Moayyed
 * @author Ruochao Zheng
 * @since 1.5.0
 */
public class SAML2Client extends IndirectClient<SAML2Credentials, SAML2Profile> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    public static final String SAML_RELAY_STATE_ATTRIBUTE = "samlRelayState";
    public static final String SAML_CONDITION_NOT_BEFORE_ATTRIBUTE = "notBefore";
    public static final String SAML_CONDITION_NOT_ON_OR_AFTER_ATTRIBUTE = "notOnOrAfter";
    public static final String SESSION_INDEX = "sessionindex";

    protected CredentialProvider credentialProvider;

    protected SAMLContextProvider contextProvider;

    protected SAML2ObjectBuilder<AuthnRequest> saml2ObjectBuilder;
    
    protected SAML2ObjectBuilder<LogoutRequest> saml2LogoutObjectBuilder;

    protected SignatureSigningParametersProvider signatureSigningParametersProvider;

    protected SAML2ProfileHandler<AuthnRequest> profileHandler;
    
    protected SAML2ProfileHandler<LogoutRequest> logoutProfileHandler;

    protected SAML2ResponseValidator responseValidator;
    
    protected SAML2ResponseValidator logoutResponseValidator;

    protected SAML2SignatureTrustEngineProvider signatureTrustEngineProvider;

    protected SAML2MetadataResolver idpMetadataResolver;

    protected SAML2MetadataResolver spMetadataResolver;

    protected Decrypter decrypter;

    protected SAML2ClientConfiguration configuration;

    static {
        CommonHelper.assertNotNull("parserPool", Configuration.getParserPool());
        CommonHelper.assertNotNull("marshallerFactory", Configuration.getMarshallerFactory());
        CommonHelper.assertNotNull("unmarshallerFactory", Configuration.getUnmarshallerFactory());
        CommonHelper.assertNotNull("builderFactory", Configuration.getBuilderFactory());
    }

    public SAML2Client() { }

    public SAML2Client(final SAML2ClientConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void internalInit(final WebContext context) {
        CommonHelper.assertNotBlank("callbackUrl", this.callbackUrl);
        CommonHelper.assertNotNull("configuration", this.configuration); 
        setProfileDefinition(new CommonProfileDefinition<>(x -> new SAML2Profile()));

        // First of all, initialize the configuration. It may dynamically load some properties, if it is not a static one.
        this.configuration.init(getName(), context); 
        
        initCredentialProvider();
        initDecrypter();
        initSignatureSigningParametersProvider();
        final MetadataResolver metadataManager = initChainingMetadataResolver(
                initIdentityProviderMetadataResolver(),
                initServiceProviderMetadataResolver(context));
        initSAMLContextProvider(metadataManager);
        initSAMLObjectBuilder();
        initSAMLLogoutObjectBuilder();
        initSignatureTrustEngineProvider(metadataManager);
        initSAMLResponseValidator();
        initSAMLProfileHandler();
        initSAMLLogoutProfileHandler();
    }

    protected void initSAMLProfileHandler() {
        this.profileHandler = new SAML2WebSSOProfileHandler(
                new SAML2WebSSOMessageSender(this.signatureSigningParametersProvider,
                        this.configuration.getDestinationBindingType(), 
                        this.configuration.isForceSignRedirectBindingAuthnRequest()),
                new SAML2WebSSOMessageReceiver(this.responseValidator));
    }
    
    protected void initSAMLLogoutProfileHandler() {
    	this.logoutProfileHandler = new SAML2LogoutProfileHandler(
    			new SAML2LogoutMessageSender(this.signatureSigningParametersProvider,
    					this.configuration.getDestinationBindingType(),
    					false, this.configuration.isForceSignRedirectBindingAuthnRequest()),
    			new SAML2WebSSOMessageReceiver(this.logoutResponseValidator));
    }

    protected void initSAMLResponseValidator() {
        // Build the SAML response validator
        this.responseValidator = new SAML2DefaultResponseValidator(
                this.signatureTrustEngineProvider,
                this.decrypter,
                this.configuration.getMaximumAuthenticationLifetime(),
                this.configuration.getWantsAssertionsSigned());
    }
    
    protected void initSAMLLogoutResponseValidator() {
    	this.logoutResponseValidator = new SAML2LogoutResponseValidator(this.signatureTrustEngineProvider);
    }

    protected void initSignatureTrustEngineProvider(final MetadataResolver metadataManager) {
        // Build provider for digital signature validation and encryption
        this.signatureTrustEngineProvider = new ExplicitSignatureTrustEngineProvider(metadataManager);
    }

    protected void initSAMLObjectBuilder() {
        this.saml2ObjectBuilder = new SAML2AuthnRequestBuilder(this.configuration.isForceAuth(),
                this.configuration.getComparisonType(),
                this.configuration.getDestinationBindingType(),
                this.configuration.getAuthnContextClassRef(),
                this.configuration.getNameIdPolicyFormat());
    }
    
    protected void initSAMLLogoutObjectBuilder() {
    	this.saml2LogoutObjectBuilder = new SAML2LogoutRequestBuilder(this.configuration.getDestinationBindingType());
    }

    protected void initSAMLContextProvider(final MetadataResolver metadataManager) {
        // Build the contextProvider
        this.contextProvider = new SAML2ContextProvider(metadataManager,
                this.idpMetadataResolver, this.spMetadataResolver,
                this.configuration.getSamlMessageStorageFactory());
    }

    protected MetadataResolver initServiceProviderMetadataResolver(final WebContext context) {
		this.spMetadataResolver = new SAML2ServiceProviderMetadataResolver(this.configuration,
				computeFinalCallbackUrl(context),
                this.credentialProvider);
        return this.spMetadataResolver.resolve();
    }

    protected MetadataResolver initIdentityProviderMetadataResolver() {
        this.idpMetadataResolver = new SAML2IdentityProviderMetadataResolver(this.configuration);
        return this.idpMetadataResolver.resolve();
    }

    protected void initCredentialProvider() {
        this.credentialProvider = new KeyStoreCredentialProvider(this.configuration);
    }

    protected void initDecrypter() {
        this.decrypter = new KeyStoreDecryptionProvider(this.credentialProvider).build();
    }

    protected void initSignatureSigningParametersProvider() {
        this.signatureSigningParametersProvider = new DefaultSignatureSigningParametersProvider(
                this.credentialProvider, this.configuration);
    }

    protected ChainingMetadataResolver initChainingMetadataResolver(final MetadataResolver idpMetadataProvider,
                                                                          final MetadataResolver spMetadataProvider) {
        final ChainingMetadataResolver metadataManager = new ChainingMetadataResolver();
        metadataManager.setId(ChainingMetadataResolver.class.getCanonicalName());
        try {
            final List<MetadataResolver> list = new ArrayList<>();
            list.add(idpMetadataProvider);
            list.add(spMetadataProvider);
            metadataManager.setResolvers(list);
            metadataManager.initialize();
        } catch (final ResolverException e) {
            throw new TechnicalException("Error adding idp or sp metadatas to manager", e);
        } catch (final ComponentInitializationException e) {
            throw new TechnicalException("Error initializing manager", e);
        }
        return metadataManager;
    }

    @Override
    protected RedirectAction retrieveRedirectAction(final WebContext wc) throws HttpAction {
        final SAML2MessageContext context = this.contextProvider.buildContext(wc);
        final String relayState = getStateParameter(wc);

        final AuthnRequest authnRequest = this.saml2ObjectBuilder.build(context);
        this.profileHandler.send(context, authnRequest, relayState);

        final Pac4jSAMLResponse adapter = context.getProfileRequestContextOutboundMessageTransportResponse();
        if (this.configuration.getDestinationBindingType().equalsIgnoreCase(SAMLConstants.SAML2_POST_BINDING_URI)) {
            final String content = adapter.getOutgoingContent();
            return RedirectAction.success(content);
        }
        final String location = adapter.getRedirectUrl();
        return RedirectAction.redirect(location);

    }

    @Override
    protected RedirectAction retrieveLogoutRedirectAction(final WebContext context) {
        final SAML2MessageContext samlContext = this.contextProvider.buildContext(context);
        final String relayState = getStateParameter(context);

        final LogoutRequest logoutRequest = this.saml2LogoutObjectBuilder.build(samlContext);
        this.logoutProfileHandler.send(samlContext, logoutRequest, relayState);

        final Pac4jSAMLResponse adapter = samlContext.getProfileRequestContextOutboundMessageTransportResponse();
        if (this.configuration.getDestinationBindingType().equalsIgnoreCase(SAMLConstants.SAML2_POST_BINDING_URI)) {
            final String content = adapter.getOutgoingContent();
            return RedirectAction.success(content);
        }
        final String location = adapter.getRedirectUrl();
        return RedirectAction.redirect(location);
    }

    @Override
    protected SAML2Credentials retrieveCredentials(final WebContext wc) throws HttpAction {
        final SAML2MessageContext context = this.contextProvider.buildContext(wc);
        final SAML2Credentials credentials = (SAML2Credentials) this.profileHandler.receive(context);
        // The profile handler sets a hard-coded client name, we need the real one.
        credentials.setClientName(getName());
        return credentials;
    }

    @Override
    protected SAML2Profile retrieveUserProfile(final SAML2Credentials credentials, final WebContext context) throws HttpAction {
        final SAML2Profile profile = getProfileDefinition().newProfile();
        profile.setId(credentials.getNameId().getValue());
        profile.addAttribute(SESSION_INDEX, credentials.getSessionIndex());
        for (final Attribute attribute : credentials.getAttributes()) {
            logger.debug("Processing profile attribute {}", attribute);

            final List<String> values = new ArrayList<>();
            for (final XMLObject attributeValue : attribute.getAttributeValues()) {
                final Element attributeValueElement = attributeValue.getDOM();
                if (attributeValueElement != null) {
                    final String value = attributeValueElement.getTextContent();
                    logger.debug("Adding attribute value {} for attribute {}", value,
                            attribute.getFriendlyName());
                    values.add(value);
                } else {
                    logger.warn("Attribute value DOM element is null for {}", attribute);
                }
            }

            if (!values.isEmpty()) {
                getProfileDefinition().convertAndAdd(profile, attribute.getName(), values);
            } else {
                logger.debug("No attribute values found for {}", attribute.getName());
            }
        }
        
        // Retrieve conditions attributes
        Conditions conditions = credentials.getConditions(); 
        if (conditions != null) {            
            profile.addAttribute(SAML_CONDITION_NOT_BEFORE_ATTRIBUTE, conditions.getNotBefore());
            profile.addAttribute(SAML_CONDITION_NOT_ON_OR_AFTER_ATTRIBUTE, conditions.getNotOnOrAfter());
        }

        return profile;
    }

    @Override
    protected String getStateParameter(final WebContext webContext) {
        final String relayState = (String) webContext.getSessionAttribute(SAML_RELAY_STATE_ATTRIBUTE);
        // clean from session after retrieving it
        webContext.setSessionAttribute(SAML_RELAY_STATE_ATTRIBUTE, "");
        return (relayState == null) ? computeFinalCallbackUrl(webContext) : relayState;
    }

    public final SAML2ResponseValidator getResponseValidator() {
        return this.responseValidator;
    }

    public final SAML2MetadataResolver getServiceProviderMetadataResolver() {
        return this.spMetadataResolver;
    }

    public final SAML2MetadataResolver getIdentityProviderMetadataResolver() {
        return this.idpMetadataResolver;
    }

    public final String getIdentityProviderResolvedEntityId() {
        return this.idpMetadataResolver.getEntityId();
    }

    public final String getServiceProviderResolvedEntityId() {
        return this.spMetadataResolver.getEntityId();
    }

    public void setConfiguration(final SAML2ClientConfiguration configuration) {
        this.configuration = configuration;
    }

    public final SAML2ClientConfiguration getConfiguration() {
        return this.configuration;
    }
}
