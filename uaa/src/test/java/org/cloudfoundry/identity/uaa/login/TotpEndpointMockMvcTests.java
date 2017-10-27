package org.cloudfoundry.identity.uaa.login;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import org.cloudfoundry.identity.uaa.mfa_provider.MfaProvider;
import org.cloudfoundry.identity.uaa.mfa_provider.UserGoogleMfaCredentials;
import org.cloudfoundry.identity.uaa.mfa_provider.UserGoogleMfaCredentialsProvisioning;
import org.cloudfoundry.identity.uaa.mock.InjectedMockContextTest;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.test.web.servlet.ResultActions;

import static org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.CookieCsrfPostProcessor.cookieCsrf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

public class TotpEndpointMockMvcTests extends InjectedMockContextTest{

    private String adminToken;
    private UserGoogleMfaCredentialsProvisioning userGoogleMfaCredentialsProvisioning;
    private IdentityZoneConfiguration uaaZoneConfig;
    private MfaProvider mfaProvider;
    private String password;

    @Before
    public void setup() throws Exception {
        adminToken = testClient.getClientCredentialsOAuthAccessToken("admin", "adminsecret",
                "clients.read clients.write clients.secret clients.admin uaa.admin");
        userGoogleMfaCredentialsProvisioning = (UserGoogleMfaCredentialsProvisioning) getWebApplicationContext().getBean("userGoogleMfaCredentialsProvisioning");

        mfaProvider = new MfaProvider();
        mfaProvider = JsonUtils.readValue(getMockMvc().perform(
                post("/mfa-providers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(JsonUtils.writeValueAsString(mfaProvider))).andReturn().getResponse().getContentAsByteArray(), MfaProvider.class);

        uaaZoneConfig = MockMvcUtils.getZoneConfiguration(getWebApplicationContext(), "uaa");
        uaaZoneConfig.getMfaConfig().setEnabled(true).setProviderId(mfaProvider.getId());
        MockMvcUtils.setZoneConfiguration(getWebApplicationContext(), "uaa", uaaZoneConfig);

    }

    @Test
    public void testRedirectToMfaAfterLogin() throws Exception {
        ScimUser user = createUser();

        MockHttpSession session = new MockHttpSession();

        performLoginWithSession(user.getUserName(), password, session).andExpect(redirectedUrl("/login/mfa/register"));

        MockHttpServletResponse response = getMockMvc().perform(get("/profile")
                .session(session)).andReturn().getResponse();
        assertTrue(response.getRedirectedUrl().contains("/login"));
    }

    @Test
    public void testGoogleAuthenticatorLoginFlow() throws Exception {
        ScimUser user = createUser();
        MockHttpSession session = new MockHttpSession();
        performLoginWithSession(user.getUserName(), password, session).andExpect(redirectedUrl("/login/mfa/register"));
        performGetMfaRegister(session).andExpect(view().name("qr_code"));

        UserGoogleMfaCredentials retrieved = userGoogleMfaCredentialsProvisioning.retrieve(user.getId());
        assertFalse(retrieved.isActive());
        String secretKey = retrieved.getSecretKey();
        GoogleAuthenticator authenticator = new GoogleAuthenticator(new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder().build());
        int code = authenticator.getTotpPassword(secretKey);

        performPostVerifyWithCode(session, code)
                .andExpect(view().name("home"));


        getMockMvc().perform(get("/")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("home"));
    }

    @Test
    public void testQRCodeRedirectIfCodeValidated()  throws Exception {
        ScimUser user = createUser();

        MockHttpSession session = new MockHttpSession();
        performLoginWithSession(user.getUserName(), password, session)
            .andExpect(redirectedUrl("/login/mfa/register"));

        performGetMfaRegister(session).andExpect(view().name("qr_code"));

        UserGoogleMfaCredentials activeCreds = userGoogleMfaCredentialsProvisioning.retrieve(user.getId());
        GoogleAuthenticator authenticator = new GoogleAuthenticator(new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder().build());
        int code = authenticator.getTotpPassword(activeCreds.getSecretKey());

        performPostVerifyWithCode(session, code)
            .andExpect(view().name("home"));

        activeCreds = userGoogleMfaCredentialsProvisioning.retrieve(user.getId());
        assertTrue(activeCreds.isActive());

        getMockMvc().perform(get("/logout.do")).andReturn();

        session = new MockHttpSession();
        performLoginWithSession(user.getUserName(), password, session);

        performGetMfaRegister(session).andExpect(redirectedUrl("/uaa/login/mfa/verify"));
    }

    @Test
    public void testQRCodeRedirectIfCodeNotValidated()  throws Exception {
        ScimUser user = createUser();

        MockHttpSession session = new MockHttpSession();
        performLoginWithSession(user.getUserName(), password, session).andExpect(redirectedUrl("/login/mfa/register"));

        performGetMfaRegister(session).andExpect(view().name("qr_code"));

        UserGoogleMfaCredentials inActiveCreds = userGoogleMfaCredentialsProvisioning.retrieve(user.getId());
        assertFalse(inActiveCreds.isActive());

        performGetMfaRegister(session).andExpect(view().name("qr_code"));
    }

    private ScimUser createUser() throws Exception{
        ScimUser user = new ScimUser(null, new RandomValueStringGenerator(5).generate(), "first", "last");

        password = "sec3Tas";
        user.setPrimaryEmail(user.getUserName());
        user.setPassword(password);
        return MockMvcUtils.createUser(getMockMvc(), adminToken, user);
    }

    private ResultActions performLoginWithSession(String userName, String password, MockHttpSession session) throws Exception {
        return getMockMvc().perform( post("/login.do")
            .session(session)
            .param("username", userName)
            .param("password", password)
            .with(cookieCsrf()))
            .andDo(print())
            .andExpect(status().isFound());
    }

    private ResultActions performPostVerifyWithCode(MockHttpSession session, int code) throws Exception {
        return getMockMvc().perform(post("/login/mfa/verify.do")
            .param("code", Integer.toString(code))
            .session(session)
            .with(cookieCsrf()))
            .andExpect(status().isOk());
    }

    private ResultActions performGetMfaRegister(MockHttpSession session) throws Exception {
        return getMockMvc().perform(get("/uaa/login/mfa/register")
            .session(session)
            .contextPath("/uaa"));
    }
}
