/*
 ===========================================================================
 Copyright (c) 2010 BrickRed Technologies Limited

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sub-license, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 ===========================================================================

 */

package org.brickred.socialauth.provider;

import java.io.Serializable;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.brickred.socialauth.AbstractProvider;
import org.brickred.socialauth.AuthProvider;
import org.brickred.socialauth.Contact;
import org.brickred.socialauth.Permission;
import org.brickred.socialauth.Profile;
import org.brickred.socialauth.exception.ProviderStateException;
import org.brickred.socialauth.exception.ServerDataException;
import org.brickred.socialauth.exception.SocialAuthConfigurationException;
import org.brickred.socialauth.exception.SocialAuthException;
import org.brickred.socialauth.util.Constants;
import org.brickred.socialauth.util.MethodType;
import org.brickred.socialauth.util.OAuthConfig;
import org.brickred.socialauth.util.OAuthConsumer;
import org.brickred.socialauth.util.Response;
import org.brickred.socialauth.util.Token;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Twitter implementation of the provider.
 * 
 * @author tarunn@brickred.com
 * 
 */

public class TwitterImpl extends AbstractProvider implements AuthProvider,
		Serializable {

	private static final long serialVersionUID = 1908393649053616794L;
	private static final String REQUEST_TOKEN_URL = "http://api.twitter.com/oauth/request_token";
	private static final String AUTHORIZATION_URL = "https://api.twitter.com/oauth/authorize";
	private static final String ACCESS_TOKEN_URL = "https://api.twitter.com/oauth/access_token";
	private static final String PROFILE_URL = "http://api.twitter.com/1/users/show.json?screen_name=";
	private static final String CONTACTS_URL = "http://api.twitter.com/1/friends/ids.json?screen_name=%1$s&cursor=-1";
	private static final String LOOKUP_URL = "http://api.twitter.com/1/users/lookup.json?user_id=";
	private static final String UPDATE_STATUS_URL = "http://api.twitter.com/1/statuses/update.json?status=";
	private static final String PROPERTY_DOMAIN = "twitter.com";
	private final Log LOG = LogFactory.getLog(TwitterImpl.class);

	private Permission scope;
	private Properties properties;
	private boolean isVerify;
	private Token requestToken;
	private Token accessToken;
	private OAuthConfig config;
	private OAuthConsumer oauth;
	private Profile userProfile;

	public TwitterImpl(final Properties props) throws Exception {
		try {
			this.properties = props;
			config = OAuthConfig.load(this.properties, PROPERTY_DOMAIN);
		} catch (IllegalStateException e) {
			throw new SocialAuthConfigurationException(e);
		}
		if (config.get_consumerSecret().length() == 0) {
			throw new SocialAuthConfigurationException(
					"twitter.com.consumer_secret value is null");
		}
		if (config.get_consumerKey().length() == 0) {
			throw new SocialAuthConfigurationException(
					"twitter.com.consumer_key value is null");
		}
		oauth = new OAuthConsumer(config);
	}

	/**
	 * This is the most important action. It redirects the browser to an
	 * appropriate URL which will be used for authentication with the provider
	 * that has been set using setId()
	 * 
	 * @throws Exception
	 */
	@Override
	public String getLoginRedirectURL(final String returnTo) throws Exception {
		LOG.info("Determining URL for redirection");
		setProviderState(true);
		LOG.debug("Call to fetch Request Token");
		try {
			requestToken = oauth.getRequestToken(REQUEST_TOKEN_URL, returnTo);
		} catch (SocialAuthException ex) {
			String msg = ex.getMessage()
					+ "OR you have not set any scope while registering your application. You will have to select atlest read public profile scope while registering your application";
			throw new SocialAuthException(msg, ex);
		}
		StringBuilder urlBuffer = oauth.buildAuthUrl(AUTHORIZATION_URL,
				requestToken, returnTo);
		LOG.info("Redirection to following URL should happen : "
				+ urlBuffer.toString());
		return urlBuffer.toString();
	}

	/**
	 * Verifies the user when the external provider redirects back to our
	 * application.
	 * 
	 * @return Profile object containing the profile information
	 * @param request
	 *            Request object the request is received from the provider
	 * @throws Exception
	 */

	@Override
	public Profile verifyResponse(final HttpServletRequest request)
			throws Exception {
		LOG.info("Verifying the authentication response from provider");
		if (!isProviderState()) {
			throw new ProviderStateException();
		}
		if (requestToken == null) {
			throw new SocialAuthException("Request token is null");
		}
		String verifier = request.getParameter(Constants.OAUTH_VERIFIER);
		if (verifier != null) {
			requestToken.setAttribute(Constants.OAUTH_VERIFIER, verifier);
		}

		LOG.debug("Call to fetch Access Token");
		accessToken = oauth.getAccessToken(ACCESS_TOKEN_URL, requestToken);
		isVerify = true;
		userProfile = getProfile();
		return userProfile;
	}

	private Profile getProfile() throws Exception {
		Profile profile = new Profile();
		String url = PROFILE_URL + accessToken.getAttribute("screen_name");
		LOG.debug("Obtaining user profile. Profile URL : " + url);
		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(url, null, accessToken);
		} catch (Exception e) {
			throw new SocialAuthException(
					"Failed to retrieve the user profile from  " + url);
		}
		if (serviceResponse.getStatus() != 200) {
			throw new SocialAuthException(
					"Failed to retrieve the user profile from  " + url
							+ ". Staus :" + serviceResponse.getStatus());
		}
		String result;
		try {
			result = serviceResponse
					.getResponseBodyAsString(Constants.ENCODING);
			LOG.debug("User Profile :" + result);
		} catch (Exception exc) {
			throw new SocialAuthException("Failed to read response from  "
					+ url);
		}
		try {
			JSONObject pObj = new JSONObject(result);
			if (pObj.has("id_str")) {
				profile.setValidatedId(pObj.getString("id_str"));
			}
			if (pObj.has("name")) {
				profile.setFullName(pObj.getString("name"));
			}
			if (pObj.has("location")) {
				profile.setLocation(pObj.getString("location"));
			}
			if (pObj.has("screen_name")) {
				profile.setDisplayName(pObj.getString("screen_name"));
			}
			if (pObj.has("lang")) {
				profile.setLanguage(pObj.getString("lang"));
			}
			if (pObj.has("profile_image_url")) {
				profile.setProfileImageURL(pObj.getString("profile_image_url"));
			}
			return profile;
		} catch (Exception e) {
			throw new ServerDataException(
					"Failed to parse the user profile json : " + result);

		}
	}

	/**
	 * Updates the status on Twitter.
	 * 
	 * @param msg
	 *            Message to be shown as user's status
	 * @throws Exception
	 */
	@Override
	public void updateStatus(final String msg) throws Exception {
		LOG.info("Updatting status " + msg);
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		if (msg == null || msg.trim().length() == 0) {
			throw new ServerDataException("Status cannot be blank");
		}
		String url = UPDATE_STATUS_URL
				+ URLEncoder.encode(msg, Constants.ENCODING);
		Response serviceResponse = null;
		try {
			serviceResponse = oauth
					.httpPost(url, null, null, null, accessToken);
		} catch (Exception e) {
			throw new SocialAuthException("Failed to update status on " + url,
					e);
		}
		System.out.println(serviceResponse.getStatus());
		System.out.println(serviceResponse
				.getResponseBodyAsString(Constants.ENCODING));
		if (serviceResponse.getStatus() != 200) {
			throw new SocialAuthException("Failed to update status on " + url
					+ ". Staus :" + serviceResponse.getStatus());
		}
	}

	/**
	 * Gets the list of followers of the user and their screen name.
	 * 
	 * @return List of contact objects representing Contacts. Only name, screen
	 *         name and profile URL will be available
	 */
	@Override
	public List<Contact> getContactList() throws Exception {
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		String url = String.format(CONTACTS_URL,
				accessToken.getAttribute("screen_name"));
		List<Contact> plist = new ArrayList<Contact>();
		LOG.info("Fetching contacts from " + url);
		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(url, null, accessToken);
		} catch (Exception ie) {
			throw new SocialAuthException(
					"Failed to retrieve the contacts from " + url, ie);
		}
		String result;
		try {
			result = serviceResponse
					.getResponseBodyAsString(Constants.ENCODING);
		} catch (Exception e) {
			throw new ServerDataException("Failed to get response from " + url);
		}
		LOG.debug("User friends ids : " + result);
		try {
			JSONObject jobj = new JSONObject(result);
			if (jobj.has("ids")) {
				JSONArray idList = jobj.getJSONArray("ids");
				int flength = idList.length();
				int ids[] = new int[flength];
				for (int i = 0; i < idList.length(); i++) {
					ids[i] = idList.getInt(i);
				}
				if (flength > 0) {
					if (flength > 100) {
						int i = flength / 100;
						int temparr[];
						for (int j = 1; j <= i; j++) {
							temparr = new int[100];
							for (int k = (j - 1) * 100, c = 0; k < j * 100; k++, c++) {
								temparr[c] = ids[k];
							}
							plist.addAll(lookupUsers(temparr));
						}
						if (flength > i * 100) {
							temparr = new int[flength - i * 100];
							for (int k = i * 100, c = 0; k < flength; k++, c++) {
								temparr[c] = ids[k];
							}
							plist.addAll(lookupUsers(temparr));
						}
					} else {
						plist.addAll(lookupUsers(ids));
					}
				}
			}
		} catch (Exception e) {
			throw new ServerDataException(
					"Failed to parse the user friends json : " + result, e);
		}
		return plist;
	}

	private List<Contact> lookupUsers(final int fids[]) throws Exception {
		StringBuilder strb = new StringBuilder();
		List<Contact> plist = new ArrayList<Contact>();
		for (int value : fids) {
			if (strb.length() != 0) {
				strb.append(",");
			}
			strb.append(value);
		}
		String url = LOOKUP_URL + strb.toString();
		LOG.debug("Fetching info of following users : " + url);
		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(url, null, accessToken);
		} catch (Exception ie) {
			throw new SocialAuthException(
					"Failed to retrieve the contacts from " + url, ie);
		}
		String result;
		try {
			result = serviceResponse
					.getResponseBodyAsString(Constants.ENCODING);
		} catch (Exception e) {
			throw new ServerDataException("Failed to get response from " + url);
		}
		LOG.debug("Users info : " + result);
		try {
			JSONArray jarr = new JSONArray(result);
			for (int i = 0; i < jarr.length(); i++) {
				JSONObject jobj = jarr.getJSONObject(i);
				Contact cont = new Contact();
				if (jobj.has("name")) {
					cont.setFirstName(jobj.getString("name"));
				}
				if (jobj.has("screen_name")) {
					cont.setDisplayName(jobj.getString("screen_name"));
					cont.setProfileUrl("http://" + PROPERTY_DOMAIN + "/"
							+ jobj.getString("screen_name"));
				}
				plist.add(cont);
			}
		} catch (Exception e) {
			throw e;
		}
		return plist;
	}

	/**
	 * Logout
	 */
	@Override
	public void logout() {
		requestToken = null;
		accessToken = null;
	}

	/**
	 * 
	 * @param p
	 *            Permission object which can be Permission.AUHTHENTICATE_ONLY,
	 *            Permission.ALL, Permission.DEFAULT
	 */
	@Override
	public void setPermission(final Permission p) {
		LOG.debug("Permission requested : " + p.toString());
		this.scope = p;
	}

	/**
	 * Makes OAuth signed HTTP request to a given URL. It attaches Authorization
	 * header with HTTP request.
	 * 
	 * @param url
	 *            URL to make HTTP request.
	 * @param methodType
	 *            Method type can be GET, POST or PUT
	 * @param params
	 *            Any additional parameters whose signature need to compute.
	 *            Only used in case of "POST" and "PUT" method type.
	 * @param headerParams
	 *            Any additional parameters need to pass as Header Parameters
	 * @param body
	 *            Request Body
	 * @return Response object
	 * @throws Exception
	 */
	@Override
	public Response api(final String url, final String methodType,
			final Map<String, String> params,
			final Map<String, String> headerParams, final String body)
			throws Exception {
		if (!isProviderState()) {
			throw new ProviderStateException();
		}
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		Response response = null;
		LOG.debug("Calling URL : " + url);
		if (MethodType.GET.toString().equals(methodType)) {
			try {
				response = oauth.httpGet(url, headerParams, accessToken);
			} catch (Exception ie) {
				throw new SocialAuthException(
						"Error while making request to URL : " + url, ie);
			}
		} else if (MethodType.PUT.toString().equals(methodType)) {
			try {
				response = oauth.httpPut(url, params, headerParams, body,
						accessToken);
			} catch (Exception e) {
				throw new SocialAuthException(
						"Error while making request to URL : " + url, e);
			}
		} else if (MethodType.POST.toString().equals(methodType)) {
			try {
				response = oauth.httpPost(url, params, headerParams, body,
						accessToken);
			} catch (Exception e) {
				throw new SocialAuthException(
						"Error while making request to URL : " + url, e);
			}
		}
		return response;
	}

	/**
	 * Retrieves the user profile.
	 * 
	 * @return Profile object containing the profile information.
	 */
	public Profile getUserProfile() {
		return userProfile;
	}

}