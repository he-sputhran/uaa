package org.cloudfoundry.identity.uaa.mfa_provider;

import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.mfa_provider.exception.UserMfaConfigAlreadyExistsException;
import org.cloudfoundry.identity.uaa.mfa_provider.exception.UserMfaConfigDoesNotExistException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpSession;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UserGoogleMfaCredentialsProvisioningTest {

    UserGoogleMfaCredentialsProvisioning provisioner;
    JdbcUserGoogleMfaCredentialsProvisioning jdbcProvisioner;

    @Before
    public void setup() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        provisioner = new UserGoogleMfaCredentialsProvisioning();

        jdbcProvisioner = mock(JdbcUserGoogleMfaCredentialsProvisioning.class);
        provisioner.setJdbcProvisioner(jdbcProvisioner);

        ((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getSession(true);
    }

    @Test
    public void testSavesCredentialsNotInDatabase() {
        UserGoogleMfaCredentials creds = creds();

        provisioner.saveUserCredentials(creds.getUserId(), creds.getSecretKey(), creds.getValidationCode(), creds.getScratchCodes());

        verify(jdbcProvisioner, times(0)).save(any());
        assertEquals(creds, session().getAttribute("SESSION_USER_GOOGLE_MFA_CREDENTIALS"));
    }

    @Test
    public void testSaveUserCredentials_updatesWhenUserExists() {
        UserGoogleMfaCredentials creds = creds();
        provisioner.saveUserCredentials(creds.getUserId(), creds.getSecretKey(), creds.getValidationCode(), creds.getScratchCodes());

        UserGoogleMfaCredentials updatedCreds = new UserGoogleMfaCredentials("jabbahut",
            "different_key",
            45678,
            Arrays.asList(1,22));

        provisioner.saveUserCredentials(updatedCreds.getUserId(), updatedCreds.getSecretKey(), updatedCreds.getValidationCode(), updatedCreds.getScratchCodes());

        verify(jdbcProvisioner, times(0)).save(any());

        assertEquals(updatedCreds, session().getAttribute("SESSION_USER_GOOGLE_MFA_CREDENTIALS"));

    }

    @Test
    public void testPersist() {
        UserGoogleMfaCredentials creds = creds();
        provisioner.saveUserCredentials(creds.getUserId(), creds.getSecretKey(), creds.getValidationCode(), creds.getScratchCodes());
        verify(jdbcProvisioner, times(0)).save(any());

        provisioner.persistCredentials();

        verify(jdbcProvisioner, times(1)).save(eq(creds));
        assertNull(session().getAttribute("SESSION_USER_GOOGLE_MFA_CREDENTIALS"));
    }

    @Test
    public void testPersist_emptySession() {
        provisioner.persistCredentials();
        verify(jdbcProvisioner, times(0)).save(any());
        //assume that creds are already in database if session doesn't exist
    }

    @Test(expected = UserMfaConfigAlreadyExistsException.class)
    public void testPersist_ErrorsIfAlreadyExists() {
        UserGoogleMfaCredentials creds = creds();
        provisioner.saveUserCredentials(creds.getUserId(), creds.getSecretKey(), creds.getValidationCode(), creds.getScratchCodes());
        verify(jdbcProvisioner, times(0)).save(any());

        provisioner.persistCredentials();

        doThrow(UserMfaConfigAlreadyExistsException.class).when(jdbcProvisioner).save(any());
        provisioner.saveUserCredentials(creds.getUserId(), creds.getSecretKey(), creds.getValidationCode(), creds.getScratchCodes());
        provisioner.persistCredentials();
    }

    @Test
    public void testActiveUserCredentialExists() {
        UserGoogleMfaCredentials creds = creds();
        when(jdbcProvisioner.retrieve(anyString())).thenThrow(UserMfaConfigDoesNotExistException.class).thenThrow(UserMfaConfigDoesNotExistException.class).thenReturn(creds);

        assertFalse("no user in db but activeCredentialExists returned true", provisioner.activeUserCredentialExists("jabbahut"));

        provisioner.saveUserCredentials(creds.getUserId(), creds.getSecretKey(), creds.getValidationCode(), creds.getScratchCodes());
        assertFalse("no user in db but activeCredentialExists returned true", provisioner.activeUserCredentialExists("jabbahut"));

        provisioner.persistCredentials();
        assertTrue("user not shown as active after persisting", provisioner.activeUserCredentialExists("jabbahut"));

    }

    @Test
    public void testGetSecretKey() {
        UserGoogleMfaCredentials creds = creds();
        session().setAttribute("SESSION_USER_GOOGLE_MFA_CREDENTIALS", creds);

        String key = provisioner.getSecretKey("jabbahut");
        assertEquals("very_sercret_key", key);
    }


    @Test
    public void testGetSecretKey_NotExistsInSession() {
        UserGoogleMfaCredentials creds = creds();

        when(jdbcProvisioner.retrieve(anyString())).thenReturn(creds);

        String key = provisioner.getSecretKey("jabbahut");
        assertEquals("very_sercret_key", key);
    }

    @Test
    public void isFirstTimeMFAUser() {
        UaaPrincipal uaaPrincipal = mock(UaaPrincipal.class);
        session().setAttribute("SESSION_USER_GOOGLE_MFA_CREDENTIALS", creds());

        assertTrue(provisioner.isFirstTimeMFAUser(uaaPrincipal));
    }

    @Test
    public void isFirstTimeMFAUser_CredsAreNotInSession() {
        UaaPrincipal uaaPrincipal = mock(UaaPrincipal.class);
        assertFalse(provisioner.isFirstTimeMFAUser(uaaPrincipal));
    }

    @Test(expected = RuntimeException.class)
    public void isFirstTimeMFAUser_failsIfNotParitallyLoggedIn() {
        provisioner.isFirstTimeMFAUser(null);
    }

    private UserGoogleMfaCredentials creds() {
        return new UserGoogleMfaCredentials("jabbahut",
            "very_sercret_key",
            74718234,
            Arrays.asList(1,22));
    }

    private HttpSession session() {
        return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest().getSession(false);
    }
}