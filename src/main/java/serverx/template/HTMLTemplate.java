package serverx.template;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import serverx.model.HTMLPageModel;
import serverx.route.Route;
import serverx.server.ServerxVerticle;
import serverx.template.HTMLTemplateLoader.TemplatePart;
import serverx.utils.ReflectionUtils;
import serverx.utils.StringUtils;

/**
 * HTMLTemplate.
 */
public class HTMLTemplate {
    /** The field name to method handle. */
    private final Map<String, MethodHandle> fieldNameToMethodHandle;

    /** The template path to template parts. */
    private final Map<String, List<TemplatePart>> templatePathToTemplateParts = new HashMap<>();

    /** The title method handle. */
    private final MethodHandle titleMethodHandle;

    /** The default template parts. */
    private List<TemplatePart> defaultTemplateParts;

    /** The Constant TEMPLATE_FIELD_NAME. */
    private static final String TEMPLATE_FIELD_NAME = "_template";

    /** The Constant TITLE_FIELD_NAME. */
    private static final String TITLE_FIELD_NAME = "_title";

    /**
     * Constructor.
     *
     * @param templateModelClass
     *            the template model class
     */
    public HTMLTemplate(final Class<? extends TemplateModel> templateModelClass) {
        this.fieldNameToMethodHandle = ReflectionUtils.getFieldNameToMethodHandle(templateModelClass);
        this.titleMethodHandle = fieldNameToMethodHandle.get(TITLE_FIELD_NAME);
        if (titleMethodHandle != null) {
            final var titleFieldType = titleMethodHandle.type().returnType();
            if (titleFieldType != String.class) {
                throw new IllegalArgumentException(
                        "Template model has a " + TITLE_FIELD_NAME + " field, but it has the type " + titleFieldType
                                + " rather than " + String.class.getName());
            }
        }
    }

    /**
     * Checks if is page template.
     *
     * @return true, if is page template
     */
    private boolean isPageTemplate() {
        return titleMethodHandle != null;
    }

    /**
     * Adds the template for path.
     *
     * @param templatePath
     *            the template path
     * @param templateStr
     *            the template str
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     */
    public void addTemplateForPath(final String templatePath, final String templateStr,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate) {
        final List<TemplatePart> templateParts = HTMLTemplateLoader.parseTemplate(templateStr,
                fieldNameToMethodHandle, ServerxVerticle.serverProperties.indentHTML,
                templateModelClassToHTMLTemplate);
        if (templatePath.isEmpty()) {
            defaultTemplateParts = templateParts;
        } else {
            templatePathToTemplateParts.put(templatePath, templateParts);
        }
    }

    /**
     * Checks for template for path.
     *
     * @param templatePath
     *            the template path
     * @return true, if successful
     */
    public boolean hasTemplateForPath(final String templatePath) {
        return templatePath.isEmpty() ? defaultTemplateParts != null
                : templatePathToTemplateParts.containsKey(templatePath);
    }

    /**
     * Load template resource from path.
     *
     * @param templatePath
     *            the template path
     * @param scanResult
     *            the scan result
     * @return the string
     */
    public static String loadTemplateResourceFromPath(final String templatePath, final ScanResult scanResult) {
        final var resources = scanResult.getResourcesWithPathIgnoringWhitelist(templatePath);
        if (resources.size() > 1) {
            throw new IllegalArgumentException(
                    "Multiple HTML templates with the same path found in classpath: " + templatePath);
        } else if (resources.size() == 1) {
            try {
                return resources.get(0).getContentAsString();
            } catch (final IOException e) {
                throw new IllegalArgumentException(
                        "Could not load template " + resources.get(0).getURI() + ": " + e);
            }
        } else {
            // There are no resources with this path, so there is no default template
        }
        return null;
    }

    /**
     * Find or load default template HTML str.
     *
     * @param templateModelClassInfo
     *            the template model class info
     * @param scanResult
     *            the scan result
     * @return the string
     */
    public static String findOrLoadDefaultTemplateHTMLStr(final ClassInfo templateModelClassInfo,
            final ScanResult scanResult) {
        // Check "_template" field of TemplateModel for HTML template
        String templateStr = null;
        final var templateField = templateModelClassInfo.getFieldInfo(TEMPLATE_FIELD_NAME);
        if (templateField != null) {
            final var constantInitializerValue = templateField.getConstantInitializerValue();
            if (constantInitializerValue != null) {
                if (!(constantInitializerValue instanceof String)) {
                    throw new IllegalArgumentException(
                            "Type of field " + templateModelClassInfo.getName() + "." + TEMPLATE_FIELD_NAME
                                    + " should be String, but is " + constantInitializerValue.getClass().getName());
                }
                templateStr = (String) constantInitializerValue;
            }
        }
        if (templateStr == null) {
            // If "_template" field is not present, check for template .html file with same base name as TemplateModel
            templateStr = loadTemplateResourceFromPath(templateModelClassInfo.getName().replace('.', '/') + ".html",
                    scanResult);
        }

        ServerxVerticle.logger.log(templateStr != null ? Level.INFO : Level.WARNING,
                (templateStr != null ? "Found" : "Could not find") + " default HTML template for "
                        + TemplateModel.class.getSimpleName() + ": " + templateModelClassInfo.getName());

        return templateStr;
    }

    /**
     * Finish init.
     */
    public void finishInit() {
        for (final var ent : templatePathToTemplateParts.entrySet()) {
            for (final var templatePart : ent.getValue()) {
                templatePart.finishInit();
            }
        }
    }

    /**
     * Render fragment.
     *
     * @param templateModel
     *            the template model
     * @param templatePath
     *            the template path
     * @param indent
     *            the indent
     * @param indentLevel
     *            the indent level
     * @param renderBuf
     *            the render buf
     */
    void renderFragment(final TemplateModel templateModel, final String templatePath, final boolean indent,
            final int indentLevel, final StringBuilder renderBuf) {
        if (templateModel == null) {
            // Null values result in no output
            return;
        }
        List<TemplatePart> templateParts;
        if (templatePath.isEmpty()) {
            // Fast path for default template
            templateParts = defaultTemplateParts;
        } else {
            templateParts = templatePathToTemplateParts.get(templatePath);
        }
        if (templateParts == null) {
            if (templatePath.isEmpty()) {
                throw new RuntimeException(templateModel.getClass().getName()
                        + " does not have a default template, and no template was specified in the "
                        + Route.class.getSimpleName() + " annotation");
            } else {
                throw new RuntimeException("Unknown template: " + templatePath);
            }
        }
        for (final TemplatePart part : templateParts) {
            try {
                part.renderToString(templateModel, indent, indentLevel, renderBuf);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "Exception while rendering template for class " + templateModel.getClass().getName(), e);
            }
        }
    }

    /**
     * Render fragment.
     *
     * @param templateModel
     *            the template model
     * @param templatePath
     *            the template path
     * @param renderBuf
     *            the render buf
     */
    void renderFragment(final TemplateModel templateModel, final String templatePath,
            final StringBuilder renderBuf) {
        renderFragment(templateModel, templatePath, ServerxVerticle.serverProperties.indentHTML, 0, renderBuf);
    }

    /**
     * Render page or fragment.
     *
     * @param templateModel
     *            the template model
     * @param templatePath
     *            the template path
     * @param pageHTMLTemplate
     *            the page HTML template
     * @param pageHTMLTemplatePath
     *            the page HTML template path
     * @return the string
     */
    public String renderPageOrFragment(final TemplateModel templateModel, final String templatePath,
            final HTMLTemplate pageHTMLTemplate, final String pageHTMLTemplatePath) {
        if (!isPageTemplate() && templateModel == null) {
            // Null values result in empty output for fragments
            return "";
        }
        final StringBuilder buf = new StringBuilder();
        try {
            if (!isPageTemplate()) {
                // Render an HTML fragment
                renderFragment(templateModel, templatePath, buf);
            } else {
                // Render an HTML page, by rendering the HTML fragment into the {{_body}} attribute of page template
                List<TemplatePart> pageTemplateParts;
                if (templatePath.isEmpty()) {
                    // Fast path for default template
                    pageTemplateParts = pageHTMLTemplate.defaultTemplateParts;
                } else {
                    pageTemplateParts = pageHTMLTemplate.templatePathToTemplateParts.get(pageHTMLTemplatePath);
                }
                if (pageTemplateParts == null) {
                    if (templatePath.isEmpty()) {
                        throw new RuntimeException(HTMLPageModel.class.getName()
                                + " does not have a default template, and no HTML page template was specified in the "
                                + Route.class.getSimpleName() + " annotation");
                    } else {
                        throw new RuntimeException("Unknown page template: " + pageHTMLTemplatePath);
                    }
                }

                final var indent = ServerxVerticle.serverProperties.indentHTML;
                for (final TemplatePart pageTemplatePart : pageTemplateParts) {
                    if (pageTemplatePart.paramName.equals("_title")) {
                        // Get page title from "_title" field of templateModel, and into "_title" param of template
                        final var title = (String) titleMethodHandle.invoke(templateModel);
                        StringUtils.appendEscaped(title, /* forAttrVal = */ false, indent, /* indentLevel = */ 2,
                                buf);

                    } else if (pageTemplatePart.paramName.equals("_body")) {
                        // Render page body into "_body" parameter of template model 
                        renderFragment(templateModel, templatePath, indent, /* indentLevel = */ 2, buf);

                    } else {
                        // Render non-parameter part of page template
                        pageTemplatePart.renderToString(/* ignored */ null, indent, /* indentLevel = */ 0, buf);
                    }
                }
            }
        } catch (final Throwable e) {
            throw new IllegalArgumentException(
                    "Exception while rendering template for class " + templateModel.getClass().getName(), e);
        }
        return buf.toString();
    }
}
