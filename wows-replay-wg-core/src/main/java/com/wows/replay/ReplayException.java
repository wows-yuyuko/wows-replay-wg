package com.wows.replay;

/**
 * Unified exception type for replay parsing errors.
 */
public class ReplayException extends Exception {

    public ReplayException(String message) {
        super(message);
    }

    public ReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}
