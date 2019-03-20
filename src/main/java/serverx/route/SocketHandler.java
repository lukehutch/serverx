package serverx.route;

import io.vertx.core.Future;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

/**
 * SockJS socket handler.
 *
 * @param <T>
 *            the generic type
 */
public interface SocketHandler<T> {
    /**
     * Handle a SockJS request.
     *
     * @param context
     *            the {@link SockJSSocket}.
     * @param response
     *            the response {@link Future}.
     */
    public void handle(SockJSSocket context, Future<T> response);
}
