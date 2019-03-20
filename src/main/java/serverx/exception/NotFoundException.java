package serverx.exception;

/**
 * NotFoundException.
 */
public class NotFoundException extends ResponseException {
    /* (non-Javadoc)
     * @see serverx.exception.ResponseException#statusCode()
     */
    @Override
    public int statusCode() {
        return 404;
    }

    /* (non-Javadoc)
     * @see serverx.exception.ResponseException#getMessage()
     */
    @Override
    public String getMessage() {
        return "Not Found";
    }
}
