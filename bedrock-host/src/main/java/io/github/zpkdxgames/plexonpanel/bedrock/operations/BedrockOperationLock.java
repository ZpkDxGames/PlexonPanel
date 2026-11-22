package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.util.concurrent.Semaphore;

/** One operation lock shared by all staged capabilities for the pinned container. */
public final class BedrockOperationLock {
    private final Semaphore lock = new Semaphore(1);

    boolean acquire() {
        return lock.tryAcquire();
    }

    void release() {
        lock.release();
    }
}
