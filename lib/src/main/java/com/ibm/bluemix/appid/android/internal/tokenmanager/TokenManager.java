/*
	Copyright 2017 IBM Corp.
	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at
	http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/

package com.ibm.bluemix.appid.android.internal.tokenmanager;

import android.util.Base64;

import com.ibm.bluemix.appid.android.api.AppID;
import com.ibm.bluemix.appid.android.api.AuthorizationException;
import com.ibm.bluemix.appid.android.api.AuthorizationListener;
import com.ibm.bluemix.appid.android.api.TokenResponseListener;
import com.ibm.bluemix.appid.android.api.tokens.AccessToken;
import com.ibm.bluemix.appid.android.api.tokens.IdentityToken;
import com.ibm.bluemix.appid.android.internal.OAuthManager;
import com.ibm.bluemix.appid.android.internal.config.Config;
import com.ibm.bluemix.appid.android.internal.network.AppIDRequest;
import com.ibm.bluemix.appid.android.internal.registrationmanager.RegistrationManager;
import com.ibm.bluemix.appid.android.internal.tokens.AccessTokenImpl;
import com.ibm.bluemix.appid.android.internal.tokens.IdentityTokenImpl;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.Response;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.ResponseListener;
import com.ibm.mobilefirstplatform.clientsdk.android.logger.api.Logger;

import org.json.JSONObject;

import java.security.PrivateKey;
import java.security.Signature;
import java.util.HashMap;

public class TokenManager {

	private final AppID appId;
	private final RegistrationManager registrationManager;

	private AccessToken latestAccessToken;
	private IdentityToken latestIdentityToken;

	private static final Logger logger = Logger.getLogger(Logger.INTERNAL_PREFIX + TokenManager.class.getName());

	private static final String OAUTH_TOKEN_PATH = "/token";
	private final static String CLIENT_ID = "client_id";
	private final static String GRANT_TYPE = "grant_type";
	private final static String GRANT_TYPE_AUTH_CODE = "authorization_code";
	private final static String CODE = "code";
	private final static String REDIRECT_URI = "redirect_uri";
	private final static String AUTHORIZATION_HEADER = "Authorization";
    private final static String USERNAME = "username";
    private final static String PASSWORD = "password";
    private final static String GRANT_TYPE_PASSWORD = "password";
    private final static String ERROR_DESCRIPTION= "error_description";
    private final static String ERROR_CODE= "error";
    private final static String INVALID_GRANT= "invalid_grant";

	public TokenManager (OAuthManager oAuthManager) {
		this.appId = oAuthManager.getAppId();
		this.registrationManager = oAuthManager.getRegistrationManager();
	}

	public void obtainTokens (String code, final AuthorizationListener listener) {
		logger.debug("obtainTokens");
		String clientId = registrationManager.getRegistrationDataString(RegistrationManager.CLIENT_ID);
		String redirectUri = registrationManager.getRegistrationDataString(RegistrationManager.REDIRECT_URIS, 0);

		HashMap<String, String> formParams = new HashMap<>();
		formParams.put(CODE, code);
		formParams.put(CLIENT_ID, clientId);
		formParams.put(GRANT_TYPE, GRANT_TYPE_AUTH_CODE);
		formParams.put(REDIRECT_URI, redirectUri);

		retrieveTokens(formParams, listener);
	}

	//for testing purpose
    AppIDRequest createAppIDRequest(String url, String method) {
		return new AppIDRequest(url, method);
	}

	private void retrieveTokens(HashMap<String, String> formParams, final TokenResponseListener listener) {
		String tokenUrl = Config.getOAuthServerUrl(appId) + OAUTH_TOKEN_PATH;
		String clientId = registrationManager.getRegistrationDataString(RegistrationManager.CLIENT_ID);

		AppIDRequest request = createAppIDRequest(tokenUrl, "POST");

		try {
			request.addHeader(AUTHORIZATION_HEADER, createAuthenticationHeader(clientId));
		} catch (Exception e) {
			logger.error("Failed to create authentication header", e);
			return;
		}

		request.send(formParams, new ResponseListener() {
			@Override
			public void onFailure (Response response, Throwable t, JSONObject extendedInfo) {
				logger.error("Failed to retrieve tokens from authorization server", t);
				String errorDescription = "";
				try {
					if (response.getStatus() == 400) {
                        JSONObject responseJSON = new JSONObject(response.getResponseText());
                        if (INVALID_GRANT.equals(responseJSON.getString(ERROR_CODE))) {
                            errorDescription = responseJSON.getString(ERROR_DESCRIPTION);
                            listener.onAuthorizationFailure(new AuthorizationException("Failed to retrieve tokens: " + errorDescription));
                            return;
                        }
                    }
                    listener.onAuthorizationFailure(new AuthorizationException("Failed to retrieve tokens"));
				} catch (Exception e) {
					logger.error("Failed to retrieve tokens from authorization server", t);
					listener.onAuthorizationFailure(new AuthorizationException("Failed to retrieve tokens"));
				}
			}

			@Override
			public void onSuccess (Response response) {
				extractTokens(response, listener);
			}
		});
	}

	public void obtainTokens (String username, String password, final TokenResponseListener listener) {
		logger.debug("obtainTokens - with resource owner password");

		HashMap<String, String> formParams = new HashMap<>();
        formParams.put(USERNAME, username);
        formParams.put(PASSWORD, password);
        formParams.put(GRANT_TYPE, GRANT_TYPE_PASSWORD);

		retrieveTokens(formParams, listener);
	}

	private String createAuthenticationHeader (String clientId) throws Exception {
		PrivateKey privateKey = registrationManager.getPrivateKey();
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(privateKey);
		signature.update(clientId.getBytes());
		String encodedClientId = Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
		return "Basic " + Base64.encodeToString((clientId + ":" + encodedClientId).getBytes(), Base64.NO_WRAP);
	}

	/**
	 * Extract token from response and save it locally
	 *
	 * @param response response that contain the token
	 */
	private void extractTokens (Response response, TokenResponseListener tokenResponseListener) {
		String accessTokenString;
		String idTokenString;
		AccessToken accessToken;
		IdentityToken identityToken;

		logger.debug("Extracting tokens from server response");

		try {
			JSONObject responseJSON = new JSONObject(response.getResponseText());
			accessTokenString = responseJSON.getString("access_token");
			idTokenString = responseJSON.getString("id_token");
		} catch (Exception e){
			logger.error("Failed to parse server response", e);
			tokenResponseListener.onAuthorizationFailure(new AuthorizationException("Failed to parse server response"));
			return;
		}

		try {
			accessToken = new AccessTokenImpl(accessTokenString);
		} catch (RuntimeException e){
			logger.error("Failed to parse access_token", e);
			tokenResponseListener.onAuthorizationFailure(new AuthorizationException("Failed to parse access_token"));
			return;
		}

		try {
			identityToken = new IdentityTokenImpl(idTokenString);
		} catch (RuntimeException e){
			clearStoredTokens();
			logger.error("Failed to parse id_token", e);
			tokenResponseListener.onAuthorizationFailure(new AuthorizationException("Failed to parse id_token"));
			return;
		}

		latestAccessToken = accessToken;
		latestIdentityToken = identityToken;

		tokenResponseListener.onAuthorizationSuccess(accessToken, identityToken);
	}

	public AccessToken getLatestAccessToken () {
		return latestAccessToken;
	}

	public IdentityToken getLatestIdentityToken () {
		return latestIdentityToken;
	}

	public void clearStoredTokens(){
		latestAccessToken = null;
		latestIdentityToken = null;
	}
}
