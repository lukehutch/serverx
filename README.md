# Serverx

**Serverx** is a [Vert.x](https://vertx.io)-powered asynchronous multithreaded web server with simple annotation-based configuration of routes.

Serverx strives to enable the setup of a web server with a *minimum amount of code*, by encapsulating a large amount
of [Vert.x boilerplate](https://vertx.io/docs/vertx-web/java/) into a reusable library, and by allowing for flexible
and simple access to a wide array of features of the `vertx-core` and `vertx-web` projects.

Serverx is designed to be *secure by default*:

* [all reasonable security headers](https://vertx.io/blog/writing-secure-vert-x-web-apps#security-headers) are enabled by default
* the built-in template engine follows all OWASP guidelines for escaping parameters
* XSS protection measures are put in place automatically
* CSRF protection is put in place automatically (the built-in template engine automatically inserts CSRF tokens into HTML forms, and the server will check the token on non-`GET` requests)
* SSL (and HTTP/2 + ALPN) is enabled by default, and `http://` requests automatically redirect to `https://` requests with the same path
* routes require a user to be authenticated by default
* cookies are HTTPS-only and disallowed for Javascript requests
* the static resource handler disables directory listing and access to hidden files/dirs
* CORS origin and allowed request methods can be configured

Serverx also strives to be as fast and scalable as possible by:

* building on the asynchronous multithreaded core of Vert.x
* using internal compilation and caching of HTML templates
* using `MethodHandle` to perform required reflective access checks (for template models) at server startup time rather than at tempate rendering time

Serverx is a new project, and is functional, but has not yet been tested widely.

## Setting up routes

When you start the Serverx server, it will scan the classes in your project (using [ClassGraph](https://github.com/classgraph/classgraph))
to find route handlers, start a Vertx server, and automatically add the discovered routes.

Route handlers should be annotated with
[`serverx.route.Route`](https://github.com/lukehutch/serverx/blob/master/src/main/java/serverx/route/Route.java),
and should implement [`RouteHandler<T>`](https://github.com/lukehutch/serverx/blob/master/src/main/java/serverx/route/RouteHandler.java)
for some *response object type* `T`. The second parameter of the `handle` method will have type `Future<T>`. The value that
this `Future` is completed with will be referred to below as the *response object*. 

### Serving `text/plain` responses

You can serve the string `Hello world` with MIME type `text/plain` from the URL path `/hello/world` as follows:

```java
@Route(path = "/hello/world", requireLogin = false, responseType = ResponseType.STRING)
public class HelloWorld implements RouteHandler<String> {
    @Override
    public void handle(final RoutingContext ctx, final Future<String> response) {
        response.complete("Hello world");
    }
}

```

Things to observe:

* The `Future` is completed with an object of type `String`, which is also the parameter type of the `RouteHandler`.
* If you do not specify a `path` parameter, the handler will match all request paths.
* If you do not specify a `method` or `additionalMethods` parameter (e.g. `POST`), the handler will default to only handling `GET` requests. 
* `requireLogin = true` requires the user to [authenticate](#authentication) to view the route. This is the default, for security reasons, so if you do not need the user to be authenticated, you need to specify `requireLogin = false`.

If `responseType = ResponseType.STRING` is set in the annotation, then the `toString()` method is called on the response object. In other words, the response object type does not have to be `String`. For example, the following handler will respond with the text `[x, y, z]`, since that is the value returned by `List::toString` for the returned list: 

```java
@Route(path = "/xyz", requireLogin = false, responseType = ResponseType.STRING)
public class XYZ implements RouteHandler<List<String>> {
    @Override
    public void handle(final RoutingContext ctx, final Future<List<String>> response) {
        response.complete(Arrays.asList("x", "y", "z"));
    }
}

```

If the value of the response object is `null`, the empty string will be returned in the HTTP response.

### Serving `application/json` responses

Instead of responding with a string and MIME type `text/plain`, you can serve any Java object as JSON by simply changing
the `responseType` parameter of the annotation to `ResponseType.JSON`:

```java
@Route(path = "/widget", requireLogin = false, responseType = ResponseType.JSON)
public class WidgetHandler implements RouteHandler<Widget> {
    @Override
    public void handle(final RoutingContext ctx, final Future<Widget> response) {
        response.complete(new Widget("Fancy Widget", 100, Currency.DOLLARS));
    }
}

```

### Serving `text/html` responses

#### Rendering HTML templates 

You can render any Java object into HTML by simply changing the `responseType` parameter of the annotation to
`ResponseType.HTML`, and then ensuring that the response object type is an implementation of `TemplateModel`:

```java
@Route(path = "/datetoday.html", requireLogin = false, responseType = ResponseType.HTML)
public class DateHandler implements RouteHandler<DateToday> {
    @Override
    public void handle(final RoutingContext ctx, final Future<DateToday> response) {
        var date = new SimpleDateFormat("yyyy-MM-dd").format(Calendar.getInstance().getTime());
        response.complete(new DateToday(date));
    }
}
```

Note that `responseType = ResponseType.HTML` is the default, so this can be omitted.

The response object must implement `TemplateModel`, so that its templates can be found by classpath scanning
on startup. A `TemplateModel` implementation that contains a field of a given name (e.g. `date`) will be rendered
into HTML by substituting the field into a parameter with the same name as the field, surrounded by double
curly braces `{{...}}` (e.g. `{{date}}`):

```java
package datetoday;

import serverx.template.TemplateModel;

public class DateToday implements TemplateModel {
    public String date;
    public static final String _template = "<b>Today's date is:</b> {{date}}";
    
    public DateToday(String date) {
        this.date = date;
    }
}    
```

Fields of a `TemplateModel` are generally converted to strings and then HTML-escaped 
(using OWASP-compliant escaping, and using appropriate escaping for both text and HTML attributes), 
before being inserted into the HTML template. (The exception is if a field's type is itself `TemplateModel`,
which causes that field to [itself be rendered as a template](#rendering-nested-html-templates).)

A `TemplateModel` class should generally have a default HTML template, which can be specified either by including
a field `public static final String _template` in the `TemplateModel` implementation itself, or by adding a file
with the extension `.html` and the same name as the `TemplateModel` implementation in the same package
(i.e. `datetoday/DateToday.html` for the class `datetoday.DateToday`). 

If the value of any field (e.g. `x`) is `null`, any use of the corresponding template parameter (e.g. `{{x}}`)
will be replaced with the empty string (i.e. to not include a template parameter, set the corresponding field to
null).

You can override the default HTML template for any `TemplateModel` by specifying `htmlTemplatePath` in the `Route`
annotation:

```java
@Route(path = "/mobile/weatherforecast.html", requireLogin = false,
       // Override HTML template with mobile-friendly template for mobile route path:
       htmlTemplatePath = "/templates/mobile/weather.html")
public class WeatherHandler implements RouteHandler<Weather> {
    @Override
    public void handle(final RoutingContext ctx, final Future<Weather> response) {
        // Get weather asynchonously, then complete the response 
        WeatherForecast.getWeather(response);
    }
}
```

If a `TemplateModel` has no default HTML template, you will need to manually specify an `htmlTemplatePath`
to use for each `Route`.

#### Rendering nested HTML templates 

When using `responseType = ResponseType.HTML`, if the response object, which must be a `TemplateModel`, has fields
of type `TemplateModel`, they will be recursively rendered into HTML fragments, and inserted at the corresponding
template parameter position as normal.

```java
public class OuterWidget implements TemplateModel {
    public String name;
    public InnerWidget innerWidget;
    public static final String _template = "<b>{{name}}</b>: {{innerWidget}}";
}

public class InnerWidget implements TemplateModel {
    public int count;
    public static final String _template = "current count: {{count}}";
}
```

(The rendering of nested `TemplateModel` instances can currently only use the default template for the `TemplateModel`
implementing class. This may be changed in future by extending the syntax of the template parameters, e.g.
using `{{paramName template="/path/to/override-template.html"}}`, but this is not currently supported.)

#### Rendering complete HTML pages

Note that all the HTML template examples given so far render only an HTML fragment, not a complete page.
An HTML template can contain a complete HTML page, but usually all or most pages on a site need to use the
same surrounding page content, and only the `<title>` and `<body>` elements need to change on a page-by-page
basis.

Consequently, if a `TemplateModel` implementation contains a field `public String _title`, after the `TemplateModel`
has been rendered into an HTML fragment, the value of the `_title` field is inserted into the `{{_title}}` parameter
of a *page template*, and the value of the rendered HTML fragment is inserted into the `{{_body}}` parameter of
the page template.

The [default page template](https://github.com/lukehutch/serverx/blob/master/src/main/java/serverx/model/HTMLPageModel.html)
is used unless it is overridden by specifying an override path of the form `htmlPageTemplatePath = "/page-template-override-path.html"`
in the `Route` annotation of the route handler:

```java
@Route(path = "/info.html", htmlPageTemplatePath = "/templates/info.html")
public class InfoHandler implements RouteHandler<InfoModel> {
    @Override
    public void handle(RoutingContext ctx, Future<InfoModel> response) {
        Info.getInfoModel(ctx, response);
    }
}
```

You can also override the default page HTML template by specifying `defaultPageHTMLTemplate=/path/to/page-template.html`
in the `server.properties` file. 

The default page template includes [UIKit](https://getuikit.com/), [SennaJS](https://sennajs.com/), and [jQuery](https://jquery.com/).

### Custom handlers

You can register a standard Vert.x `Handler<RoutingContext>` rather than a `RouteHandler<T>` if you want to handle
a route yourself (e.g. if you want to use your own HTML template engine to render content, send a custom
status code, or respond with a custom MIME type):

```java
@Route(path = "/price.html", requireLogin = false)
public class Price implements Handler<RoutingContext> {
    @Override
    public void handle(RoutingContext ctx) {
        ctx.response().putHeader("content-type", "text/plain; charset=utf-8")
                      .end("Current price: $100");
    }
}
```

### Failure handlers

You can register a failure handler by adding `isFailureHandler = true` to the `Route` annotation. This handler will
take over in the case of exceptions (with will give `ctx.statusCode()` equal to `5xx` or `-1`), page not found (`404`),
etc.  You can filter for a single specific status code by adding `failureStatusCode = <statusCodeInt>` to the `Route`
annotation.

### Blocking handlers

You can register a blocking handler by adding `isBlockingHandler = true` to the `Route` annotation. This handler will
run in a separate thread pool from normal request handlers, and the handler is allowed to block. Blocking handlers
should be used only sparingly.

### SockJS handlers

You can register a standard Vert.x `Handler<SockJSSocket>` rather than a `RouteHandler<T>` if you want to set up
a SockJS connection.

*(Note that this may work, but is not yet tested -- testing and pull requests are welcome.)*

```java
@Route(path = "/socket")
public class SockJSHandler implements Handler<SockJSSocket> {
    @Override
    public void handle(SockJSSocket socket) {
        /* ... */
    }
}
```

You probably should not use `requireLogin = false` with socket mountpoints (`/socket` in the above example),
to reduce the likelihood of DoS attacks (i.e. only offer WebSocket connections to logged-in users).

## Authentication

By omitting `requireLogin = false` (i.e. using the default of `requireLogin = true`), a route will require
a user to be authenticated before the route can be accessed, and the user will be automatically directed to
an OAuth2 login page. Currently only Google OAuth2 is supported, but other login types will be supported in
future (pull requests welcome).

```java
@Route(path = "/profile.html")
public class ProfileHandler implements RouteHandler<ProfileModel> {
    @Override
    public void handle(RoutingContext ctx, Future<ProfileModel> response) {
        Profile.getProfileModel(ctx, response);
    }
}
```

In addition to `ctx.user()` being available for logged-in users on authenticated routes (in particular providing
`ctx.user().principal()` for making OAuth2-authenticated remote API calls), the user's email address
is available as `(String) ctx.session().get("email")`, and the OpenID Connect user info (the user's name,
profile picture URL, etc.) is available as `(JsonObject) ctx.session().get("user_info")`.


## Authorization

Role-based access control (RBAC) is supported on routes via the `permissions = { }` list parameter of a `Route`
annotation. Listing a permission also implicitly requires the user to be authenticated (logged in). 

```java
@Route(path = "/settings.html", permissions = { "role:manager" })
public class Settings implements RouteHandler<SettingsModel> {
    @Override
    public void handle(RoutingContext ctx, Future<SettingsModel> settingsResponse) {
        Settings.getSettings(ctx, settingsResponse);
    }
}
```

Permissions are strings, and should be prefixed with something meaningful to avoid namespace
collisions, e.g. a prefix of `role:` could be used for roles. A user must have all listed permissions on a
route before they have access to the route.

The permissions are stored in the `permissions` collection of
the MongoDB database, with the `_id` field of a record set to the email address of the user, and the
`permissions` field of the same record set to a JSON object mapping from permission name to a Boolean value
indicating whether the user has the permission. (If a user's email address is absent, or a user's email
address is present but a named permission is absent, the user is assumed not to have the permission.)

Some methods for adding or revoking a permission in the `permissions` table are contained in the
[`Permission`](https://github.com/lukehutch/serverx/blob/master/src/main/java/serverx/utils/Permission.java)
utility class.  

## Other `Route` configuration options

The `Route` annotation supports many other configuration options (e.g. changing the HTTP request method to `POST`) --
[check the Javadoc](https://github.com/lukehutch/serverx/blob/master/src/main/java/serverx/route/Route.java) for details.

## Database access

Serverx requires a running MongoDB instance. You can access this database using the static field
`ServerxVerticle.mongoClient`. MongoDB should be accessed asynchronously using callbacks.

```java
@Route(path = "/settings.html", permissions = { "role:manager" })
public class SiteSettingsHandler implements RouteHandler<SiteSettingsModel> {
    @Override
    public void handle(RoutingContext ctx, Future<SiteSettingsModel> response) {
        ServerxVerticle.mongoClient.find(
                /* collection = */ "settings",
                /* query = */ new JsonObject().put("_id", "siteSettings"),
                result -> {
                    if (result.succeeded()) {
                        JsonObject siteSettingsJson = result.result().get(0);
                        SiteSettingsModel siteSettingsModel = 
                                siteSettingsJson.mapTo(SiteSettingsModel.class);
                        response.complete(siteSettingsModel);
                    } else {
                        response.fail(result.cause());
                    }
                });
    }
}
```

The database name and connection string are specified in the [server configuration file](#configuring-the-server). 

## Configuring the server

You should add a file `server.properties` to the root of your project, containing the server configuration.
The names of the properties in this file should match the fields of the
[`ServerProperties`](https://github.com/lukehutch/serverx/blob/master/src/main/java/serverx/server/ServerProperties.java)
class, which is also where the default values can be found. If a value is specified in `server.properties`, it
overrides the default value in the `ServerProperties` class. If a field in `ServerProperties` has no defined default value,
then the property is required, i.e. the server won't start unless the property is set.

A typical `server.properties` file will look like the following:

```
# Host, port, and SSL parameters. If useSSL=true (the default),
# httpPort will automatically redirect requests to httpsPort 
host=mydomain.com
httpPort=8080
httpsPort=8443
#useSSL=true

# SSL certificate paths
pemCertFilePath=/etc/letsencrypt/live/mydomain.com/fullchain.pem
pemKeyFilePath=/etc/letsencrypt/live/mydomain.com/privkey.pem

# Packages to scan for route handlers and TemplateModels
handlerPackage=com.mydomain.routehandler
templateModelPackage=com.mydomain.templatemodel

# The MongoDB database name
dbName=mydb

# The file containing `clientId` and `clientSecret` properties for Google OAuth2
# (this is a filesystem path, not a resource path)
googleSecretProperties=/home/user/mydomain-config/google_secret.properties

# The default page HTML template, if you want to override the default
# (this is a resource path, not a filesystem path)
defaultPageHTMLTemplate=/templates/page-template.html

# Whether or not to indent (prettyprint) HTML and/or JSON in the response.
# HTML template rendering and JSON serialization is faster (and the response
# is smaller) if not indented.
indentHTML=false
indentJSON=true
```

## Starting the server

```java
public class MyMainClass {
    public static void main(final String[] args) throws IOException {
        Vertx.vertx().deployVerticle(new ServerxVerticle());
    }
}
```

or the following (you will need the Serverx jar and [its dependencies](https://github.com/lukehutch/serverx/blob/master/pom.xml)
on the classpath, as well as your project jar): 

```bash
java io.vertx.core.Starter run serverx.server.ServerxVerticle
```


## Author

Serverx was written by Luke Hutchison ([@LH](http://twitter.com/LH) on Twitter).

## License

**The MIT License (MIT)**

**Copyright (c) 2019 Luke Hutchison**
 
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
