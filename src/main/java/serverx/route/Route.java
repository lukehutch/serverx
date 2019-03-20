package serverx.route;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import serverx.template.TemplateModel;

/**
 * A route annotation for {@link Handler Handler&lt;RoutingContext&gt; or Handler&lt;SockJSSocket&gt;}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Route {
    /**
     * If true, disable the route.
     *
     * @return true, if the route is disabled
     */
    public boolean isDisabled() default false;

    /**
     * Routing priority. The higher the priority, the earlier the route is added to the router.
     *
     * @return the routing priority.
     */
    public int routingPriority() default 0;

    /**
     * If true, handler is an HTTP failure handler. Call {@link RoutingContext#statusCode()} to check the failure
     * status. Ignored for SockJS handlers.
     *
     * @return true, if this is a failure handler.
     */
    public boolean isFailureHandler() default false;

    /**
     * For failure handlers, specifies the status code to handle (leave at the default value of 0 to handle all
     * status codes). If 0, match all status codes.
     *
     * @return the failure status code to handle.
     */
    public int failureStatusCode() default 0;

    /**
     * If true, handler is a blocking HTTP handler, which causes requests to be handled in a separate thread pool.
     * N.B. if this is a blocking handler, and {@link #method()} is POST, then postIsMultipart should also be true
     * (which is the default). Ignored for SockJS handlers.
     *
     * @return true, if this is a blocking handler.
     */
    public boolean isBlocking() default false;

    /**
     * Required authorization permissions. A user must have all of the listed permissions to be able to access the
     * route. It is suggested that permissions should start with a prefix, e.g. "role:", to differentiate them from
     * approved OAuth2 scopes, which use the same cache.
     *
     * @return the required permissions.
     */
    public String[] permissions() default {};

    /**
     * If true, require the user to be logged in to view the route, without necessarily requiring any roles or
     * permissions.
     *
     * @return true, if route requires the user to be authenticated.
     */
    public boolean requireLogin() default true;

    /**
     * HTTP request method to handle.
     *
     * @return the HTTP method.
     */
    public HttpMethod method() default HttpMethod.GET;

    /**
     * Any additional HTTP request methods to handle, in addition to the main {@link #method()}.
     *
     * @return Additional HTTP method requests to handle.
     */
    public HttpMethod[] additionalMethods() default {};

    /**
     * If greater than zero, a {@link BodyHandler} is installed with this body size limit.
     * 
     * <p>
     * To read the body of the request, use {@link RoutingContext#getBody()},
     * {@link RoutingContext#getBodyAsString()}, {@link RoutingContext#getBodyAsJson()}, or
     * {@link RoutingContext#getBodyAsJsonArray()}.
     * 
     * <p>
     * To read file uploads, use {@link RoutingContext#fileUploads()}.
     * 
     * <p>
     * To read form attributes, use {@link HttpServerRequest#getParam(String)} (by default the {@link BodyHandler}
     * merges form attributes into request params).
     *
     * @return the body size limit.
     */
    public long requestBodySizeLimit() default 10 * 1024 * 1024;

    /**
     * If true, and if {@link #method()} is {@link HttpMethod#POST}, and if {@link #requestBodySizeLimit()} is -1L
     * (meaning there is no {@link BodyHandler}), the post data is expected to be multipart. If
     * non-{@link HttpMethod#POST}, this option is ignored.
     *
     * @return true, if the post data is expected to be multipart.
     */
    public boolean postIsMultipart() default true;

    /**
     * Specifies the MIME content-types to accept from the client's "Content-Type:" header, or accept all types if
     * the array of content types has zero length (the default).
     *
     * @return the MIME content types to accept.
     */
    public String[] consumesContentTypes() default {};

    /**
     * Specifies the MIME content-types to match from the client's "Accept:" HTTP header. You can find out the MIME
     * type that was actually accepted using {@link RoutingContext#getAcceptableContentType()}. The Content-Type
     * header will automatically be set in the response, using
     * {@code response.putHeader("Content-Type", acceptableContentType)}.
     *
     * @return the MIME content types to match from the client's "Accept:" HTTP header.
     */
    public String[] producesContentTypes() default {};

    /**
     * If true, interpret {@link #path()} as a regular expression.
     *
     * @return true, the path is a regular expression.
     */
    public boolean isRegex() default false;

    /**
     * The response type for the route.
     */
    public static enum ResponseType {
        /** Render the response object into HTML. The response object must implement {@link TemplateModel}). */
        HTML,
        /** Convert the response object to JSON. */
        JSON,
        /**
         * Call the {@link Object#toString()} method on the response object, and send it in the response as
         * "text/plain".
         */
        STRING;
    }

    /**
     * The response type.
     *
     * @return the response type.
     */
    public ResponseType responseType() default ResponseType.HTML;

    /**
     * For a {@link #responseType()} of {@link ResponseType#HTML}, this specifies the override resource path of the
     * HTML template to use to render the result object into HTML as an HTML fragment. If this parameter is not set
     * (i.e. is ""), first the {@link TemplateModel} result object is checked for a String-typed static final field
     * named "_template", and if not present, the resource with the same name as the class of the
     * {@link TemplateModel} result object (but with the {@code .html} extension) is used as the HTML template.
     *
     * @return the path of the override HTML template file. If {@code ""}, use the default template for the response
     *         object, which must be a {@link TemplateModel}.
     */
    public String htmlTemplatePath() default "";

    /**
     * For a {@link #responseType()} of {@link ResponseType#HTML}, this specifies the override resource path of the
     * HTML template to use for whole-page responses. If the {@link TemplateModel} includes a {@code _title} field,
     * it will be treated as a whole-page response rather than an HTML fragment response, and the rest of the
     * template will be rendered into an HTML fragment, which will be inserted into the HTML page template at the
     * {@code _content} template parameter. There is a basic HTML page template enabled by default.
     *
     * @return the path of the override HTML page template file. If {@code ""}, use the default page template.
     */
    public String htmlPageTemplatePath() default "";

    /**
     * The route path. Examples: {@code "/some/path"}, {@code "/path/prefix/*"}, {@code "/prod/:prodtype/:prodid"}.
     * Leave blank if the handler should apply to all paths (in particular, for failure handlers).
     * 
     * <p>
     * If {@link #isRegex()} is true, the path is a regexp, e.g. {@code "\\/([^\\/]+)\\/index.html"}. Any captured
     * parameters are named "param0", "param1", etc., or you can name the capture groups, e.g.
     * {@code "\\/(?<productType>[^\\/]+)\\/(?<productId>[^\\/]+)"}.
     * 
     * <p>
     * If the route path is empty, i.e. {@code ""}, then all requests with a matching {@link #method()} are routed
     * to the handler.
     *
     * @return the route path.
     */
    public String path() default "";
}