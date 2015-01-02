/*
  Copyright 2012 - 2014 Jerome Leleu

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
package org.pac4j.play.java;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.RedirectAction;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.RequiresHttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.play.CallbackController;
import org.pac4j.play.Config;
import org.pac4j.play.Constants;
import org.pac4j.play.StorageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.libs.F.Function;
import play.libs.F.Function0;
import play.libs.F.Promise;
import play.mvc.Action;
import play.mvc.Http.Context;
import play.mvc.Http.Request;
import play.mvc.Result;

/**
 * <p>This action checks if the user is not authenticated and starts the authentication process if necessary.</p>
 * <p>It handles both statefull (default) or stateless resources by delegating to a pac4j client.
 *  - If statefull, it relies on the session and on the callback filter to terminate the authentication process.
 *  - If stateless it validates the provided credentials and forward the request to
 * the underlying resource if the authentication succeeds.</p>
 * <p>The filter also handles basic authorization based on two parameters: requireAnyRole and requireAllRoles.</p>
 * 
 * @author Jerome Leleu
 * @since 1.0.0
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class RequiresAuthenticationAction extends Action<Result> {

    private static final Logger logger = LoggerFactory.getLogger(RequiresAuthenticationAction.class);

    private static final Method clientNameMethod;

    private static final Method targetUrlMethod;

    private static final Method statelessMethod;

    private static final Method isAjaxMethod;

    private static final Method requireAnyRoleMethod;

    private static final Method requireAllRolesMethod;

    static {
        try {
            clientNameMethod = RequiresAuthentication.class.getDeclaredMethod(Constants.CLIENT_NAME);
            targetUrlMethod = RequiresAuthentication.class.getDeclaredMethod(Constants.TARGET_URL);
            statelessMethod = RequiresAuthentication.class.getDeclaredMethod(Constants.STATELESS);
            isAjaxMethod = RequiresAuthentication.class.getDeclaredMethod(Constants.IS_AJAX);
            requireAnyRoleMethod = RequiresAuthentication.class.getDeclaredMethod(Constants.REQUIRE_ANY_ROLE);
            requireAllRolesMethod = RequiresAuthentication.class.getDeclaredMethod(Constants.REQUIRE_ALL_ROLES);
        } catch (final SecurityException e) {
            throw new RuntimeException(e);
        } catch (final NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Authentication algorithm.
     * 
     */
    @Override
    public Promise<Result> call(final Context ctx) throws Throwable {

        final ActionContext actionContext = buildActionContext(ctx);

        Promise<CommonProfile> profile = retrieveUserProfile(actionContext);
        return profile.flatMap(new Function<CommonProfile, Promise<Result>>() {

            @Override
            public Promise<Result> apply(CommonProfile profile) throws Throwable {
                // authentication success or failure strategy
                if (profile == null) {
                    return authenticationFailure(actionContext);
                } else {
                    saveUserProfile(profile, actionContext);
                    return authenticationSuccess(profile, actionContext);
                }
            }

        }).recover(new Function<Throwable, Result>() {

            @Override
            public Result apply(Throwable a) throws Throwable {
                if (a instanceof RequiresHttpAction) {
                    RequiresHttpAction e = (RequiresHttpAction) a;
                    return requireActionToResult(e.getCode(), actionContext);
                } else {
                    return internalServerError();
                }
            }

        });

    }

    /**
     * Retrieve user profile either by looking in the session or trying to authenticate directly
     * if stateless web service.
     * 
     */
    protected Promise<CommonProfile> retrieveUserProfile(final ActionContext actionContext) {
        if (isStateless(actionContext)) {
            return authenticate(actionContext);
        } else {
            return Promise.promise(new Function0<CommonProfile>() {

                @Override
                public CommonProfile apply() {
                    final CommonProfile profile = StorageHelper.getProfile(actionContext.sessionId);
                    logger.debug("profile : {}", profile);
                    return profile;
                }
            });
        }

    }

    /**
     * Default authentication failure strategy which generates an unauthorized page if stateless web service
     * or redirect to the authentication provider after saving the original url.
     * 
     */
    protected Promise<Result> authenticationFailure(final ActionContext actionContext) {

        return Promise.promise(new Function0<Result>() {
            @Override
            public Result apply() throws RequiresHttpAction {
                if (isStateless(actionContext)) {
                    return unauthorized(Config.getErrorPage401()).as(HttpConstants.HTML_CONTENT_TYPE);
                } else {
                    // no authentication tried -> redirect to provider
                    // keep the current url
                    saveOriginalUrl(actionContext);
                    // compute and perform the redirection
                    return redirectToIdentityProvider(actionContext);
                }
            }
        });
    }

    /**
     * Save the user profile in session or attach it to the request if stateless web service.
     * 
     */
    protected void saveUserProfile(CommonProfile profile, final ActionContext actionContext) {
        if (isStateless(actionContext)) {
            actionContext.ctx.args.put(HttpConstants.USER_PROFILE, profile);
        } else {
            StorageHelper.saveProfile(actionContext.sessionId, profile);
        }
    }

    /**
     * Default authentication success strategy which forward to the next filter if the user
     * has access or returns an access denied error otherwise.
     * @return 
     * @throws Throwable 
     * 
     */
    protected Promise<Result> authenticationSuccess(CommonProfile profile, final ActionContext actionContext)
            throws Throwable {

        if (hasAccess(profile, actionContext)) {
            return delegate.call(actionContext.ctx);
        } else {
            return Promise.promise(new Function0<Result>() {
                @Override
                public Result apply() {
                    return forbidden(Config.getErrorPage403()).as(HttpConstants.HTML_CONTENT_TYPE);
                }
            });
        }
    }

    /**
     * Authenticates the current request by getting the credentials and the corresponding user profile.
     * @param ctx 
     * 
     */
    protected Promise<CommonProfile> authenticate(final ActionContext actionContext) {

        return Promise.promise(new Function0<CommonProfile>() {

            @Override
            public CommonProfile apply() throws RequiresHttpAction {
                final Client client = Config.getClients().findClient(actionContext.clientName);
                logger.debug("client : {}", client);

                final Credentials credentials;
                credentials = client.getCredentials(actionContext.webContext);
                logger.debug("credentials : {}", credentials);

                // get user profile
                CommonProfile profile = (CommonProfile) client.getUserProfile(credentials, actionContext.webContext);
                logger.debug("profile : {}", profile);

                return profile;
            }

        });
    }

    /**
     * Returns true if the user defined by the profile has access to the underlying resource
     * depending on the requireAnyRole and requireAllRoles fields.
     * 
     * @param profile
     * @param request 
     * @return
     */
    protected boolean hasAccess(CommonProfile profile, final ActionContext actionContext) {

        return profile.hasAccess(actionContext.requireAnyRole, actionContext.requireAllRoles);
    }

    protected void saveOriginalUrl(final ActionContext actionContext) {
        if (!isAjaxRequest(actionContext)) {
            // requested url to save
            final String requestedUrlToSave = CallbackController.defaultUrl(actionContext.targetUrl,
                    actionContext.request.uri());
            logger.debug("requestedUrlToSave : {}", requestedUrlToSave);
            StorageHelper.saveRequestedUrl(actionContext.sessionId, actionContext.clientName, requestedUrlToSave);
        }
    }

    protected String retrieveOriginalUrl(final ActionContext actionContext) {
        return StorageHelper.getRequestedUrl(actionContext.sessionId, actionContext.clientName);
    }

    protected boolean isAjaxRequest(ActionContext actionContext) {
        return actionContext.isAjax;
    }

    protected boolean isStateless(final ActionContext actionContext) {
        return actionContext.stateless;
    }

    private Result redirectToIdentityProvider(final ActionContext actionContext) throws RequiresHttpAction {
        Client<Credentials, CommonProfile> client = Config.getClients().findClient(actionContext.clientName);
        RedirectAction action = ((BaseClient) client).getRedirectAction(actionContext.webContext, true,
                isAjaxRequest(actionContext));
        logger.debug("redirectAction : {}", action);
        return toResult(action);
    }

    private Result toResult(RedirectAction action) {
        switch (action.getType()) {
        case REDIRECT:
            return redirect(action.getLocation());
        case SUCCESS:
            return ok(action.getContent()).as(HttpConstants.HTML_CONTENT_TYPE);
        default:
            throw new TechnicalException("Unsupported RedirectAction type " + action.getType());
        }
    }

    /**
     * Build the action context from Play context and {@link RequiresAuthentication} annotation.
     * 
     * @param ctx
     * @return
     * @throws Throwable
     */
    private ActionContext buildActionContext(Context ctx) throws Throwable {
        JavaWebContext context = new JavaWebContext(ctx.request(), ctx.response(), ctx.session());
        String clientName = null;
        String targetUrl = "";
        Boolean isAjax = false;
        Boolean stateless = false;
        String requireAnyRole = "";
        String requireAllRoles = "";

        if (configuration != null) {
            final InvocationHandler invocationHandler = Proxy.getInvocationHandler(configuration);
            clientName = (String) invocationHandler.invoke(configuration, clientNameMethod, null);
            targetUrl = (String) invocationHandler.invoke(configuration, targetUrlMethod, null);
            logger.debug("targetUrl : {}", targetUrl);
            isAjax = (Boolean) invocationHandler.invoke(configuration, isAjaxMethod, null);
            logger.debug("isAjax : {}", isAjax);
            stateless = (Boolean) invocationHandler.invoke(configuration, statelessMethod, null);
            logger.debug("stateless : {}", stateless);
            requireAnyRole = (String) invocationHandler.invoke(configuration, requireAnyRoleMethod, null);
            logger.debug("requireAnyRole : {}", requireAnyRole);
            requireAllRoles = (String) invocationHandler.invoke(configuration, requireAllRolesMethod, null);
            logger.debug("requireAllRoles : {}", requireAllRoles);
        }
        clientName = (clientName != null) ? clientName : context.getRequestParameter(Config.getClients()
                .getClientNameParameter());
        logger.debug("clientName : {}", clientName);

        String sessionId = (stateless) ? null : StorageHelper.getOrCreationSessionId(ctx.session());
        return new ActionContext(ctx, ctx.request(), sessionId, context, clientName, targetUrl, isAjax, stateless,
                requireAnyRole, requireAllRoles);
    }

    private Result requireActionToResult(int code, ActionContext actionContext) {
        // requires some specific HTTP action
        logger.debug("requires HTTP action : {}", code);
        if (code == HttpConstants.UNAUTHORIZED) {
            return unauthorized(Config.getErrorPage401()).as(HttpConstants.HTML_CONTENT_TYPE);
        } else if (code == HttpConstants.FORBIDDEN) {
            return forbidden(Config.getErrorPage403()).as(HttpConstants.HTML_CONTENT_TYPE);
        } else if (code == HttpConstants.TEMP_REDIRECT) {
            return redirect(actionContext.webContext.getResponseLocation());
        } else if (code == HttpConstants.OK) {
            final String content = actionContext.webContext.getResponseContent();
            logger.debug("render : {}", content);
            return ok(content).as(HttpConstants.HTML_CONTENT_TYPE);
        }
        final String message = "Unsupported HTTP action : " + code;
        logger.error(message);
        throw new TechnicalException(message);
    }

    /**
     * Context for the action.
     * 
     */
    protected static class ActionContext {

        Context ctx;
        Request request;
        String sessionId;
        JavaWebContext webContext;
        String clientName;
        String targetUrl;
        boolean isAjax;
        boolean stateless;
        String requireAnyRole;
        String requireAllRoles;

        private ActionContext(Context ctx, Request request, String sessionId, JavaWebContext webContext,
                String clientName, String targetUrl, boolean isAjax, Boolean stateless, String requireAnyRole,
                String requireAllRoles) {
            this.ctx = ctx;
            this.request = request;
            this.sessionId = sessionId;
            this.webContext = webContext;
            this.clientName = clientName;
            this.targetUrl = targetUrl;
            this.isAjax = isAjax;
            this.stateless = stateless;
            this.requireAnyRole = requireAnyRole;
            this.requireAllRoles = requireAllRoles;
        }

    }
}
