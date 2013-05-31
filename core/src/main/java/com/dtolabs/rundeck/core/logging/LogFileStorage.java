package com.dtolabs.rundeck.core.logging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles log file storage and retrieval
 */
public interface LogFileStorage {
    /**
     * Stores a log file read from the given stream
     *
     * @param stream
     *
     * @throws IOException
     */
    void storeLogFile(InputStream stream) throws IOException;

    /**
     * Writes a log file to the given stream
     *
     * @param stream
     *
     * @throws IOException
     */
    void retrieveLogFile(OutputStream stream) throws IOException;
}