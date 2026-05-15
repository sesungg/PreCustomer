package com.example.personareport.report.pipeline;

/** Raised when an operator requests a graceful stop for the report pipeline. */
public class PipelineStopRequestedException extends RuntimeException {

    public PipelineStopRequestedException(String message) {
        super(message);
    }
}
