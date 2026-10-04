package org.example.starpicbackend.api.outpainting;

/** Deliberately excludes raw provider responses, signed URLs, and credentials. */
public class OutPaintingProviderException extends RuntimeException {
    private final String errorCode;
    public OutPaintingProviderException(String errorCode) {
        super("扩图服务暂不可用");
        this.errorCode = errorCode;
    }
    public String getErrorCode() { return errorCode; }
}
