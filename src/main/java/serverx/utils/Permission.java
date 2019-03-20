package serverx.utils;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.UpdateOptions;
import io.vertx.ext.web.RoutingContext;
import serverx.route.RouteInfo;
import serverx.server.ServerxVerticle;

/**
 * Permission utilities.
 */
public class Permission {
    /**
     * Grant or revoke a permission.
     *
     * @param ctx
     *            the ctx
     * @param email
     *            the email
     * @param permission
     *            the permission
     * @param grant
     *            whether to grant (true) or revoke (false)
     * @param future
     *            the future
     */
    private static void grantOrRevoke(final RoutingContext ctx, final String email, final String permission,
            final boolean grant, final Future<?> future) {
        ServerxVerticle.mongoClient.updateCollectionWithOptions(RouteInfo.PERMISSIONS_COLLECTION_NAME,
                new JsonObject().put("_id", email),
                new JsonObject().put(grant ? "$set" : "$unset",
                        new JsonObject().put(RouteInfo.PERMISSIONS_SESSION_PROPERTY_KEY + "." + permission,
                                grant ? true : 1)),
                new UpdateOptions().setUpsert(true), //
                ar -> {
                    if (ar.succeeded()) {
                        if (ar.result().getDocModified() == 0) {
                            // Didn't change anything in the database
                            if (ar.result().getDocMatched() == 1) {
                                // But matched one record based on email address, so permission was already
                                // granted or revoked for user
                                future.fail("User " + email + (grant ? " already has" : " does not have")
                                        + " permission " + permission);
                            } else {
                                // Otherwise did not match email address in the database
                                future.fail("Failed to " + (grant ? "grant" : "revoke") + " permission "
                                        + permission + " for user " + email);
                            }
                        } else {
                            // Successfully granted or revoked permission in database.
                            // If granting permission, also add to session cache.
                            if (ctx != null && grant) {
                                var permissions = (JsonObject) ctx.session()
                                        .get(RouteInfo.PERMISSIONS_SESSION_PROPERTY_KEY);
                                if (permissions == null) {
                                    // Create a new permissions object in the session. 
                                    // N.B. there's a race condition here, since putIfAbsent isn't supported:
                                    // https://github.com/vert-x3/vertx-web/issues/1205
                                    // This could cause a permission to get lost, if multiple threads are
                                    // trying to add permissions (which is probably vanishingly rare).
                                    permissions = new JsonObject();
                                    ctx.session().put(RouteInfo.PERMISSIONS_SESSION_PROPERTY_KEY, permissions);
                                }
                                // Add permission to session cache
                                permissions.put(permission, Boolean.TRUE);
                            }
                            future.complete();
                        }
                    } else {
                        future.fail(ar.cause());
                    }
                });
    }

    /**
     * Grant or revoke a permission.
     *
     * @param ctx
     *            the ctx
     * @param permission
     *            the permission
     * @param grant
     *            whether to grant (true) or revoke (false)
     * @return the future
     */
    private static Future<?> grantOrRevoke(final RoutingContext ctx, final String permission, final boolean grant) {
        final var future = Future.future();
        if (ctx != null && !grant) {
            // If revoking, revoke permission immediately from session cache for safety.
            // (if granting, only add grant to session later after permission has been updated in database)
            final var permissions = (JsonObject) ctx.session().get(RouteInfo.PERMISSIONS_SESSION_PROPERTY_KEY);
            if (permissions != null) {
                // Remove cached permission, if present
                permissions.remove(permission);
            }
        }
        // Look up email address, since this is the key for the permissions collection in the database
        final var email = (String) ctx.session().get(RouteInfo.EMAIL_SESSION_PROPERTY_KEY);
        if (email == null) {
            future.fail("email property not found in session");
        } else {
            grantOrRevoke(ctx, email, permission, grant, future);
        }
        return future;
    }

    /**
     * Revoke the named permission from the current user.
     *
     * @param ctx
     *            the ctx
     * @param permission
     *            the permission
     * @return the future
     */
    public static Future<?> revoke(final RoutingContext ctx, final String permission) {
        return grantOrRevoke(ctx, permission, /* grant = */ false);
    }

    /**
     * Grant the named permission to the current user.
     *
     * @param ctx
     *            the ctx
     * @param permission
     *            the permission
     * @return the future
     */
    public static Future<?> grant(final RoutingContext ctx, final String permission) {
        return grantOrRevoke(ctx, permission, /* grant = */ true);
    }

    /**
     * Revoke the named permission from the named user. Will not update the session, since sessions are keyed by
     * session id rather than email address, so the user will need to log out and log back in for the changes to
     * take effect, or wait for the session to time out and be renewed. This is a security risk since revocation is
     * not immediate.
     *
     * @param email
     *            the email
     * @param permission
     *            the permission
     * @return the future
     */
    public static Future<?> revoke(final String email, final String permission) {
        return grantOrRevoke(/* ctx = */ null, permission, /* grant = */ false);
    }

    /**
     * Grant the named permission to the named user. Will not update the session, since sessions are keyed by
     * session id rather than email address, so the user will need to log out and log back in for the changes to
     * take effect, or wait for the session to time out and be renewed.
     *
     * @param email
     *            the email
     * @param permission
     *            the permission
     * @return the future
     */
    public static Future<?> grant(final String email, final String permission) {
        return grantOrRevoke(/* ctx = */ null, permission, /* grant = */ true);
    }
}
