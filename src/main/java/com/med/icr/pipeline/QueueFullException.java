package com.med.icr.pipeline;

/** 阻塞式投递超时：队列满且等待超时，计数不变。 */
public class QueueFullException extends RuntimeException {
    public final String code = "QUEUE_FULL_TIMEOUT";
    public QueueFullException(String message) { super(message); }
}
