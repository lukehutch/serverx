package serverx.template;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import serverx.utils.StringUtils;
import serverx.utils.WebUtils;

/**
 * HTMLTemplateLoader.
 */
class HTMLTemplateLoader {
    /**
     * If true, require all template params in URL-typed HTML attributes to be bound to fields of type java.net.URI,
     * and disallow any content in the attribute value other than the parameter string. This is to prevent injection
     * attacks with badly-formed URLs, as recommended by OWASP.
     */
    public static boolean STRICT_URI_ATTRS = false;

    // -------------------------------------------------------------------------------------------------------------

    /** Pattern for template parameters, of the form "{{name}}". */
    public static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The Class TemplatePart.
     */
    static abstract class TemplatePart {

        /** The param name. */
        final String paramName;

        /**
         * Instantiates a new template part.
         *
         * @param paramName
         *            the param name
         */
        public TemplatePart(final String paramName) {
            this.paramName = paramName;
        }

        /**
         * Render to string.
         *
         * @param templateModel
         *            the template model
         * @param indent
         *            the indent
         * @param renderIndentLevel
         *            the render indent level
         * @param renderBuf
         *            the render buf
         */
        public abstract void renderToString(TemplateModel templateModel, boolean indent, int renderIndentLevel,
                StringBuilder renderBuf);

        /**
         * Finish init.
         */
        public void finishInit() {
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The Class RawHTMLTemplatePart.
     */
    private static class RawHTMLTemplatePart extends TemplatePart {

        /** The raw HTML. */
        private String rawHTML;

        /**
         * Instantiates a new raw HTML template part.
         *
         * @param rawHTML
         *            the raw HTML
         */
        public RawHTMLTemplatePart(final String rawHTML) {
            super(/* no param name */ "");
            this.rawHTML = rawHTML;
        }

        /* (non-Javadoc)
         * @see serverx.template.HTMLTemplateLoader.TemplatePart#renderToString(serverx.template.TemplateModel, boolean, int, java.lang.StringBuilder)
         */
        @Override
        public void renderToString(final TemplateModel ignored, final boolean indent, final int renderIndentLevel,
                final StringBuilder renderBuf) {
            StringUtils.appendUnescaped(rawHTML, indent, renderIndentLevel, renderBuf);
        }
    }

    /**
     * Snapshot any string content in buf, and write a TemplatePart that copies it as a string directly to the
     * output render buffer.
     *
     * @param buf
     *            the buf
     * @param templateParts
     *            the template parts
     */
    private static void flushBufToTemplatePart(final StringBuilder buf, final List<TemplatePart> templateParts) {
        if (buf.length() > 0) {
            final String bufStr = buf.toString();
            buf.setLength(0);
            TemplatePart prevTemplate = null;
            if (templateParts.size() > 0 //
                    && ((prevTemplate = templateParts.get(templateParts.size() - 1)) //
                    instanceof RawHTMLTemplatePart)) {
                // Concatenate any adjacent RawHTMLTemplatePart strings for efficiency
                ((RawHTMLTemplatePart) prevTemplate).rawHTML += bufStr;
            } else {
                templateParts.add(new RawHTMLTemplatePart(bufStr));
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The Class NestedHTMLTemplatePart.
     */
    private static class NestedHTMLTemplatePart extends TemplatePart {

        /** The field type template model. */
        private final Class<TemplateModel> fieldTypeTemplateModel;

        /** The field default html template. */
        private HTMLTemplate fieldDefaultHtmlTemplate;

        /** The field getter method handle. */
        private final MethodHandle fieldGetterMethodHandle;

        /** The indent level. */
        private final int indentLevel;

        /** The template model class to HTML template. */
        private final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate;

        /**
         * Instantiates a new nested HTML template part.
         *
         * @param fieldTypeTemplateModel
         *            the field type template model
         * @param fieldName
         *            the field name
         * @param fieldGetterMethodHandle
         *            the field getter method handle
         * @param indentLevel
         *            the indent level
         * @param templateModelClassToHTMLTemplate
         *            the template model class to HTML template
         */
        public NestedHTMLTemplatePart(final Class<TemplateModel> fieldTypeTemplateModel, final String fieldName,
                final MethodHandle fieldGetterMethodHandle, final int indentLevel,
                final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate) {
            super(/* paramName = */ fieldName);
            this.fieldTypeTemplateModel = fieldTypeTemplateModel;
            this.fieldGetterMethodHandle = fieldGetterMethodHandle;
            this.indentLevel = indentLevel;
            this.templateModelClassToHTMLTemplate = templateModelClassToHTMLTemplate;
        }

        /* (non-Javadoc)
         * @see serverx.template.HTMLTemplateLoader.TemplatePart#finishInit()
         */
        @Override
        public void finishInit() {
            // Once all templates have loaded, look up the default template for this nested TemplateModel field
            fieldDefaultHtmlTemplate = templateModelClassToHTMLTemplate.get(fieldTypeTemplateModel);
            if (fieldDefaultHtmlTemplate == null) {
                throw new RuntimeException("Cannot render nested " + TemplateModel.class.getSimpleName()
                        + ", since it does not have a registered " + HTMLTemplate.class.getSimpleName() + ": "
                        + fieldTypeTemplateModel.getName());
            }
            if (!fieldDefaultHtmlTemplate.hasTemplateForPath("")) {
                throw new RuntimeException("Cannot render nested " + TemplateModel.class.getSimpleName()
                        + ", since it does not have a default " + HTMLTemplate.class.getSimpleName() + ": "
                        + fieldTypeTemplateModel.getName());
            }
        }

        /* (non-Javadoc)
         * @see serverx.template.HTMLTemplateLoader.TemplatePart#renderToString(serverx.template.TemplateModel, boolean, int, java.lang.StringBuilder)
         */
        @Override
        public void renderToString(final TemplateModel templateModel, final boolean indent,
                final int renderIndentLevel, final StringBuilder renderBuf) {
            try {
                final var fieldVal = fieldGetterMethodHandle.invoke(templateModel);
                if (fieldVal != null) {
                    if (!(fieldVal instanceof TemplateModel)) {
                        throw new RuntimeException("Expected field to be of type " + TemplateModel.class.getName()
                                + ", but got " + fieldVal.getClass().getName());
                    }
                    // Nested templates must default to their default HTML template, since there is
                    // no way to specify the template type for a nested template
                    fieldDefaultHtmlTemplate.renderFragment((TemplateModel) fieldVal, /* use default template */ "",
                            indent, indentLevel + renderIndentLevel, renderBuf);
                }
            } catch (final Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The Class CustomEscapedTemplatePart.
     */
    private static abstract class CustomEscapedTemplatePart extends TemplatePart {

        /** The field getter method handle. */
        private final MethodHandle fieldGetterMethodHandle;

        /**
         * Instantiates a new custom escaped template part.
         *
         * @param fieldName
         *            the field name
         * @param fieldGetterMethodHandle
         *            the field getter method handle
         */
        public CustomEscapedTemplatePart(final String fieldName, final MethodHandle fieldGetterMethodHandle) {
            super(/* paramName = */ fieldName);
            this.fieldGetterMethodHandle = fieldGetterMethodHandle;
        }

        /* (non-Javadoc)
         * @see serverx.template.HTMLTemplateLoader.TemplatePart#renderToString(serverx.template.TemplateModel, boolean, int, java.lang.StringBuilder)
         */
        @Override
        public void renderToString(final TemplateModel templateModel, final boolean indent,
                final int renderIndentLevel, final StringBuilder renderBuf) {
            try {
                final var fieldVal = fieldGetterMethodHandle.invoke(templateModel);
                if (fieldVal != null) {
                    renderToString(fieldVal, indent, renderIndentLevel, renderBuf);
                }
            } catch (final Throwable e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Render to string.
         *
         * @param fieldVal
         *            the field val
         * @param indent
         *            the indent
         * @param renderIndentLevel
         *            the render indent level
         * @param renderBuf
         *            the render buf
         */
        public abstract void renderToString(Object fieldVal, final boolean indent, final int renderIndentLevel,
                final StringBuilder renderBuf);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The Class NewlineIndentTemplatePart.
     */
    private static class NewlineIndentTemplatePart extends TemplatePart {

        /** The indent level. */
        private final int indentLevel;

        /**
         * Instantiates a new newline indent template part.
         *
         * @param indentLevel
         *            the indent level
         */
        public NewlineIndentTemplatePart(final int indentLevel) {
            super(/* no param name */ "");
            this.indentLevel = indentLevel;
        }

        /* (non-Javadoc)
         * @see serverx.template.HTMLTemplateLoader.TemplatePart#renderToString(serverx.template.TemplateModel, boolean, int, java.lang.StringBuilder)
         */
        @Override
        public void renderToString(final TemplateModel ignored, final boolean indent, final int renderIndentLevel,
                final StringBuilder renderBuf) {
            if (renderBuf.length() > 0) {
                renderBuf.append('\n');
            }
            StringUtils.appendSpaces(indentLevel + renderIndentLevel, renderBuf);
        }
    }

    /**
     * Adds the newline indent.
     *
     * @param addIndent
     *            the add indent
     * @param indentLevel
     *            the indent level
     * @param buf
     *            the buf
     * @param templateParts
     *            the template parts
     */
    private static void addNewlineIndent(final boolean addIndent, final int indentLevel, final StringBuilder buf,
            final List<TemplatePart> templateParts) {
        if (addIndent) {
            flushBufToTemplatePart(buf, templateParts);
            if (templateParts.size() > 0
                    && (templateParts.get(templateParts.size() - 1) instanceof NewlineIndentTemplatePart)) {
                // If there are two NewlineAndIndent parts in a row, replace previous one with this one,
                // so that we don't create blank lines
                templateParts.remove(templateParts.size() - 1);
            }
            templateParts.add(new NewlineIndentTemplatePart(indentLevel));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The Enum EscapingType.
     */
    private static enum EscapingType {

        /** The text. */
        TEXT,
        /** The attr. */
        ATTR,
        /** The uri attr. */
        URI_ATTR;
    };

    /**
     * Prerender text.
     *
     * @param text
     *            the text
     * @param escapingType
     *            the escaping type
     * @param indent
     *            the indent
     * @param indentLevel
     *            the indent level
     * @param templateParts
     *            the template parts
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     * @param fieldNameToMethodHandle
     *            the field name to method handle
     * @param buf
     *            the buf
     */
    private static void prerenderText(final String text, final EscapingType escapingType, final boolean indent,
            final int indentLevel, final List<TemplatePart> templateParts,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate,
            final Map<String, MethodHandle> fieldNameToMethodHandle, final StringBuilder buf) {
        final boolean isURIAttr = escapingType == EscapingType.URI_ATTR;
        int prevEnd = 0;
        final Matcher matcher = TEMPLATE_PARAM_PATTERN.matcher(text);
        while (matcher.find()) {
            if (matcher.start() - prevEnd > 0) {
                final String initialText = text.substring(prevEnd, matcher.start());
                if (STRICT_URI_ATTRS && isURIAttr) {
                    throw new IllegalArgumentException(
                            "HTML attributes that expect a URL may not contain anything other than "
                                    + "URI-typed parameters, but also got: " + initialText);
                }
                // Write string before template parameter to buf
                StringUtils.appendUnescaped(indent ? initialText.replaceAll("[ ]+", " ") : initialText, indent,
                        indentLevel, buf);
            }
            // Write any string part before param as a TemplatePart 
            flushBufToTemplatePart(buf, templateParts);
            // Get param name
            final String paramName = matcher.group(1);
            // Get field of same name in template model
            final var methodHandle = fieldNameToMethodHandle.get(paramName);
            if (methodHandle == null) {
                throw new IllegalArgumentException(
                        "Template contains parameter that does not match any public field in the corresponding "
                                + TemplateModel.class.getSimpleName() + " class: " + paramName);
            }
            final var fieldType = methodHandle.type().returnType();
            final var isURITypedField = URI.class.isAssignableFrom(fieldType);
            if (STRICT_URI_ATTRS && isURIAttr != isURITypedField) {
                throw new IllegalArgumentException(
                        "URL-typed HTML attributes must take parameters of type java.net.URI (for parameter \""
                                + paramName + "\")");
            }

            if (TemplateModel.class.isAssignableFrom(fieldType)) {
                // HTMLTemplate parameters can't be used inside attributes
                if (escapingType != EscapingType.TEXT) {
                    throw new IllegalArgumentException("Parameter " + paramName
                            + " is inside an HTML attribute, but the corresponding field is of type "
                            + TemplateModel.class.getSimpleName());
                }
                // Recursively expand template within template, using the default template for the TemplateModel
                @SuppressWarnings("unchecked")
                final var fieldTypeTemplateModel = (Class<TemplateModel>) fieldType;
                templateParts.add(new NestedHTMLTemplatePart(fieldTypeTemplateModel, paramName, methodHandle,
                        indentLevel, templateModelClassToHTMLTemplate));
            } else if (isURITypedField) {
                templateParts.add(new CustomEscapedTemplatePart(paramName, methodHandle) {
                    @Override
                    public void renderToString(final Object fieldVal, final boolean renderIndent,
                            final int renderIndentLevel, final StringBuilder renderBuf) {
                        // URIs will already be escaped
                        // TODO: is any escaping needed? Or is different escaping needed for HTML attributes?
                        StringUtils.appendUnescaped(fieldVal.toString(), renderIndent, renderIndentLevel,
                                renderBuf);
                    }
                });
            } else if (fieldType == int.class || fieldType == long.class || fieldType == short.class
                    || fieldType == float.class || fieldType == double.class || fieldType == boolean.class) {
                templateParts.add(new CustomEscapedTemplatePart(paramName, methodHandle) {
                    @Override
                    public void renderToString(final Object fieldVal, final boolean renderIndent,
                            final int renderIndentLevel, final StringBuilder renderBuf) {
                        // Most primitives don't need to be escaped
                        StringUtils.appendUnescaped(fieldVal.toString(), renderIndent, renderIndentLevel,
                                renderBuf);
                    }
                });
            } else if (fieldType == char.class) {
                templateParts.add(new CustomEscapedTemplatePart(paramName, methodHandle) {
                    @Override
                    public void renderToString(final Object fieldVal, final boolean renderIndent,
                            final int renderIndentLevel, final StringBuilder renderBuf) {
                        // Characters must be escaped
                        StringUtils.appendEscaped(fieldVal.toString(),
                                /* forAttrVal = */ escapingType != EscapingType.TEXT, renderIndent,
                                indentLevel + renderIndentLevel, renderBuf);
                    }
                });
            } else {
                // Otherwise check if the toString() method has been defined
                Method toString = null;
                try {
                    toString = fieldType.getMethod("toString");
                } catch (NoSuchMethodException | SecurityException e) {
                    // No toString()
                }
                if (toString == null || toString.getDeclaringClass() == Object.class) {
                    throw new IllegalArgumentException("Field \"" + paramName + "\" has type " + fieldType
                            + ", which does not override toString(), but the class also does not implement "
                            + TemplateModel.class.getName() + ", so it cannot be rendered using an HTML template");
                } else {
                    // Render the object to a string, and escape the string
                    templateParts.add(new CustomEscapedTemplatePart(paramName, methodHandle) {
                        @Override
                        public void renderToString(final Object fieldVal, final boolean renderIndent,
                                final int renderIndentLevel, final StringBuilder renderBuf) {
                            // Characters must be escaped
                            StringUtils.appendEscaped(fieldVal.toString(),
                                    /* forAttrVal = */ escapingType != EscapingType.TEXT, renderIndent,
                                    indentLevel + renderIndentLevel, renderBuf);
                        }
                    });
                }
            }
            prevEnd = matcher.end();
        }
        if (prevEnd < text.length()) {
            // Flush last text chunk
            final String lastText = text.substring(prevEnd, text.length());
            if (STRICT_URI_ATTRS && isURIAttr) {
                throw new IllegalArgumentException(
                        "HTML attributes that expect a URL may not contain anything other than "
                                + "URI-typed parameters, but also got: " + lastText);
            }
            StringUtils.appendUnescaped(indent ? lastText.replaceAll("[ ]+", " ") : lastText, indent, indentLevel,
                    buf);
            flushBufToTemplatePart(buf, templateParts);
        }
    }

    /**
     * Pre render.
     *
     * @param fieldNameToMethodHandle
     *            the field name to method handle
     * @param node
     *            the node
     * @param indent
     *            the indent
     * @param indentLevel
     *            the indent level
     * @param buf
     *            the buf
     * @param templateParts
     *            the template parts
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     */
    private static void preRender(final Map<String, MethodHandle> fieldNameToMethodHandle, final Node node,
            final boolean indent, final int indentLevel, final StringBuilder buf,
            final List<TemplatePart> templateParts,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate) {
        if (node instanceof Comment) {
            // Remove all comments that do not start with "[if " or " [if " (for IE) or "@license"
            final String commentStr = ((Comment) node).getData();
            if (commentStr.startsWith("[if ") || commentStr.startsWith(" [if ")
                    || commentStr.contains("@license")) {
                buf.append(node.toString());
            }
        } else if (node instanceof TextNode) {
            final String text = ((TextNode) node).text();
            // In case of mal-formed element names, Jsoup inserts a text node with the unescaped text 
            if (text.indexOf('<') > 0 || text.indexOf('>') > 0) {
                throw new IllegalArgumentException("Template contains '<' or '>' character in unexpected position");
            }
            prerenderText(text, EscapingType.TEXT, indent, indentLevel, templateParts,
                    templateModelClassToHTMLTemplate, fieldNameToMethodHandle, buf);

        } else if (node instanceof Element) {
            final Element elt = (Element) node;
            final String tagName = elt.tagName();
            final boolean isPara = tagName.equals("p");
            final boolean isInline = WebUtils.INLINE_ELEMENTS.contains(tagName);
            final boolean isVoidElt = WebUtils.VOID_ELEMENTS.contains(tagName);

            // Render open tag and attributes
            addNewlineIndent(indent && !isInline, indentLevel, buf, templateParts);
            buf.append('<');
            buf.append(tagName);
            for (final Attribute attr : elt.attributes()) {
                buf.append(' ');
                final String attrName = attr.getKey();
                buf.append(attrName);
                final String attrVal = attr.getValue();
                if (attrVal != null) {
                    buf.append("=\"");
                    prerenderText(attrVal,
                            WebUtils.isURLAttr(tagName, attrName) ? EscapingType.URI_ATTR : EscapingType.ATTR,
                            indent, indentLevel, templateParts, templateModelClassToHTMLTemplate,
                            fieldNameToMethodHandle, buf);
                    buf.append('"');
                }
            }
            buf.append('>');

            // Recursively render child nodes
            final List<Node> children = elt.childNodes();
            if (!children.isEmpty()) {
                if (isVoidElt) {
                    throw new IllegalArgumentException("Tag " + tagName + " is a void element, but has children");
                }
                for (final Node child : children) {
                    addNewlineIndent(indent && !isInline && !isPara, indentLevel + 1, buf, templateParts);
                    preRender(fieldNameToMethodHandle, child, indent, indentLevel + 1, buf, templateParts,
                            templateModelClassToHTMLTemplate);
                }
            }

            // Render close tag
            addNewlineIndent(indent && !isInline && !isPara, indentLevel, buf, templateParts);
            if (!isVoidElt) {
                buf.append("</" + tagName + ">");
            }
            addNewlineIndent(indent && !isInline, indentLevel, buf, templateParts);

        } else {
            // DocumentType, DataNode, XmlDeclaration
            final String nodeStr = node.toString();
            StringUtils.appendUnescaped(nodeStr, indent, indentLevel, buf);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parses the template.
     *
     * @param templateStr
     *            the template str
     * @param fieldNameToMethodHandle
     *            the field name to method handle
     * @param indent
     *            the indent
     * @param templateModelClassToHTMLTemplate
     *            the template model class to HTML template
     * @return the list
     */
    static List<TemplatePart> parseTemplate(final String templateStr,
            final Map<String, MethodHandle> fieldNameToMethodHandle, final boolean indent,
            final Map<Class<? extends TemplateModel>, HTMLTemplate> templateModelClassToHTMLTemplate) {
        // See if this is a whole-page HTML document, as opposed to an HTML fragment
        final var firstTagIdx = templateStr.indexOf("<");
        final var isWholeDocument = firstTagIdx >= 0 && ((templateStr.length() >= 5
                && templateStr.substring(firstTagIdx, firstTagIdx + 5).toLowerCase().equals("<html")) //
                || (templateStr.length() >= 9
                        && templateStr.substring(firstTagIdx, firstTagIdx + 9).toLowerCase().equals("<!doctype")));

        // Parse the HTML -- whole-page templates need Jsoup.parse(), fragments need Jsoup.parseBodyFragment()
        final List<Node> nodes = isWholeDocument ? Jsoup.parse(templateStr).childNodes()
                : Jsoup.parseBodyFragment(templateStr).body().childNodes();

        // Turn the list of Nodes into TemplateParts
        final var templateParts = new ArrayList<TemplatePart>();
        final var buf = new StringBuilder();
        for (final var node : nodes) {
            preRender(fieldNameToMethodHandle, node, indent, 0, buf, templateParts,
                    templateModelClassToHTMLTemplate);
        }
        flushBufToTemplatePart(buf, templateParts);

        // Remove any trailing indent
        if (templateParts.size() > 0
                && (templateParts.get(templateParts.size() - 1) instanceof NewlineIndentTemplatePart)) {
            templateParts.remove(templateParts.size() - 1);
        }

        return templateParts;
    }
}
