package com.machina.mdevtools.tasks.modreload;

/**
 * Result of a mod reload operation
 * @param status The status of the reload operation
 * @param message Optional message describing the result (null if not applicable)
 */
public record ModReloadResult(Status status, String message) {
    /**
     * Status of the mod reload operation
     */
    public enum Status {
        /**
         * The reload was successful
         */
        SUCCESS,
        
        /**
         * The reload should be retried (e.g., JAR is incomplete)
         */
        RETRY_NEEDED,
        
        /**
         * An error occurred during reload (should not retry)
         */
        ERROR
    }
    
    /**
     * Create a success result
     * @return A successful reload result
     */
    public static ModReloadResult success() {
        return new ModReloadResult(Status.SUCCESS, null);
    }
    
    /**
     * Create a retry needed result
     * @param message Reason why retry is needed
     * @return A retry needed result
     */
    public static ModReloadResult retryNeeded(String message) {
        return new ModReloadResult(Status.RETRY_NEEDED, message);
    }
    
    /**
     * Create an error result
     * @param message Error message
     * @return An error result
     */
    public static ModReloadResult error(String message) {
        return new ModReloadResult(Status.ERROR, message);
    }
    
    /**
     * Check if the reload was successful
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
    
    /**
     * Check if retry is needed
     * @return true if retry is needed, false otherwise
     */
    public boolean shouldRetry() {
        return status == Status.RETRY_NEEDED;
    }
    
    /**
     * Check if an error occurred
     * @return true if an error occurred, false otherwise
     */
    public boolean isError() {
        return status == Status.ERROR;
    }
}
