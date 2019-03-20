package serverx.exception;

/**
 * InternalServerException.
 */
public class InternalServerException extends ResponseException {
    /**
     * Constructor.
     */
    public InternalServerException() {
        super();
    }

    /**
     * Constructor.
     *
     * @param message
     *            the message
     * @param throwable
     *            the throwable
     */
    public InternalServerException(final String message, final Throwable throwable) {
        super(message, throwable);
    }

    /**
     * Constructor.
     *
     * @param message
     *            the message
     */
    public InternalServerException(final String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param throwable
     *            the throwable
     */
    public InternalServerException(final Throwable throwable) {
        super(throwable);
    }

    /* (non-Javadoc)
     * @see serverx.exception.ResponseException#statusCode()
     */
    @Override
    public int statusCode() {
        return 500;
    }

    /* (non-Javadoc)
     * @see serverx.exception.ResponseException#getMessage()
     */
    @Override
    public String getMessage() {
        return "Internal Server Exception" + (getCause() == null ? "" : ": " + getCause().getMessage());
    }

    /* (non-Javadoc)
     * @see serverx.exception.ResponseException#okToFillInStackTrace()
     */
    @Override
    protected boolean okToFillInStackTrace() {
        // Fill in stacktrace for internal server error
        return true;
    }
}
