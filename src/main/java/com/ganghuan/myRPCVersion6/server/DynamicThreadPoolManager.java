package com.ganghuan.myRPCVersion6.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端动态业务线程池：
 * 支持运行时调整 core/max/keepAlive，并提供当前负载快照供健康检查使用。
 */
public class DynamicThreadPoolManager {
    private static final String CORE_SIZE_KEY = "myrpc.server.biz.core";
    private static final String MAX_SIZE_KEY = "myrpc.server.biz.max";
    private static final String KEEP_ALIVE_KEY = "myrpc.server.biz.keepAliveSeconds";
    private static final String QUEUE_CAPACITY_KEY = "myrpc.server.biz.queueCapacity";

    private static final DynamicThreadPoolManager INSTANCE = new DynamicThreadPoolManager();

    private final ThreadPoolExecutor executor;
    private volatile PoolConfig config;

    private DynamicThreadPoolManager() {
        this.config = loadConfigFromSystemProperties();
        this.executor = new ThreadPoolExecutor(
                config.corePoolSize,
                config.maximumPoolSize,
                config.keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("myrpc-biz-" + THREAD_ID.incrementAndGet());
                    return thread;
                }
        );
    }

    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);

    public static DynamicThreadPoolManager getInstance() {
        return INSTANCE;
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    public PoolSnapshot snapshot() {
        return new PoolSnapshot(
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                config.queueCapacity,
                executor.getCompletedTaskCount()
        );
    }

    public synchronized void refreshFromSystemProperties() {
        PoolConfig latest = loadConfigFromSystemProperties();
        applyConfig(latest);
    }

    public synchronized void updateConfig(int corePoolSize, int maximumPoolSize, long keepAliveSeconds) {
        PoolConfig latest = new PoolConfig(corePoolSize, maximumPoolSize, keepAliveSeconds, config.queueCapacity);
        applyConfig(latest);
    }

    public void shutdown() {
        executor.shutdown();
    }

    private void applyConfig(PoolConfig latest) {
        if (latest.maximumPoolSize < latest.corePoolSize) {
            throw new IllegalArgumentException("maximumPoolSize 不能小于 corePoolSize");
        }
        int currentCore = executor.getCorePoolSize();
        int currentMax = executor.getMaximumPoolSize();

        if (latest.corePoolSize < currentCore) {
            executor.setCorePoolSize(latest.corePoolSize);
        }
        if (latest.maximumPoolSize != currentMax) {
            executor.setMaximumPoolSize(latest.maximumPoolSize);
        }
        if (latest.corePoolSize > executor.getCorePoolSize()) {
            executor.setCorePoolSize(latest.corePoolSize);
        }
        if (executor.getKeepAliveTime(TimeUnit.SECONDS) != latest.keepAliveSeconds) {
            executor.setKeepAliveTime(latest.keepAliveSeconds, TimeUnit.SECONDS);
        }
        this.config = latest;
    }

    private PoolConfig loadConfigFromSystemProperties() {
        int defaultCore = Math.max(Runtime.getRuntime().availableProcessors() * 2, 8);
        int defaultMax = Math.max(defaultCore * 2, 16);
        long defaultKeepAlive = 60L;
        int defaultQueueCapacity = 1000;

        int core = Integer.getInteger(CORE_SIZE_KEY, defaultCore);
        int max = Integer.getInteger(MAX_SIZE_KEY, defaultMax);
        long keepAlive = Long.getLong(KEEP_ALIVE_KEY, defaultKeepAlive);
        int queueCapacity = Integer.getInteger(QUEUE_CAPACITY_KEY, defaultQueueCapacity);
        return new PoolConfig(core, max, keepAlive, queueCapacity);
    }

    public static class PoolSnapshot {
        private final int corePoolSize;
        private final int maximumPoolSize;
        private final int activeCount;
        private final int queueSize;
        private final int queueCapacity;
        private final long completedTaskCount;

        public PoolSnapshot(int corePoolSize, int maximumPoolSize, int activeCount, int queueSize, int queueCapacity, long completedTaskCount) {
            this.corePoolSize = corePoolSize;
            this.maximumPoolSize = maximumPoolSize;
            this.activeCount = activeCount;
            this.queueSize = queueSize;
            this.queueCapacity = queueCapacity;
            this.completedTaskCount = completedTaskCount;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public int getActiveCount() {
            return activeCount;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public long getCompletedTaskCount() {
            return completedTaskCount;
        }

        public double getQueueUsage() {
            return queueCapacity == 0 ? 0D : (double) queueSize / queueCapacity;
        }

        public double getActiveUsage() {
            return maximumPoolSize == 0 ? 0D : (double) activeCount / maximumPoolSize;
        }
    }

    private static class PoolConfig {
        private final int corePoolSize;
        private final int maximumPoolSize;
        private final long keepAliveSeconds;
        private final int queueCapacity;

        private PoolConfig(int corePoolSize, int maximumPoolSize, long keepAliveSeconds, int queueCapacity) {
            this.corePoolSize = corePoolSize;
            this.maximumPoolSize = maximumPoolSize;
            this.keepAliveSeconds = keepAliveSeconds;
            this.queueCapacity = queueCapacity;
        }
    }
}
