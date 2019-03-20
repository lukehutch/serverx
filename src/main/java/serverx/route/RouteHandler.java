package serverx.route;

import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;

/**
 * HTTP request handler.
 *
 * @param <T>
 *            the generic type
 */
public interface RouteHandler<T> {
    /**
     * Handle an HTTP request.
     *
     * @param context
     *            the {@link RoutingContext}.
     * @param response
     *            the response {@link Future}, which wraps the response object.
     */
    public void handle(RoutingContext context, Future<T> response);
}
