package serverx.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.auth.VertxContextPRNG;
import io.vertx.ext.auth.oauth2.providers.GoogleAuth;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CSRFHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.FaviconHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.OAuth2AuthHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import io.vertx.ext.web.handler.UserSessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import serverx.route.RouteInfo;
import serverx.utils.URLExternalizer;
import serverx.utils.WebUtils;

/**
 * Verticle.
 */
public class ServerxVerticle extends AbstractVerticle {
    /** Logger. */
    public final static Logger logger = Logger.getLogger(ServerxVerticle.class.getName());

    /** Configuration. */
    public static final ServerProperties serverProperties = new ServerProperties();
    {
        readServerProperties();
    }

    /** The MongoDB client. */
    public static MongoClient mongoClient;

    /** Read the server properties file. */
    private void readServerProperties() {
        final Properties prop = new Properties();
        try (var inputStream = getClass().getClassLoader()
                .getResourceAsStream(ServerProperties.PROPERTIES_FILENAME)) {
            if (inputStream == null) {
                throw new RuntimeException(
                        "Could not find " + ServerProperties.PROPERTIES_FILENAME + " resource file");
            }
            prop.load(inputStream);
        } catch (final IOException e) {
            throw new RuntimeException("Could not read " + ServerProperties.PROPERTIES_FILENAME + " resource file");
        }

        for (final var field : ServerProperties.class.getDeclaredFields()) {
            try {
                if ((field.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0) {
                    final var propVal = prop.getProperty(field.getName());
                    if (propVal != null) {
                        try {
                            if (field.getType() == String.class) {
                                field.set(serverProperties, propVal);
                            } else if (field.getType() == Integer.class) {
                                field.set(serverProperties, Integer.valueOf(propVal));
                            } else if (field.getType() == int.class) {
                                field.setInt(serverProperties, Integer.parseInt(propVal));
                            } else if (field.getType() == Boolean.class) {
                                field.set(serverProperties, Boolean.valueOf(propVal));
                            } else if (field.getType() == boolean.class) {
                                field.setBoolean(serverProperties, Boolean.parseBoolean(propVal));
                            } else {
                                throw new RuntimeException(
                                        "Field " + field.getName() + " has illegal type " + field.getType());
                            }
                        } catch (final NumberFormatException e) {
                            throw new RuntimeException(
                                    "Property " + field.getName() + " has non-numerical value " + propVal);
                        }
                    }
                    final var fieldVal = field.get(serverProperties);
                    if (fieldVal == null) {
                        throw new RuntimeException("Required property " + field.getName() + " is missing from file "
                                + ServerProperties.PROPERTIES_FILENAME);
                    }
                    ServerxVerticle.logger.log(Level.INFO, "serverProperties." + field.getName() + ": " + fieldVal);
                }
            } catch (final IllegalAccessException e) {
                // Should not happen
                throw new RuntimeException("Field " + field.getName() + " could not be accessed");
            }
        }
    }

    /**
     * Die.
     *
     * @param errMsg
     *            the err msg
     */
    static void die(final String errMsg) {
        System.err.println(errMsg);
        System.exit(1);
    }

    /**
     * Die.
     *
     * @param errMsg
     *            the err msg
     * @param t
     *            the t
     */
    static void die(final String errMsg, final Throwable t) {
        System.err.println(errMsg);
        t.printStackTrace();
        System.exit(1);
    }

    /* (non-Javadoc)
     * @see io.vertx.core.AbstractVerticle#init(io.vertx.core.Vertx, io.vertx.core.Context)
     */
    @Override
    public void init(final Vertx vertx, final Context context) {
        super.init(vertx, context);

        // Configure Json objectmapper (for JSON responses)
        if (serverProperties.enableNonPublicDataModelFields) {
            Json.mapper.setVisibility(PropertyAccessor.ALL, Visibility.ANY);
            Json.prettyMapper.setVisibility(PropertyAccessor.ALL, Visibility.ANY);
        }
        Json.mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        Json.prettyMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        //        try (com.mongodb.MongoClient mongo = new com.mongodb.MongoClient(serverProperties.dbConnectionStr)) {
        //              mongo.getDatabase(serverProperties.dbName).runCommand(new Document("ping", "1"));
        //        } catch (MongoException e) {
        //              die("MongoDB is not running");
        //        }

        if (!WebUtils.portIsAvailable(serverProperties.httpPort)) {
            die("Port " + serverProperties.httpPort + " is not available");
        }
        if (serverProperties.useSSL && !WebUtils.portIsAvailable(serverProperties.httpsPort)) {
            die("Port " + serverProperties.httpsPort + " is not available");
        }

        try {
            // TODO: replace this with a proper check to see if MongoDB is running
            final URI uri = new URI(serverProperties.dbConnectionURI);
            if (WebUtils.portIsAvailable(uri.getPort())) {
                die("MongoDB is not running on port " + uri.getPort());
            }
        } catch (final URISyntaxException e) {
            die("Malformed database connection URI: " + serverProperties.dbConnectionURI);
        }

        // Create the MongoClient instance
        // TODO: Fail if MongoDB is not running
        mongoClient = MongoClient.createShared(vertx, new JsonObject().put("db_name", serverProperties.dbName)
                .put("connection_string", serverProperties.dbConnectionURI));
    }

    /** The Constant DEFAULT_ERROR_HANDLER. */
    private static final Handler<RoutingContext> DEFAULT_ERROR_HANDLER = ctx -> {
        final var cause = ctx.failure();
        final var causeSuffix = cause != null ? ": " + cause.getClass().getName() : "";
        if (ctx.response().ended()) {
            // Response has already ended, can't send anything
            logger.log(Level.SEVERE, "Response already ended before default error handler could run" + causeSuffix,
                    cause);
        } else {
            if (ctx.response().headWritten()) {
                // Header has already been written, so can't send error code
                logger.log(Level.SEVERE,
                        "Head already written before default error handler could run" + causeSuffix, cause);
                // Body may or may not be written, so it's best to just reset the connection rather than
                // write an error message after whatever was already sent
                ctx.response().reset();
            } else {
                // Write default error message to the response
                // TODO: Make this an HTML page
                final var statusCode = WebUtils.setFailureStatusAndLogInternalError(ctx);
                ctx.response().setStatusCode(statusCode).putHeader("content-type", "text/plain; charset=utf-8")
                        .end("Unhandled " + (statusCode == 500 ? "internal error" : "failure (" + statusCode + ")")
                                + causeSuffix);
            }
        }
    };

    /* (non-Javadoc)
     * @see io.vertx.core.AbstractVerticle#start(io.vertx.core.Future)
     */
    @Override
    public void start(final Future<Void> startFuture) {
        try {
            final var redirectServerFuture = Future.future();
            if (serverProperties.useSSL) {
                // Redirect HTTP requests to HTTPS
                final var httpsRedirectRouter = Router.router(vertx);
                httpsRedirectRouter.route().handler(URLExternalizer.httpToHttpsHandler(serverProperties.httpsPort));
                logger.info("Starting HTTP-to-HTTPS redirect server on port " + serverProperties.httpPort);
                vertx.createHttpServer(
                        new HttpServerOptions().setPort(serverProperties.httpPort).setHost(serverProperties.host))
                        .requestHandler(httpsRedirectRouter).listen(serverProperties.httpPort, result -> {
                            if (result.succeeded()) {
                                redirectServerFuture.complete();
                            } else {
                                redirectServerFuture.fail(result.cause());
                            }
                        });
            } else {
                redirectServerFuture.complete();
            }

            // Create the router
            final var router = Router.router(vertx);

            //        // Virtual host handler
            //        router.route().handler(VirtualHostHandler.create("*.vertx.io", routingContext -> {
            //            // do something if the request is for *.vertx.io
            //        }));

            // Serve static resources -- added before session and authentication handlers for speed
            final var staticDir = new File(serverProperties.staticResourceDir);
            if (!staticDir.exists()) {
                logger.log(Level.SEVERE, "Static dir does not exist: " + serverProperties.staticResourceDir);
            } else if (!staticDir.isDirectory()) {
                logger.log(Level.SEVERE, "Static dir is not a directory: " + serverProperties.staticResourceDir);
            } else if (!staticDir.canRead()) {
                logger.log(Level.SEVERE, "Static dir cannot be read: " + serverProperties.staticResourceDir);
            } else {
                // Found static resource dir
                logger.info("Found static resource dir: " + staticDir.getAbsolutePath());
                final var handler = StaticHandler.create(serverProperties.staticResourceDir) //
                        // Disable directory listing and hidden files/dirs for security
                        .setDirectoryListing(false) //
                        .setIncludeHidden(false) //
                        .setEnableRangeSupport(true) //
                        .setCachingEnabled(true) //
                        // .setFilesReadOnly(true) // If files are read-only and will never change
                        .setMaxAgeSeconds(60 * 60 * 24) //
                        // .setHttp2PushMapping(http2PushMappings) // TODO
                        .setAlwaysAsyncFS(true) //
                        .setEnableFSTuning(true);
                router.route(serverProperties.staticResourceURL + "/*").handler(handler);

                // Look for favicon in static resource dir
                // TODO: add a default favicon
                final var faviconFile = new File(staticDir, "favicon.ico");
                if (faviconFile.exists() && faviconFile.isFile() && faviconFile.canRead()) {
                    // Add favicon handler
                    router.route().handler(FaviconHandler.create());
                }
            }

            // Add default security headers -- see: https://vertx.io/blog/writing-secure-vert-x-web-apps/
            router.route().handler(ctx -> {
                ctx.response()
                        // do not allow proxies to cache the data
                        .putHeader("Cache-Control", "no-store, no-cache")
                        // prevents Internet Explorer from MIME - sniffing a
                        // response away from the declared content-type
                        .putHeader("X-Content-Type-Options", "nosniff")
                        // Strict HTTPS (for about ~6Months)
                        .putHeader("Strict-Transport-Security", "max-age=" + 15768000)
                        // IE8+ do not allow opening of attachments in the context of this resource
                        .putHeader("X-Download-Options", "noopen")
                        // enable XSS for IE
                        .putHeader("X-XSS-Protection", "1; mode=block")
                        // deny frames
                        .putHeader("X-FRAME-OPTIONS", "DENY");
                ctx.next();
            });

            // Add a cookie handler (has to be added before session handler)
            router.route().handler(CookieHandler.create());

            // Add a session handler
            // TODO: for a clustered session store, use ClusteredSessionStore.create(vertx);
            // TODO: default timeout is 30 mins.
            final var sessionStore = LocalSessionStore.create(vertx);
            router.route().handler(SessionHandler.create(sessionStore)
                    // Security options
                    .setCookieHttpOnlyFlag(true) //
                    // Not enabling secure cookies leaves session open to hijacking
                    .setCookieSecureFlag(serverProperties.useSSL) //
                    .setNagHttps(true));

            // -----------------------------------------------------------------------------------------------------

            // Callback has to go to an https URL
            if (!serverProperties.useSSL) {
                // TODO: maybe instead disable OAuth2 if useSSL is false?
                throw new RuntimeException("Google OAuth2 callback requires useSSL to be set to true");
            }

            // Read Google OAuth secrets 
            final var googleSecretsFile = new File(serverProperties.googleSecretProperties);
            if (!googleSecretsFile.exists()) {
                throw new RuntimeException(
                        "Google secrets file does not exist: " + serverProperties.googleSecretProperties);
            }
            final var googleSecrets = new Properties();
            try (var inputStream = new FileInputStream(googleSecretsFile)) {
                googleSecrets.load(inputStream);
            } catch (final IOException e) {
                throw new RuntimeException("Cannot read " + googleSecretsFile + ": " + e);
            }
            final var clientId = googleSecrets.getProperty("clientId");
            final var clientSecret = googleSecrets.getProperty("clientSecret");
            if (clientId == null || clientSecret == null) {
                // TODO: currently Google OAuth must be set up
                throw new RuntimeException("Please provide Google OAuth credentials in file " + googleSecretsFile);
            }
            // Create Google OAuth provider
            final var googleAuthProvider = GoogleAuth.create(vertx, clientId, clientSecret);
            router.route().handler(UserSessionHandler.create(googleAuthProvider));

            final var callbackPath = "/oauth2/google/callback";
            final var googleAuthHandler = OAuth2AuthHandler.create(googleAuthProvider,
                    "https://" + ServerxVerticle.serverProperties.host + callbackPath);
            googleAuthHandler
                    // Set up callback handler
                    .setupCallback(router.route(callbackPath))
                    // Add authorities (these three are the authorities provided by Google)
                    .addAuthority("email") //
                    .addAuthority("profile") //
                    .addAuthority("openid"); // TODO: is openid needed?
            router.route().handler(googleAuthHandler);

            //            // Set up MongoDB-based auth provider after the Google OAuth provider, to handle roles and permissions
            //            // after a user logs in with OAuth2
            //            JsonObject authProperties = new JsonObject();
            //            MongoAuth mongoAuthProvider = MongoAuth.create(mongoClient, authProperties);
            //
            //            // OWASP-recommended hash algorithm for 2018 
            //            mongoAuthProvider.setHashAlgorithm(HashAlgorithm.PBKDF2);
            //
            //            // Set the external salt value for password hashing
            //            HashStrategy hashStrategy = mongoAuthProvider.getHashStrategy();
            //            hashStrategy.setSaltStyle(HashSaltStyle.EXTERNAL);
            //            hashStrategy.setExternalSalt(serverProperties.salt);

            // -----------------------------------------------------------------------------------------------------

            // Add CSRF handler
            router.route().handler(CSRFHandler.create(/* secret = */ VertxContextPRNG.current(vertx).nextString(32))
                    .setNagHttps(true));

            // Add a CORS handler
            if (!serverProperties.corsAllowedOriginPattern.isBlank()
                    && !serverProperties.corsAllowMethods.isBlank()) {
                final var corsAllowedMethodsSet = new HashSet<HttpMethod>();
                for (final var methodStr : serverProperties.corsAllowMethods.split(",")) {
                    if (!methodStr.isBlank()) {
                        try {
                            corsAllowedMethodsSet.add(HttpMethod.valueOf(methodStr));
                        } catch (final Exception e) {
                            throw new RuntimeException("Invalid cors.allowed.methods value: " + methodStr);
                        }
                    }
                }
                router.route().handler(CorsHandler.create(serverProperties.corsAllowedOriginPattern)
                        .allowedMethods(corsAllowedMethodsSet));
            }

            // Add logging handler
            router.route().handler(LoggerHandler.create());

            // Add a timeout handler
            router.route().handler(TimeoutHandler.create(serverProperties.requestTimeout_ms));

            // Produce server base URI
            final var serverUri = new URI(serverProperties.useSSL ? "https" : "http", null, serverProperties.host,
                    serverProperties.useSSL ? serverProperties.httpsPort : serverProperties.httpPort, null, null,
                    null);

            // Add route handlers
            try {
                RouteInfo.addRoutes(vertx, router, googleAuthHandler, mongoClient, serverUri);
            } catch (final Exception e) {
                die("Could not add routes", e);
            }

            // Default failure handler, called if there are no other failure handler registered (this is added
            // after all route handlers, so that it can be overridden by user-provided failure handlers)
            router.route().failureHandler(DEFAULT_ERROR_HANDLER);

            // Default internal server error handler. Should only be called if a failure handler fails, or
            // if there are no failure handlers registered and there is an uncaught exception in a handler
            router.errorHandler(500, DEFAULT_ERROR_HANDLER);

            final var port = serverProperties.useSSL ? serverProperties.httpsPort : serverProperties.httpPort;
            final var httpServerOptions = new HttpServerOptions() //
                    .setPort(port) //
                    .setHost(serverProperties.host);
            if (serverProperties.compressionLevel == 0) {
                httpServerOptions.setCompressionSupported(false);
            } else if (serverProperties.compressionLevel > 0) {
                httpServerOptions.setCompressionSupported(true)
                        .setCompressionLevel(serverProperties.compressionLevel);
            }

            if (serverProperties.useSSL) {
                // Set up HTTPS, and make non-HTTPS URLs redirect to HTTP
                final var pemCertFilePath = serverProperties.pemCertFilePath.isEmpty()
                        ? "/etc/letsencrypt/live/" + serverProperties.host + "/fullchain.pem"
                        : serverProperties.pemCertFilePath;
                final var pemKeyFilePath = serverProperties.pemKeyFilePath.isEmpty()
                        ? "/etc/letsencrypt/live/" + serverProperties.host + "/privkey.pem"
                        : serverProperties.pemKeyFilePath;
                httpServerOptions //
                        .setUseAlpn(true) //
                        .setSsl(true) //
                        .setPemKeyCertOptions(new PemKeyCertOptions() //
                                .setCertPath(pemCertFilePath).setKeyPath(pemKeyFilePath));
                if (serverProperties.useBoringSSL) {
                    logger.log(Level.INFO, "Using BoringSSL rather than JDK SSL");
                    httpServerOptions.setOpenSslEngineOptions(new OpenSSLEngineOptions());
                }
            }

            // Start server
            logger.info("Starting server at " + serverUri);
            final var serverFuture = Future.future();
            final HttpServer server = vertx.createHttpServer(httpServerOptions);
            server.requestHandler(router).listen(port, result -> {
                if (result.succeeded()) {
                    serverFuture.complete();
                } else {
                    serverFuture.fail(result.cause());
                }
            });

            // TODO: call server.webSocketHandler(h) to set up webSocketHandler

            CompositeFuture.all(redirectServerFuture, serverFuture).setHandler(result -> {
                if (result.succeeded()) {
                    startFuture.complete();
                } else {
                    startFuture.fail(result.cause());
                }
            });
        } catch (final Exception e) {
            startFuture.fail(e);
        }
    }
}
