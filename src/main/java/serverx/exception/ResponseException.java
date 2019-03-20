package serverx.exception;

/**
 * ResponseException.
 */
public abstract class ResponseException extends RuntimeException {
    /**
     * Constructor.
     */
    public ResponseException() {
        super();
    }

    /**
     * Constructor.
     *
     * @param message
     *            the message
     */
    public ResponseException(final String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param throwable
     *            the throwable
     */
    public ResponseException(final Throwable throwable) {
        super(throwable);
    }

    /**
     * Constructor.
     *
     * @param message
     *            the message
     * @param throwable
     *            the throwable
     */
    public ResponseException(final String message, final Throwable throwable) {
        super(message, throwable);
    }

    /* (non-Javadoc)
     * @see java.lang.Throwable#getMessage()
     */
    @Override
    public String getMessage() {
        final String msg = super.getMessage();
        if (msg == null || msg.isEmpty()) {
            return "HTTP status " + statusCode();
        } else {
            return msg;
        }
    }

    /**
     * The HTTP status code for this exception.
     *
     * @return the int
     */
    public abstract int statusCode();

    /**
     * Whether to fill in stack trace(stack trace is not needed for anything other than
     * {@link InternalServerException}).
     *
     * @return true, if successful
     */
    protected boolean okToFillInStackTrace() {
        return false;
    }

    /**
     * Speed up exception (stack trace is not needed for anything other than {@link InternalServerException}).
     *
     * @return this
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return okToFillInStackTrace() ? super.fillInStackTrace() : this;
    }
}
