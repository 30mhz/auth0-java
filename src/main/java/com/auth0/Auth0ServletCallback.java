package com.auth0;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Arrays.asList;

public class Auth0ServletCallback extends HttpServlet {

    private Properties properties = new Properties();
    private String redirectOnSuccess;
    private String redirectOnFail;

    private Tokens parseTokens(String body) throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        Map<String, String> jsonAsMap = mapper.readValue(body, Map.class);
        String accessToken = jsonAsMap.get("access_token");
        String idToken = jsonAsMap.get("id_token");

        return new Tokens(idToken, accessToken);
    }

    protected void saveTokens(HttpServletRequest req, HttpServletResponse resp, Tokens tokens) throws ServletException, IOException {
        HttpSession session = req.getSession();

        // Save tokens on a persistent session
        session.setAttribute("accessToken", tokens.getAccessToken());
        session.setAttribute("idToken", tokens.getIdToken());
    }

    protected void onSuccess(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Redirect user to home
        resp.sendRedirect(redirectOnSuccess);
    }

    private URI getURI(Properties properties) {
        URI https;
        try {
            https = new URI("https", (String) properties.get("auth0.domain"), "/oauth/token", "");

        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return https;
    }

    private String getAuthorizationCode(HttpServletRequest req) {
        String authorizationCode = null;
        List<NameValuePair> queryString = URLEncodedUtils.parse(req.getQueryString(), Charset.forName("ASCII"));

        for (NameValuePair pair : queryString) {
            if ("code".equals(pair.getName())) {
                authorizationCode = pair.getValue();
            }
        }
        return authorizationCode;
    }

    static String readParameter(String parameter, ServletConfig config) {
        String first = config.getInitParameter(parameter);
        if(hasValue(first)) {
            return first;
        }
        final String second = config.getServletContext().getInitParameter(parameter);
        if(hasValue(second)) {
            return second;
        }
        throw new IllegalArgumentException(parameter + " needs to be defined");
    }

    private static boolean hasValue(String value) {
        return value != null && value.trim().length() > 0;
    }

    /**
     * Fetch properties to be used. User is encourage to override this method.
     *
     * Auth0 uses the ServletContext parameters:
     *
     * <dl>
     *     <dt>auth0.client_id</dd><dd>Application client id</dd>
     *     <dt>auth0.client_secret</dt><dd>Application client secret</dd>
     *     <dt>auth0.domain</dt><dd>Auth0 domain</dd>
     * </dl>
     *
     * Auth0ServletCallback uses these ServletConfig parameters:
     *
     * <dl>
     *     <dt>auth0.redirect_on_success</dt><dd>Where to send the user after successful login.</dd>
     *     <dt>auth0.redirect_on_error</dt><dd>Where to send the user after failed login.</dd>
     * </dl>
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        redirectOnSuccess = readParameter("auth0.redirect_on_success", config);

        redirectOnFail = readParameter("auth0.redirect_on_error", config);

        for(String param : asList("auth0.client_id", "auth0.client_secret", "auth0.domain")) {
            properties.put(param, readParameter(param, config));
        }
    }

    /**
     * Override this method to specify a different NonceStorage:
     *
     * <code>
     *     protected NonceStorage getNonceStorage(HttpServletRequest request) {
     *         return MyNonceStorageImpl(request);
     *     }
     * </code>
     */

    protected NonceStorage getNonceStorage(HttpServletRequest request) {
        return new RequestNonceStorage(request);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if(isValidRequest(req, resp)) {
            Tokens tokens = fetchTokens(req);
            saveTokens(req, resp, tokens);
            onSuccess(req, resp);
        }
    }

    /**
     * Override this method to specify a different HttpClient. For instance, if you
     * want this to run behind a proxy you should override this class and implement:
     *
     * <code>
     * protected CloseableHttpClient createHttpClient() {
     *      CloseableHttpClient httpClient;
     +      if (useSystemDefaultProxy) {
     +          SystemDefaultRoutePlanner routePlanner = new SystemDefaultRoutePlanner(
     +                    ProxySelector.getDefault());
     +          httpClient = HttpClients.custom()
     +                    .setRoutePlanner(routePlanner)
     +                    .build();
     +      } else {
     +          httpClient = HttpClients.createDefault();
     +      }
     +      return httpClient;
     * }
     * </code>
     *
     *
     * @return CloseableHttpClient that will be used to perform http requests to Auth0.
     */
    protected CloseableHttpClient createHttpClient()
    {
        return HttpClients.createDefault();
    }

    private Tokens fetchTokens(HttpServletRequest req) throws IOException {
        // Parse request to fetch authorization code
        String authorizationCode = getAuthorizationCode(req);

        URI accessTokenURI = getURI(properties);

        CloseableHttpClient httpClient = createHttpClient();

        HttpPost httpPost = new HttpPost(accessTokenURI);
        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
        nameValuePairs.add(new BasicNameValuePair("client_id",      (String) properties.get("auth0.client_id")));
        nameValuePairs.add(new BasicNameValuePair("client_secret",  (String) properties.get("auth0.client_secret")));

        nameValuePairs.add(new BasicNameValuePair("redirect_uri",   req.getRequestURL().toString()));
        nameValuePairs.add(new BasicNameValuePair("code",           authorizationCode));

        nameValuePairs.add(new BasicNameValuePair("grant_type",     "authorization_code"));

        httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
        CloseableHttpResponse response = httpClient.execute(httpPost);

        String tokensToParse;
        try {
            HttpEntity entity = response.getEntity();
            tokensToParse = EntityUtils.toString(entity);

            EntityUtils.consume(entity);
        } finally {
            response.close();
        }

        // Parse and obtain both access token and id token
        return parseTokens(tokensToParse);
    }

    private boolean isValidRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (hasError(req) || !isValidState(req)) {
            resp.sendRedirect(req.getContextPath() + redirectOnFail + "?" + req.getQueryString());
            return false;
        }
        return true;
    }

    private boolean isValidState(HttpServletRequest req) {
        return req.getParameter("state").equals(getNonceStorage(req).getState());
    }

    private static boolean hasError(HttpServletRequest req) {
        return req.getParameter("error") != null;
    }

}
