package com.tournatest.loader.protocol;

import java.io.Closeable;
import java.io.IOException;

public interface SerialTransport extends Closeable {
    int read(byte[] buffer, int timeoutMs) throws IOException;
    void write(byte[] bytes, int timeoutMs) throws IOException;
    void setDtr(boolean enabled) throws IOException;
    void discardInput() throws IOException;
}
