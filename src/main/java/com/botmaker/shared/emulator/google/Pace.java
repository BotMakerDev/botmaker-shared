package com.botmaker.shared.emulator.google;

import java.time.Duration;

/** The clock a sign-in or sign-out waits by; tests give one that only counts. */
interface Pace {

    long nowMillis();

    void sleep(Duration duration) throws InterruptedException;

    Pace REAL = new Pace() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            Thread.sleep(duration);
        }
    };
}
