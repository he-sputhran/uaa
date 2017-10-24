package org.cloudfoundry.identity.uaa.login;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import org.cloudfoundry.identity.uaa.mfa_provider.MfaProvider;
import org.cloudfoundry.identity.uaa.mfa_provider.UserGoogleMfaCredentials;
import org.cloudfoundry.identity.uaa.mfa_provider.UserGoogleMfaCredentialsProvisioning;
import org.cloudfoundry.identity.uaa.mock.InjectedMockContextTest;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.security.web.CookieBasedCsrfTokenRepository;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneConfiguration;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.servlet.http.Cookie;
import java.util.List;

import static org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.CookieCsrfPostProcessor.cookieCsrf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

public class TotpEndpointMockMvcTests extends InjectedMockContextTest{

    private String adminToken;
    private UserGoogleMfaCredentialsProvisioning userGoogleMfaCredentialsProvisioning;
    private JdbcScimUserProvisioning userProvisioning;
    private IdentityZoneConfiguration uaaZoneConfig;
    private MfaProvider mfaProvider;

    @Before
    public void setup() throws Exception {
        adminToken = testClient.getClientCredentialsOAuthAccessToken("admin", "adminsecret",
                "clients.read clients.write clients.secret clients.admin uaa.admin");
        userProvisioning = getWebApplicationContext().getBean(JdbcScimUserProvisioning.class);
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
    public void testMfaRedirect() throws Exception {
        ScimUser user = new ScimUser(null, new RandomValueStringGenerator(5).generate(), "first", "last");

        String password = "sec3Tas";
        user.setPrimaryEmail(user.getUserName());
        user.setPassword(password);
        user = createUser(user);

        MockHttpSession session = new MockHttpSession();
        String csrfValue = "12345";
        Cookie csrfCookie = new Cookie(CookieBasedCsrfTokenRepository.DEFAULT_CSRF_COOKIE_NAME, csrfValue);
        MockHttpServletRequestBuilder validPost = post("/login.do")
                .session(session)
                .param("username", user.getUserName())
                .param("password", password)
                .with(cookieCsrf());

        MockHttpServletResponse jsessionid = getMockMvc().perform(validPost)
                .andDo(print())
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login/mfa/register")).andReturn().getResponse();

        MockHttpServletResponse response = getMockMvc().perform(get("/profile")
                .session(session)).andReturn().getResponse();
        assertTrue(response.getRedirectedUrl().contains("/login"));
    }

    @Test
    public void testGoogleAuthenticatorLoginFlow() throws Exception {
        ScimUser user = new ScimUser(null, new RandomValueStringGenerator(5).generate(), "first", "last");

        String password = "sec3Tas";
        user.setPrimaryEmail(user.getUserName());
        user.setPassword(password);
        user = createUser(user);

        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequestBuilder validPost = post("/login.do")
                .session(session)
                .param("username", user.getUserName())
                .param("password", password)
                .with(cookieCsrf());

        MockHttpServletResponse jsessionid = getMockMvc().perform(validPost)
                .andDo(print())
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login/mfa/register"))
                .andReturn().getResponse();

        MockHttpServletResponse getQrResponse = getMockMvc().perform(get("/uaa/login/mfa/register")
                .session(session)
                .contextPath("/uaa"))
                .andExpect(view().name("qr_code"))
                .andReturn().getResponse();

        List<ScimUser> scimUsers = userProvisioning.query("userName eq \"" + user.getUserName() + "\"", IdentityZoneHolder.get().getId());
        UserGoogleMfaCredentials retrieved = userGoogleMfaCredentialsProvisioning.retrieve(scimUsers.get(0).getId());
        assertFalse(retrieved.isActive());
        String secretKey = retrieved.getSecretKey();
        GoogleAuthenticator authenticator = new GoogleAuthenticator(new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder().build());
       int code = authenticator.getTotpPassword(secretKey);


        MvcResult performTotp = getMockMvc().perform(post("/login/mfa/verify.do")
                .param("code", Integer.toString(code))
                .session(session)
                .with(cookieCsrf()))
                .andExpect(view().name("home"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpServletResponse response = getMockMvc().perform(get("/")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andReturn().getResponse();

        UserGoogleMfaCredentials activeCreds = userGoogleMfaCredentialsProvisioning.retrieveActive(scimUsers.get(0).getId());
        assertTrue(activeCreds.isActive());
        getMockMvc().perform(get("/uaa/logout.do").session(session)).andReturn();

        getMockMvc().perform(post("/login.do")
            .session(session)
            .param("username", user.getUserName())
            .param("password", password)
            .with(cookieCsrf()));

        getMockMvc().perform(get("/login/mfa/register")
            .session(session))
            .andExpect(view().name("redirect:/login/mfa/verify"))
            .andReturn();

        getMockMvc().perform(get("/login/mfa/verify")
            .session(session))
            .andExpect(view().name("enter_code"))
            .andReturn();

    }

    @Test
    public void testQRCodeRedirectIfCodeNotValidated()  throws Exception {
        ScimUser user = new ScimUser(null, new RandomValueStringGenerator(5).generate(), "first", "last");

        String password = "sec3Tas";
        user.setPrimaryEmail(user.getUserName());
        user.setPassword(password);
        user = createUser(user);

        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequestBuilder validPost = post("/login.do")
            .session(session)
            .param("username", user.getUserName())
            .param("password", password)
            .with(cookieCsrf());

        MockHttpServletResponse jsessionid = getMockMvc().perform(validPost)
            .andDo(print())
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/login/mfa/register")).andReturn().getResponse();

        MockHttpServletResponse getQrResponse = getMockMvc().perform(get("/uaa/login/mfa/register")
            .session(session)
            .contextPath("/uaa"))
            .andExpect(view().name("qr_code"))
            .andReturn().getResponse();

        getQrResponse = getMockMvc().perform(get("/uaa/login/mfa/register")
            .session(session)
            .contextPath("/uaa"))
            .andExpect(view().name("qr_code"))
            .andReturn().getResponse();

    }

    private ScimUser createUser(ScimUser user) throws Exception{
        return MockMvcUtils.createUser(getMockMvc(), adminToken, user);
    }
}
