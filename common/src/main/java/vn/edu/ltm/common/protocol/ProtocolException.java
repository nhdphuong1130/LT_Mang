package vn.edu.ltm.common.protocol;

import java.io.IOException;

public class ProtocolException extends IOException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
