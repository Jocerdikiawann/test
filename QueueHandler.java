@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueueHandler {

    String value();
    String topic() default "";
    String description() default "";
    int concurrency() default 10;
    int maxPollRecords() default 10;
    int maxPollIntervalMs() default 300000;
    int sessionTimeoutMs() default 60000;
    int timeout() default 300;
    int maxRetries() default 3;
    int retryDelayMultiplier() default 5;
    int partitions() default 10;

    // UBAH INI: default jadi 1
    short replicationFactor() default 1;

    boolean enableDLQ() default true;
    int priority() default 0;
}
