package com.tournatest.loader.protocol;

/** Injectable monotonic time, so timeout and flash-delay tests do not need real sleeps. */
public interface Timing {
    long nowMs();
    void sleep(long milliseconds) throws InterruptedException;

    Timing SYSTEM = new Timing() {
        @Override public long nowMs() { return System.nanoTime() / 1_000_000L; }
        @Override public void sleep(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
    };
}
