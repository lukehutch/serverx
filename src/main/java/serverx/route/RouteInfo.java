/*
 * 
 */
package serverx.route;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import io.github.classgraph.AnnotationEnumValue;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationParameterValue;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ClassTypeSignature;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeArgument.Wildcard;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.oauth2.AccessToken;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.UpdateOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.OAuth2AuthHandler;
import io.vertx.ext.web.handler.ResponseContentTypeHandler;
import io.vertx.ext.web.handler.impl.CSRFHandlerImpl;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import serverx.exception.ResponseException;
import serverx.model.HTMLPageModel;
import serverx.route.Route.ResponseType;
import serverx.server.ServerxVerticle;
import serverx.template.HTMLTemplate;
import serverx.template.TemplateModel;
import serverx.utils.WebUtils;

/**
 * RouteInfo.
 */
public class RouteInfo implements Comparable<RouteInfo> {
    /** The route annotation. */
    private final Route routeAnnotation;

    /** The route annotation info. */
    private final AnnotationInfo routeAnnotationInfo;

    /** The handler class name. */
    private final String handlerClassName;

    /** The routing context handler instance. */
    private final Handler<RoutingContext> routingContextHandlerInstance;

    /** The is socket handler. */
    private final boolean isSocketHandler;

    // -------------------------------------------------------------------------------------------------------------

    /** The name of the user info collection in the database. */
    public static final String USER_INFO_COLLECTION_NAME = "user_info";

    /** The name of the permissions collection in the database. */
    public static final String PERMISSIONS_COLLECTION_NAME = "permissions";

    /** The name of the user info property in the session. */
    public static final String USER_INFO_SESSION_PROPERTY_KEY = "user_info";

    /** The name of the email property in the session. */
    public static final String EMAIL_SESSION_PROPERTY_KEY = "email";

    /** The name of the permissions property in the session. */
    public static final String PERMISSIONS_SESSION_PROPERTY_KEY = "permissions";

    /**
     * The name of the CSRF token property in the session (should match
     * {@code io.vertx.ext.web.handler.DEFAULT_HEADER_NAME}, since {@link CSRFHandlerImpl#handle(RoutingContext)}
     * stores this value using {@code ctx.put(headerName, token)}).
     */
    public static final String CSRF_SESSION_PROPERTY_KEY = "X-XSRF-TOKEN";

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor for {@code Handler<RoutingContext>} or {@code Handler<SockJSSocket>}.
     *
     * @param vertx
     *            the vertx
     * @param handlerClassName
     *            the handler class name
     * @param routeAnnotation
     *            the route annotation
     * @param routeAnnotationInfo
     *            the route annotation info
     * @param ifaceSig
     *            the iface sig
     * @param handlerInstanceUntyped
     *            the handler instance untyped
     */
    public RouteInfo(final Vertx vertx, final String handlerClassName, final Route routeAnnotation,
            final AnnotationInfo routeAnnotationInfo, final ClassRefTypeSignature ifaceSig,
            final Handler<?> handlerInstanceUntyped) {
        this.handlerClassName = handlerClassName;
        this.routeAnnotation = routeAnnotation;
        this.routeAnnotationInfo = routeAnnotationInfo;

        final var typeArg = getTypeArg(ifaceSig, 0).getFullyQualifiedClassName();
        Handler<RoutingContext> handlerInstance;
        if (typeArg.equals(SockJSSocket.class.getName())) {
            @SuppressWarnings("unchecked")
            final var handlerInstanceConcrete = (Handler<SockJSSocket>) handlerInstanceUntyped;
            handlerInstance = wrapSocketHandler(vertx, handlerInstanceConcrete);
            this.isSocketHandler = true;
        } else if (typeArg.equals(RoutingContext.class.getName())) {
            @SuppressWarnings("unchecked")
            final var handlerInstanceConcrete = (Handler<RoutingContext>) handlerInstanceUntyped;
            handlerInstance = handlerInstanceConcrete;
            this.isSocketHandler = false;
        } else {
            throw new IllegalArgumentException("Illegal Handler parameter type: " + typeArg);
        }

        // Wrap handler method in try-catch so that a failure handler can be used for all failures,
        // rather than having to rely on a separate error handler for uncaught exceptions.
        this.routingContextHandlerInstance = new Handler<RoutingContext>() {
            @Override
            public void handle(final RoutingContext ctx) {
                try {
                    handlerInstance.handle(ctx);
                } catch (final Throwable t) {
                    final var statusCode = t instanceof ResponseException ? ((ResponseException) t).statusCode()
                            : 500;
                    ctx.fail(statusCode, t);
                }
            }
        };
    }

    /**
     * Constructor for {@link RouteHandler}.
     *
     * @param vertx
     *            the vertx
     * @param handlerClassName
     *            the handler class name
     * @param routeAnnotation
     *            the route annotation
     * @param routeAnnotationInfo
     *            the route annotation info
     * @param ifaceSig
     *            the iface sig
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     * @param defaultPageHTMLTemplate
     *            the default page HTML template
     * @param serverUri
     *            the server URI
     * @param scanResult
     *            the scan result
     * @param routeHandlerInstance
     *            the route handler instance
     * @throws ReflectiveOperationException
     *             the reflective operation exception
     * @throws SecurityException
     *             the security exception
     */
    public RouteInfo(final Vertx vertx, final String handlerClassName, final Route routeAnnotation,
            final AnnotationInfo routeAnnotationInfo, final ClassRefTypeSignature ifaceSig,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate,
            final HTMLTemplate defaultPageHTMLTemplate, final URI serverUri, final ScanResult scanResult,
            final RouteHandler<Object> routeHandlerInstance)
            throws ReflectiveOperationException, SecurityException {
        this.handlerClassName = handlerClassName;
        this.routeAnnotation = routeAnnotation;
        this.routeAnnotationInfo = routeAnnotationInfo;
        this.isSocketHandler = false;

        // Pre-lookup the method handles for template class, so that it doesn't have to be done for each render
        final var htmlTemplate = getHTMLTemplate(routeHandlerInstance.getClass(), routeAnnotation,
                getTypeArg(ifaceSig, 0), templateModelClassToHTMLTemplate, serverUri, scanResult);

        this.routingContextHandlerInstance = ctx -> {
            // Create a new Future<TemplateModel>, and pass it to the handler
            final var templateModelFuture = Future.future();
            templateModelFuture.setHandler(asyncResult -> {
                if (asyncResult.succeeded()) {
                    final Object result = asyncResult.result();
                    switch (routeAnnotation.responseType()) {
                    case HTML:
                        // Render the result TemplateModel into HTML, then send as response
                        ctx.response().putHeader("content-type", "text/html; charset=utf-8")
                                .end(htmlTemplate.renderPageOrFragment((TemplateModel) result,
                                        routeAnnotation.htmlTemplatePath(), defaultPageHTMLTemplate,
                                        routeAnnotation.htmlPageTemplatePath(),
                                        ctx.get(CSRF_SESSION_PROPERTY_KEY)));
                        break;
                    case JSON:
                        // Encode the result object as JSON, then send as response
                        ctx.response().putHeader("content-type", "application/json; charset=utf-8")
                                .end(ServerxVerticle.serverProperties.indentJSON ? Json.encodePrettily(result)
                                        : Json.encode(result));
                        break;
                    case STRING:
                        // Call toString() on the result object, then send as plaintext
                        ctx.response().putHeader("content-type", "text/plain; charset=utf-8")
                                .end(result == null ? "" : result.toString());
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown response type " + routeAnnotation.responseType());
                    }
                } else {
                    final var cause = asyncResult.cause();
                    final var statusCode = cause instanceof ResponseException
                            ? ((ResponseException) cause).statusCode()
                            : 500;
                    ctx.fail(statusCode, cause);
                }
            });
            try {
                routeHandlerInstance.handle(ctx, templateModelFuture);
            } catch (final Throwable e) {
                // Uncaught exception in handler
                if (routeAnnotation.isFailureHandler()) {
                    // Exception in failure handler -- log and try next handler
                    if (ctx.failure() != null) {
                        // Add the suppressed failure that triggered the failure handler
                        e.addSuppressed(ctx.failure());
                    }
                    ServerxVerticle.logger.log(Level.SEVERE, "Uncaught exception in failure handler", e);
                    ctx.next();
                } else {
                    // Exception in a non-failure handler -- fail the future, which will fail the above lambda
                    templateModelFuture.fail(e);
                }
            }
        };
    }

    /**
     * Constructor for {@link SocketHandler}.
     *
     * @param vertx
     *            the vertx
     * @param handlerClassName
     *            the handler class name
     * @param routeAnnotation
     *            the route annotation
     * @param routeAnnotationInfo
     *            the route annotation info
     * @param ifaceSig
     *            the iface sig
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     * @param defaultPageHTMLTemplate
     *            the default page HTML template
     * @param serverUri
     *            the server URI
     * @param scanResult
     *            the scan result
     * @param socketHandlerInstance
     *            the socket handler instance
     * @throws ReflectiveOperationException
     *             the reflective operation exception
     * @throws SecurityException
     *             the security exception
     */
    public RouteInfo(final Vertx vertx, final String handlerClassName, final Route routeAnnotation,
            final AnnotationInfo routeAnnotationInfo, final ClassRefTypeSignature ifaceSig,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate,
            final HTMLTemplate defaultPageHTMLTemplate, final URI serverUri, final ScanResult scanResult,
            final SocketHandler<Object> socketHandlerInstance)
            throws ReflectiveOperationException, SecurityException {
        this.handlerClassName = handlerClassName;
        this.routeAnnotation = routeAnnotation;
        this.routeAnnotationInfo = routeAnnotationInfo;
        this.isSocketHandler = true;

        // Pre-lookup the method handles for template class, so that it doesn't have to be done for each render
        final var htmlTemplate = getHTMLTemplate(socketHandlerInstance.getClass(), routeAnnotation,
                getTypeArg(ifaceSig, 0), templateModelClassToHTMLTemplate, serverUri, scanResult);

        // Wrap the Handler<SockJSSocket> with a Handler<RoutingContext>
        this.routingContextHandlerInstance = wrapSocketHandler(vertx, socket -> {
            // Create a new Future<TemplateModel>, and pass it to the handler
            final var templateModelFuture = Future.future();
            templateModelFuture.setHandler(asyncResult -> {
                if (asyncResult.succeeded()) {
                    switch (routeAnnotation.responseType()) {
                    case HTML:
                        // Render the result TemplateModel into HTML, then send as response
                        final var webSession = socket.webSession();
                        socket.write(htmlTemplate.renderPageOrFragment((TemplateModel) asyncResult.result(),
                                routeAnnotation.htmlTemplatePath(), defaultPageHTMLTemplate,
                                routeAnnotation.htmlPageTemplatePath(), webSession.get(CSRF_SESSION_PROPERTY_KEY)));
                        break;
                    case JSON:
                        // Encode the result object as JSON, then send as response
                        socket.write(ServerxVerticle.serverProperties.indentJSON
                                ? Json.encodePrettily(asyncResult.result())
                                : Json.encode(asyncResult.result()));
                        break;
                    case STRING:
                        // Call toString() on the result object, then send as plaintext
                        socket.write(asyncResult.result().toString());
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown response type " + routeAnnotation.responseType());
                    }
                } else {
                    ServerxVerticle.logger.log(Level.SEVERE, "Uncaught exception in socket handler",
                            asyncResult.cause());
                    socket.end();
                }
            });
            try {
                socketHandlerInstance.handle(socket, templateModelFuture);
            } catch (final Throwable e) {
                // Uncaught exception in socket handler -- fail the future, which will fail the above lambda
                templateModelFuture.fail(e);
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Wrap {@code Handler<SockJSSocket>} in {@code Handler<RoutingContext>} for socket upgrade.
     *
     * @param vertx
     *            the vertx
     * @param socketHandler
     *            the socket handler
     * @return the handler
     */
    private static Handler<RoutingContext> wrapSocketHandler(final Vertx vertx,
            final Handler<SockJSSocket> socketHandler) {

        //                // TODO: add annotation option for enabling SockJS event bus bridge -- see the docs for necessary client-side bits -- https://vertx.io/docs/vertx-web/java/
        //                BridgeOptions options = new BridgeOptions();
        //                // TODO: use new BridgeOptions().addInboundPermitted() and setRequiredAuthority() along with roles and permissions in Route annotation to add authorization to SockJS API
        //                sockJSHandler.bridge(options);

        final var options = new SockJSHandlerOptions();
        final var sockJSHandler = SockJSHandler.create(vertx, options);
        sockJSHandler.socketHandler(socketHandler);

        // Socket handlers do not have access to the RoutingContext that was used to mount the handler, but
        // they do have access to the session. Copy the CSRF token from the RoutingContext into the session,
        // then chain the handle() call to the socket handler.
        return ctx -> {
            if (ctx.session() != null) {
                final var csrfToken = (String) ctx.get(CSRF_SESSION_PROPERTY_KEY);
                if (csrfToken != null) {
                    ctx.session().put(CSRF_SESSION_PROPERTY_KEY, csrfToken);
                }
            }
            sockJSHandler.handle(ctx);
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Load the HTML template for a given template model.
     *
     * @param templateModelClass
     *            the {@link TemplateModel} class. If the class has not been seen before, the default template for
     *            this class will be loaded, by first looking for a static final field named {@code _template}, and
     *            if that doesn't exist, by looking for a file with the extension ".html" in the same package as the
     *            {@link TemplateModel} class.
     * @param htmlTemplatePath
     *            the resource path for to use for an override HTML template, or "" to use the default template. If
     *            a non-empty path is given, then if a template has not previously been loaded from this path, the
     *            template be loaded and added to the set of available templates for this {@link TemplateModel}.
     * @param templateModelClassToHTMLTemplate
     *            the map from {@link TemplateModel} class to {@link HTMLTemplate}
     * @param serverUri
     *            the server URI
     * @param scanResult
     *            the {@link ScanResult}
     * @return the {@link HTMLTemplate}
     */
    private static HTMLTemplate getHTMLTemplate(final Class<? extends TemplateModel> templateModelClass,
            final String htmlTemplatePath,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate,
            final URI serverUri, final ScanResult scanResult) {
        var htmlTemplate = templateModelClassToHTMLTemplate.get(templateModelClass);
        if (htmlTemplate == null) {
            // Template has not been seen before, create a new HTMLTemplate object and store it in the map
            templateModelClassToHTMLTemplate.put(templateModelClass,
                    htmlTemplate = new HTMLTemplate(templateModelClass));
        }
        if (!htmlTemplate.hasTemplateForPath(htmlTemplatePath)) {
            if (htmlTemplatePath.isEmpty()) {
                throw new IllegalArgumentException("Response type " + templateModelClass.getName()
                        + " has no default template, and no template was specified in "
                        + Route.class.getSimpleName() + " annotation");
            }
            // Came across a Route annotation with a template path that has not been seen before --
            // try loading template
            final var templateStr = HTMLTemplate.loadTemplateResourceFromPath(htmlTemplatePath, scanResult);
            if (templateStr != null && !templateStr.isEmpty()) {
                htmlTemplate.addTemplateForPath(htmlTemplatePath, templateStr, templateModelClassToHTMLTemplate,
                        serverUri);
            } else {
                throw new IllegalArgumentException("Could not find template path " + htmlTemplatePath
                        + " specified in " + Route.class.getSimpleName() + " annotation");
            }
        }
        return htmlTemplate;
    }

    /**
     * If the response type is HTML, and the handler's type argument is a {@link TemplateModel}, then return the
     * corresponding {@link HTMLTemplate}, otherwise return null.
     *
     * @param handlerClass
     *            the handler class
     * @param routeAnnotation
     *            the route annotation
     * @param typeArg
     *            the type arg
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     * @param serverUri
     *            the server URI
     * @param scanResult
     *            the scan result
     * @return the HTML template
     */
    private static HTMLTemplate getHTMLTemplate(final Class<?> handlerClass, final Route routeAnnotation,
            final ClassRefTypeSignature typeArg,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate,
            final URI serverUri, final ScanResult scanResult) {
        // Check responseType is HTML
        if (routeAnnotation.responseType() == ResponseType.HTML) {
            // Check that typeArg implements TemplateModel (required for HTML response types)
            final var templateModelClass = typeArg.loadClass();
            if (TemplateModel.class.isAssignableFrom(templateModelClass)) {
                @SuppressWarnings("unchecked")
                final var templateModelClassCasted = (Class<? extends TemplateModel>) templateModelClass;
                // Load the template specified in the Route annotation
                final var htmlTemplate = getHTMLTemplate(templateModelClassCasted,
                        routeAnnotation.htmlTemplatePath(), templateModelClassToHTMLTemplate, serverUri,
                        scanResult);
                // Preload the page template specified in the Route annotation
                getHTMLTemplate(HTMLPageModel.class, routeAnnotation.htmlPageTemplatePath(),
                        templateModelClassToHTMLTemplate, serverUri, scanResult);
                return htmlTemplate;
            } else {
                throw new IllegalArgumentException("If responseType == " + ResponseType.class.getSimpleName()
                        + ".HTML, then the type argument " + "of the handler needs to implement "
                        + TemplateModel.class.getSimpleName());
            }
        } else {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Gets the type arg.
     *
     * @param typeSig
     *            the type sig
     * @param typeArgIdx
     *            the type arg idx
     * @return the type arg
     */
    private static ClassRefTypeSignature getTypeArg(final ClassRefTypeSignature typeSig, final int typeArgIdx) {
        final var typeArgs = typeSig.getTypeArguments();
        if (typeArgIdx < 0 || typeArgIdx >= typeArgs.size()) {
            throw new IllegalArgumentException("Invalid type argument index: " + typeArgIdx);
        }
        final var typeArg = typeArgs.get(typeArgIdx);
        if (typeArg.getWildcard() == Wildcard.ANY) {
            throw new IllegalArgumentException("Invalid type argument: " + typeArg);
        }
        final var typeArgSig = typeArg.getTypeSignature();
        if (!(typeArgSig instanceof ClassRefTypeSignature)) {
            throw new IllegalArgumentException("Invalid type argument: " + typeArg);
        }
        return (ClassRefTypeSignature) typeArgSig;
    }

    /**
     * Find interface sig.
     *
     * @param classSig
     *            the class sig
     * @param ifaceName
     *            the iface name
     * @return the class ref type signature
     */
    private static ClassRefTypeSignature findInterfaceSig(final ClassTypeSignature classSig,
            final String ifaceName) {
        if (classSig == null) {
            throw new IllegalArgumentException("Class is not generic");
        }
        for (final var ifaceSig : classSig.getSuperinterfaceSignatures()) {
            final var baseIfaceName = ifaceSig.getFullyQualifiedClassName();
            if (baseIfaceName.equals(ifaceName)) {
                return ifaceSig;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan the classpath and module path for template models and route handlers.
     *
     * @param vertx
     *            the vertx
     * @param serverUri
     *            the server URI
     * @return the route info
     */
    private static List<RouteInfo> getRouteInfo(final Vertx vertx, final URI serverUri) {
        // Find all TemplateModel implementations
        ServerxVerticle.logger.log(Level.INFO,
                "Scanning package " + ServerxVerticle.serverProperties.templateModelPackage + " for "
                        + TemplateModel.class.getSimpleName() + " implementations");
        final var templateModelClassToHTMLTemplate = new HashMap<Class<? extends TemplateModel>, HTMLTemplate>();
        HTMLTemplate htmlPageModeTemplate = null;
        try (var scanResult = new ClassGraph().enableAllInfo()
                .whitelistPackages(ServerxVerticle.serverProperties.templateModelPackage)
                // Also load the template for the default HTML page model
                .whitelistClasses(HTMLPageModel.class.getName()).scan()) {
            for (final ClassInfo templateModelClassInfo : scanResult
                    .getClassesImplementing(TemplateModel.class.getName())) {
                @SuppressWarnings({ "unchecked" })
                final var templateModelClass = (Class<TemplateModel>) templateModelClassInfo.loadClass();
                try {
                    final var defaultTemplateHTMLStr = HTMLTemplate
                            .findOrLoadDefaultTemplateHTMLStr(templateModelClassInfo, scanResult);
                    if (defaultTemplateHTMLStr != null) {
                        final var htmlTemplate = new HTMLTemplate(templateModelClass);
                        // The default value of Route#htmlTemplatePath() is "", so associate the default 
                        // template with ""
                        htmlTemplate.addTemplateForPath(/* templatePath = */ "", defaultTemplateHTMLStr,
                                templateModelClassToHTMLTemplate, serverUri);
                        templateModelClassToHTMLTemplate.put(templateModelClass, htmlTemplate);
                        // Remember default page template, once reached
                        if (templateModelClassInfo.getName().equals(HTMLPageModel.class.getName())) {
                            htmlPageModeTemplate = htmlTemplate;
                        }
                    }
                } catch (final IllegalArgumentException e) {
                    ServerxVerticle.logger.log(Level.SEVERE, "Exception while reading template for class "
                            + templateModelClass.getName() + ": " + e);
                }
            }
            if (htmlPageModeTemplate == null) {
                throw new RuntimeException("Could not find default template for " + HTMLPageModel.class.getName());
            }
            if (!ServerxVerticle.serverProperties.defaultPageHTMLTemplate.isEmpty()) {
                try {
                    final var overrideDefaultPageTemplateStr = HTMLTemplate.loadTemplateResourceFromPath(
                            ServerxVerticle.serverProperties.defaultPageHTMLTemplate, scanResult);
                    if (overrideDefaultPageTemplateStr != null) {
                        // Override the default template (with path "") with the user-specified template override
                        htmlPageModeTemplate.addTemplateForPath("", overrideDefaultPageTemplateStr,
                                templateModelClassToHTMLTemplate, serverUri);
                        ServerxVerticle.logger.log(Level.INFO, "Overriding default HTML page template with "
                                + ServerxVerticle.serverProperties.defaultPageHTMLTemplate);
                    } else {
                        ServerxVerticle.logger.log(Level.INFO, "Could not find override HTML page template "
                                + ServerxVerticle.serverProperties.defaultPageHTMLTemplate);
                    }
                } catch (final IllegalArgumentException e) {
                    ServerxVerticle.logger.log(Level.INFO, "Could not find override HTML page template "
                            + ServerxVerticle.serverProperties.defaultPageHTMLTemplate + " : " + e.getMessage());
                }
            }
        }

        // Find all classes that are annotated with @Route and that implement Handler<RoutingContext>
        // or HTTPHandlerWithHTMLResponse
        ServerxVerticle.logger.log(Level.INFO,
                "Scanning package " + ServerxVerticle.serverProperties.handlerPackage + " for handlers");
        final var routeHandlerOrder = new ArrayList<RouteInfo>();
        try (var scanResult = new ClassGraph().enableAllInfo()
                .whitelistPackages(ServerxVerticle.serverProperties.handlerPackage)
                .whitelistClasses(Route.class.getName()).scan()) {
            for (final ClassInfo classWithRouteAnnotation : scanResult
                    .getClassesWithAnnotation(Route.class.getName())) {
                final var routeAnnotationInfo = classWithRouteAnnotation.getAnnotationInfo(Route.class.getName());
                final var routeAnnotation = (Route) routeAnnotationInfo.loadClassAndInstantiate();
                try {
                    // Get the generic type signature of the class
                    final var typeSig = classWithRouteAnnotation.getTypeSignature();

                    // Look for one of the required interface types (only first matching interface is used)
                    ClassRefTypeSignature ifaceSig = null;
                    if ((ifaceSig = findInterfaceSig(typeSig, RouteHandler.class.getName())) != null) {
                        // RouteHandler
                        @SuppressWarnings({ "unchecked" })
                        final var routeHandlerInstance = (RouteHandler<Object>) classWithRouteAnnotation.loadClass()
                                .getConstructor().newInstance();
                        routeHandlerOrder.add(new RouteInfo(vertx, classWithRouteAnnotation.getName(),
                                routeAnnotation, routeAnnotationInfo, ifaceSig, templateModelClassToHTMLTemplate,
                                htmlPageModeTemplate, serverUri, scanResult, routeHandlerInstance));

                    } else if ((ifaceSig = findInterfaceSig(typeSig, SocketHandler.class.getName())) != null) {
                        // SocketHandler
                        @SuppressWarnings({ "unchecked" })
                        final var socketHandlerInstance = (SocketHandler<Object>) classWithRouteAnnotation
                                .loadClass().getConstructor().newInstance();
                        routeHandlerOrder.add(new RouteInfo(vertx, classWithRouteAnnotation.getName(),
                                routeAnnotation, routeAnnotationInfo, ifaceSig, templateModelClassToHTMLTemplate,
                                htmlPageModeTemplate, serverUri, scanResult, socketHandlerInstance));

                    } else if ((ifaceSig = findInterfaceSig(typeSig, Handler.class.getName())) != null) {
                        // Handler<RoutingContext> or Handler<SockJSSocket>
                        final var handlerInstanceUntyped = (Handler<?>) classWithRouteAnnotation.loadClass()
                                .getConstructor().newInstance();
                        routeHandlerOrder.add(new RouteInfo(vertx, classWithRouteAnnotation.getName(),
                                routeAnnotation, routeAnnotationInfo, ifaceSig, handlerInstanceUntyped));

                    } else {
                        throw new IllegalArgumentException("Class does not implement a required interface");
                    }

                } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
                    ServerxVerticle.logger.log(Level.SEVERE,
                            "Cannot add route handler class " + classWithRouteAnnotation.getName() + " : " + e);
                }
            }
        }

        // Finish initializing templates
        for (final var htmlTemplate : templateModelClassToHTMLTemplate.values()) {
            htmlTemplate.finishInit();
        }

        // Sort handlers into priority order, then lexicographic order
        Collections.sort(routeHandlerOrder);
        return routeHandlerOrder;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sort handlers into priority order, then lexicographic order.
     *
     * @param o
     *            the other {@link RouteInfo} object.
     * @return the difference.
     */
    @Override
    public int compareTo(final RouteInfo o) {
        // Sort in decreasing order of routing priority
        final int diff0 = o.routeAnnotation.routingPriority() - this.routeAnnotation.routingPriority();
        if (diff0 != 0) {
            return diff0;
        }
        // Then sort in lexicographic order of path
        final int diff1 = this.routeAnnotation.path().compareTo(o.routeAnnotation.path());
        if (diff1 != 0) {
            return diff1;
        }
        // Then sort into increasing order of method
        final int diff2 = this.routeAnnotation.method().ordinal() - o.routeAnnotation.method().ordinal();
        if (diff2 != 0) {
            return diff2;
        }
        // Then compare isBlocking for equality
        return this.routeAnnotation.isBlocking() == o.routeAnnotation.isBlocking() ? 0 : 1;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final var buf = new StringBuilder();
        buf.append(routeAnnotation.method().name() + " " + routeAnnotation.path() + " -> " + handlerClassName);

        // Append non-default param values to buf
        final var set = new LinkedHashSet<AnnotationParameterValue>(routeAnnotationInfo.getParameterValues());
        set.removeAll(routeAnnotationInfo.getDefaultParameterValues());
        for (final var apv : set) {
            final var apvName = apv.getName();
            // method and value were already added to buf
            if (!apvName.equals("method") && !apvName.equals("value")) {
                buf.append(' ');
                final var apvVal = apv.getValue();
                if (apvVal instanceof AnnotationEnumValue) {
                    // Show only the value, not the class name, for enum values
                    buf.append(apv.getName() + "=" + ((AnnotationEnumValue) apvVal).getValueName());
                } else {
                    buf.append(apv);
                }
            }
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Destroy the session.
     *
     * @param ctx
     *            the context.
     */
    private static void destroySession(final RoutingContext ctx) {
        final var session = ctx.session();
        final var accessToken = ((AccessToken) ctx.user());
        if (accessToken != null) {
            accessToken.clearCache();
        }
        if (session != null && !session.isDestroyed()) {
            session.destroy();
        }
    }

    /**
     * Create handler for fetching user info (including email address) using OpenID Connect.
     *
     * @param route
     *            the route
     * @param mongoClient
     *            the mongo client
     * @return the handler
     */
    private static Handler<RoutingContext> createUserInfoFetchHandler(final Route route,
            final MongoClient mongoClient) {
        return ctx -> {
            // Make sure session and access token are valid and that access token has not expired
            final var session = ctx.session();
            final var accessToken = ((AccessToken) ctx.user());
            if (session == null || session.isDestroyed() || accessToken == null || accessToken.expired()) {
                // Request was not routed through a SessionHandler, or session was destroyed, or access tok expired
                destroySession(ctx);
                ctx.fail(HttpURLConnection.HTTP_UNAUTHORIZED);
                return;
            }

            final var emailCached = (String) session.get(EMAIL_SESSION_PROPERTY_KEY);
            if (emailCached != null) {
                // Email address is already cached -- move to authorization handler
                ctx.next();

            } else {
                // Permissions are looked up based on email address, but first email address has to be
                // looked up from the access token using OpenID Connect
                final var userInfoFuture = Future.<JsonObject> future();
                final var userInfo = (JsonObject) session.get("userInfo");
                if (userInfo != null) {
                    // Use cached userInfo
                    userInfoFuture.complete(userInfo);
                } else {
                    // There is no cached userInfo, need to fetch it via OIDC
                    accessToken.userInfo(userInfoFuture);
                }
                userInfoFuture.setHandler(ar -> {
                    if (ar.failed()) {
                        // OpenID Connect request failed because the token was revoked
                        destroySession(ctx);
                        ctx.fail(HttpURLConnection.HTTP_UNAUTHORIZED);
                        return;
                    }

                    // Get email address from userInfo
                    // N.B. Google says re email address:
                    // https://developers.google.com/identity/protocols/OpenIDConnect
                    // "This may not be unique and is not suitable for use as a primary key. Provided only
                    // if your scope included the string 'email'."
                    // However, even if there are multiple Google accounts with the same email address,
                    // we can use this as a primary key for RBAC permission purposes.
                    final var receivedUserInfo = ar.result();
                    final var email = receivedUserInfo.getString("email");
                    if (email == null) {
                        // Missing email field in userInfo (should not happen
                        ctx.fail(HttpURLConnection.HTTP_BAD_REQUEST);
                        return;
                    }

                    // Rename the "email" field to "_id", so MongoDB uses email addr as PK
                    receivedUserInfo.remove("email");
                    receivedUserInfo.put("_id", email);

                    // Write email address and userInfo to session (this is done before the database save
                    // operation below, so that if the save operation fails, there will still be usable
                    // information in the session)
                    session.put(EMAIL_SESSION_PROPERTY_KEY, email);
                    session.put(USER_INFO_SESSION_PROPERTY_KEY, receivedUserInfo);

                    // Write userInfo to MongoDB (this will write over any previous information, so that
                    // with each authentication, we get a copy of the most up to date information)
                    mongoClient.updateCollectionWithOptions(USER_INFO_COLLECTION_NAME,
                            new JsonObject().put("_id", email), receivedUserInfo,
                            new UpdateOptions().setUpsert(true), //
                            updateResult -> {
                                if (updateResult.failed()) {
                                    ctx.fail(updateResult.cause());
                                    return;
                                }
                                // Successfully saved user info to database, move to next handler
                                ctx.next();
                            });
                });
            }
        };
    }

    /**
     * Create authorization handler.
     *
     * @param route
     *            the route
     * @param mongoClient
     *            the mongo client
     * @return the handler
     */
    private static Handler<RoutingContext> createAuthorizationHandler(final Route route,
            final MongoClient mongoClient) {
        return ctx -> {
            // Check if permissions have already been cached
            final var session = ctx.session();
            final var permissionsFuture = Future.<JsonObject> future();
            final var permissionsCached = (JsonObject) session.get(PERMISSIONS_SESSION_PROPERTY_KEY);
            if (permissionsCached != null) {
                // Fast path -- permissions have already been cached, no need to access database
                permissionsFuture.complete(permissionsCached);
            } else {
                // Otherwise look up permissions in database based on email address
                final var email = (String) session.get(EMAIL_SESSION_PROPERTY_KEY);
                if (email == null) {
                    // Should not happen, email address was checked for null in the previous handler
                    ctx.fail(HttpURLConnection.HTTP_BAD_REQUEST);
                    return;
                }
                mongoClient.find(PERMISSIONS_COLLECTION_NAME, new JsonObject().put("_id", email),
                        permissionsResult -> {
                            JsonObject dbPermissions;
                            if (permissionsResult.succeeded() && permissionsResult.result().size() == 1) {
                                // There was a permissions entry for this email address -- copy to session.
                                // Entry in this key should be a map from permission name to Boolean.
                                dbPermissions = (JsonObject) permissionsResult.result().get(0)
                                        .getValue(PERMISSIONS_SESSION_PROPERTY_KEY);
                            } else {
                                // If permissions were not found in the database, store an empty permissions
                                // object in the session, to avoid querying the database again
                                dbPermissions = new JsonObject();
                            }
                            // Store permissions in the session, then complete the future
                            session.put(PERMISSIONS_SESSION_PROPERTY_KEY, dbPermissions);
                            permissionsFuture.complete(dbPermissions);
                        });
            }
            permissionsFuture.setHandler(ar -> {
                // User must have all required permissions to access route
                final var userPermissions = permissionsFuture.result();
                for (final var reqdPermission : route.permissions()) {
                    final Object permissionVal = userPermissions.getValue(reqdPermission);
                    if (!(permissionVal instanceof Boolean) || !((Boolean) permissionVal)) {
                        ctx.fail(HttpURLConnection.HTTP_FORBIDDEN);
                        return;
                    }
                }
                // User has all necessary permissions
                ctx.next();
            });
        };
    }

    /**
     * Add authorization and authentication handlers.
     *
     * @param route
     *            the route
     * @param authHandler
     *            the auth handler
     * @param mongoClient
     *            the mongo client
     * @param vertxRoute
     *            the vertx route
     */
    private static void addAuthHandlers(final Route route, final OAuth2AuthHandler authHandler,
            final MongoClient mongoClient, final io.vertx.ext.web.Route vertxRoute) {
        if (route.requireLogin() || route.permissions().length > 0) {
            // Add Google OAuth2 authentication handler
            vertxRoute.handler(authHandler);

            // Add a handler for fetching "email", "userInfo" and "permissions" session properties,
            // given an authentication token
            vertxRoute.handler(createUserInfoFetchHandler(route, mongoClient));

            // Add authorization handler if needed
            if (route.permissions().length > 0) {
                vertxRoute.handler(createAuthorizationHandler(route, mongoClient));
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Adds the routes.
     *
     * @param vertx
     *            the vertx
     * @param router
     *            the router
     * @param authHandler
     *            the auth handler
     * @param mongoClient
     *            the mongo client
     * @param serverUri
     *            the server URI
     */
    public static void addRoutes(final Vertx vertx, final Router router, final OAuth2AuthHandler authHandler,
            final MongoClient mongoClient, final URI serverUri) {
        // Get all route info, add route handlers in order 
        for (final var routeInfo : getRouteInfo(vertx, serverUri)) {
            ServerxVerticle.logger.info("Found " + (routeInfo.isSocketHandler ? "SockJS" : "HTTP") + "->"
                    + routeInfo.routeAnnotation.responseType()
                    + (routeInfo.routeAnnotation.isFailureHandler() ? " failure" : "")
                    + (routeInfo.routeAnnotation.isBlocking() ? " blocking" : "") + " handler: " + routeInfo);

            final var route = routeInfo.routeAnnotation;

            // Create the route
            io.vertx.ext.web.Route vertxRoute = router.route();

            // Disable the route if necessary
            if (route.isDisabled()) {
                vertxRoute.disable();
            }

            // Install a BodyHandler if needed (needs to be installed as early as possible, before
            // any async handlers).
            // TODO: Can this be put after the auth handler? (probably not, because that uses async methods)
            if (route.requestBodySizeLimit() > 0) {
                final var bodyHandler = BodyHandler.create();
                bodyHandler.setBodyLimit(route.requestBodySizeLimit());
                bodyHandler.setMergeFormAttributes(true);
                bodyHandler.setDeleteUploadedFilesOnEnd(true);
                final var uploadsDirFile = new File(ServerxVerticle.serverProperties.uploadsDir);
                uploadsDirFile.mkdirs();
                if (!uploadsDirFile.canWrite() || !uploadsDirFile.canRead()) {
                    throw new RuntimeException("Uploads dir \"" + ServerxVerticle.serverProperties.uploadsDir
                            + "\" is not accessible");
                }
                bodyHandler.setUploadsDirectory(ServerxVerticle.serverProperties.uploadsDir);
                vertxRoute.handler(bodyHandler);
            }

            // Add the HTTP method filters to the route
            vertxRoute.method(route.method());
            for (final var additionalMethod : route.additionalMethods()) {
                vertxRoute.method(additionalMethod);
            }

            // Add the path filter to the route
            final var routePath = route.path();
            if (!routePath.isEmpty()) {
                vertxRoute = route.isRegex() ? vertxRoute.pathRegex(routePath) : vertxRoute.path(routePath);
            }

            // Add authentication and authorization handlers if needed
            addAuthHandlers(route, authHandler, mongoClient, vertxRoute);

            if (routeInfo.isSocketHandler) {
                // SockJS handler -- must be added after checking for authentication, if needed
                vertxRoute.handler(routeInfo.routingContextHandlerInstance);
                // Skip other route settings
                continue;
            }

            // Filter based on "Content-Type:" header, if necessary
            for (final var contentType : route.consumesContentTypes()) {
                vertxRoute.consumes(contentType);
            }
            if (route.consumesContentTypes().length > 0) {
                // Automatically set the "Content-Type" header in the response for the acceptable content type
                vertxRoute.handler(ctx -> {
                    ctx.response().putHeader("Content-Type", ctx.getAcceptableContentType());
                });
            }

            // Filter based on "Accept:" header, if necessary
            if (route.producesContentTypes().length > 0) {
                // Automatically add "Content-Type" header based on response type -- TODO: also adding content-type above, and user might do so too
                vertxRoute.handler(ResponseContentTypeHandler.create());
                for (final var contentType : route.producesContentTypes()) {
                    vertxRoute.produces(contentType);
                }
            }

            // Add the multipart filter for POST requests, if enabled (the default)
            if (route.method() == HttpMethod.POST && route.postIsMultipart() //
            // Don't need to add multipart support if there is a BodyHandler
            // TODO: check if multipart support is needed with a BodyHandler 
                    && route.requestBodySizeLimit() < 0L) {
                vertxRoute.handler(ctx -> {
                    ctx.request().setExpectMultipart(true);
                    ctx.next();
                });
            }

            // Add the route handler
            if (route.isFailureHandler()) {
                // HTTP failure handler -- wrap with another handler to set response code and log internal errors
                vertxRoute.failureHandler(ctx -> {
                    if (route.failureStatusCode() == 0 || route.failureStatusCode() == ctx.statusCode()) {
                        // Set the status code of the result based on the status code of the failure,
                        // and log any internal server errors
                        WebUtils.setFailureStatusAndLogInternalError(ctx);
                        // Call the failure handler
                        try {
                            routeInfo.routingContextHandlerInstance.handle(ctx);
                        } catch (final Throwable t) {
                            ServerxVerticle.logger.log(Level.SEVERE, "Failure handler threw an exception", t);
                            ctx.next();
                        }
                    } else {
                        // Status code didn't match
                        ctx.next();
                    }
                });
            } else if (route.isBlocking()) {
                // Blocking HTTP handler
                vertxRoute.blockingHandler(routeInfo.routingContextHandlerInstance);
            } else {
                // HTTP handler
                vertxRoute.handler(routeInfo.routingContextHandlerInstance);
            }
        }
    }
}