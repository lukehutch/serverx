package serverx.model;

import serverx.template.TemplateModel;

/**
 * The {@link TemplateModel} for rendering HTML pages.
 */
public class HTMLPageModel implements TemplateModel {
    /** The page title. HTML templates should have a parameter of this name in the {@code <title>} element. */
    public String _title;

    /** The page body. HTML templates should have a parameter of this name in the {@code <body>} element. */
    public TemplateModel _body;

    /**
     * Constructor.
     *
     * @param _title
     *            the title
     * @param _body
     *            the body
     */
    public HTMLPageModel(final String _title, final TemplateModel _body) {
        this._title = _title;
        this._body = _body;
    }
}
