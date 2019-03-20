package serverx.server;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.logging.Level;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import serverx.route.Route;
import serverx.template.TemplateModel;

/** The configuration object. */
public class ServerProperties {
    /**
     * The HTTP port. "There are several ways to run as a under privileged user, you can use iptables to forward
     * requests to higher ports, use authbind, run behind a proxy like ngnix, etc..."
     */
    public int httpPort = 80;

    /**
     * The HTTPS port. "There are several ways to run as a under privileged user, you can use iptables to forward
     * requests to higher ports, use authbind, run behind a proxy like ngnix, etc..."
     */
    public int httpsPort = 443;

    /** The host name (required). */
    public String host;

    /** If true, enable SSL (HTTPS). */
    public boolean useSSL = true;

    /** If true, use BoringSSL rather than JDK SSL for performance. */
    public boolean useBoringSSL = true;

    /**
     * The path to the SSL certificate. If unspecified, defaults to "/etc/letsencrypt/live/${host}/fullchain.pem".
     */
    public String pemCertFilePath = "";

    /**
     * The path to the SSL private key file. If unspecified, defaults to
     * "/etc/letsencrypt/live/${host}/privkey.pem".
     */
    public String pemKeyFilePath = "";

    /** The salt value to use for password hashing. */
    public String salt;

    /**
     * The compression level to use in the response: 0 to disable, 1 for fast compression, 9 for high compression.
     */
    public int compressionLevel = 1;

    /** The MongoDB database name (required). */
    public String dbName;

    /** The MongoDB database connection string. */
    public String dbConnectionURI = "mongodb://localhost:27017";

    /**
     * The path to the properties file containing Google secrets (required). <b>The properties file should not be in
     * a git repository!</b> File should contain two properties: {@code clientId} and {@code clientSecret}.
     */
    public String googleSecretProperties;

    /** Whether or not to display exception stacktraces on the error page. */
    public boolean displayStackTraceOnError = false;

    /** The CORS allowed origin header. */
    public String corsAllowedOriginPattern = "";

    /** CORS allowed methods, comma-separated, from the list in {@link HttpMethod} (e.g. {@code GET}). */
    public String corsAllowMethods = "";

    /** The request timeout in milliseconds. */
    public int requestTimeout_ms = 10_000;

    /** The package to scan for {@link Handler} classes with {@link Route} annotations (required). */
    public String handlerPackage;

    /** The package to scan for {@link TemplateModel} subclasses (required). */
    public String templateModelPackage;

    /** The URL to redirect to if the user is not logged in and requests a resource that requires authentication. */
    public String loginRedirectURL = "/login";

    /** The static resource root URL. */
    public String staticResourceURL = "/static";

    /** The static resource directory. */
    public String staticResourceDir = "webroot";

    /** The uploads directory, used when {@link Route#requestBodySizeLimit()} > 0. */
    public String uploadsDir = "uploads";

    /** If true, pretty print (indent) HTML. */
    public boolean indentHTML = false;

    /** If true, pretty print (indent) JSON. */
    public boolean indentJSON = false;

    /**
     * Default HTML page template to use. Should include template fields {@code _title} and {@code _body}. A basic
     * template at {@code serverx/model/HTMLPageModel.html} is used by default.
     */
    public String defaultHTMLPageTemplate = "";

    /**
     * If true, allow non-public fields from {@link TemplateModel} objects to be named in HTML templates, and
     * serialize/deserialize non-public fields to/from JSON.
     */
    public boolean enableNonPublicDataModelFields = true;

    // -------------------------------------------------------------------------------------------------------------

    /** The filename to read properties from. */
    private static final String PROPERTIES_FILENAME = "server.properties";

    // -------------------------------------------------------------------------------------------------------------

    /** Constructor. */
    ServerProperties() {
        final Properties prop = new Properties();
        try (var inputStream = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILENAME)) {
            if (inputStream == null) {
                throw new RuntimeException("Could not find " + PROPERTIES_FILENAME + " resource file");
            }
            prop.load(inputStream);
        } catch (final IOException e) {
            throw new RuntimeException("Could not read " + PROPERTIES_FILENAME + " resource file");
        }

        for (final var field : getClass().getDeclaredFields()) {
            try {
                if ((field.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0) {
                    final var propVal = prop.getProperty(field.getName());
                    if (propVal != null) {
                        try {
                            if (field.getType() == String.class) {
                                field.set(this, propVal);
                            } else if (field.getType() == Integer.class) {
                                field.set(this, Integer.valueOf(propVal));
                            } else if (field.getType() == int.class) {
                                field.setInt(this, Integer.parseInt(propVal));
                            } else if (field.getType() == Boolean.class) {
                                field.set(this, Boolean.valueOf(propVal));
                            } else if (field.getType() == boolean.class) {
                                field.setBoolean(this, Boolean.parseBoolean(propVal));
                            } else {
                                throw new RuntimeException(
                                        "Field " + field.getName() + " has illegal type " + field.getType());
                            }
                        } catch (final NumberFormatException e) {
                            throw new RuntimeException(
                                    "Property " + field.getName() + " has non-numerical value " + propVal);
                        }
                    }
                    final var fieldVal = field.get(this);
                    if (fieldVal == null) {
                        throw new RuntimeException("Required property " + field.getName() + " is missing from file "
                                + PROPERTIES_FILENAME);
                    }
                    ServerxVerticle.logger.log(Level.INFO, "serverProperties." + field.getName() + ": " + fieldVal);
                }
            } catch (final IllegalAccessException e) {
                // Should not happen
                throw new RuntimeException("Field " + field.getName() + " could not be accessed");
            }
        }
    }
}
