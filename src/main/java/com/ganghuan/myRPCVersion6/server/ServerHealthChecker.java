package com.ganghuan.myRPCVersion6.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务端 readiness 健康检查：
 * 当前先基于业务线程池负载做判定，当线程池过载时主动摘除实例；恢复后重新注册。
 */
public class ServerHealthChecker {
    private static final long CHECK_INTERVAL_SECONDS = Long.getLong("myrpc.server.health.intervalSeconds", 5L);
    private static final double UNHEALTHY_QUEUE_USAGE = Double.parseDouble(System.getProperty("myrpc.server.health.unhealthyQueueUsage", "0.80"));
    private static final double HEALTHY_QUEUE_USAGE = Double.parseDouble(System.getProperty("myrpc.server.health.healthyQueueUsage", "0.50"));
    private static final double UNHEALTHY_ACTIVE_USAGE = Double.parseDouble(System.getProperty("myrpc.server.health.unhealthyActiveUsage", "0.95"));
    private static final double HEALTHY_ACTIVE_USAGE = Double.parseDouble(System.getProperty("myrpc.server.health.healthyActiveUsage", "0.75"));

    private final ServiceProvider serviceProvider;
    private final DynamicThreadPoolManager threadPoolManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setName("myrpc-health-checker");
        return thread;
    });

    public ServerHealthChecker(ServiceProvider serviceProvider, DynamicThreadPoolManager threadPoolManager) {
        this.serviceProvider = serviceProvider;
        this.threadPoolManager = threadPoolManager;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::checkHealth, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void checkHealth() {
        try {
            threadPoolManager.refreshFromSystemProperties();
            DynamicThreadPoolManager.PoolSnapshot snapshot = threadPoolManager.snapshot();
            boolean unhealthy = snapshot.getQueueUsage() >= UNHEALTHY_QUEUE_USAGE
                    || snapshot.getActiveUsage() >= UNHEALTHY_ACTIVE_USAGE;
            boolean healthy = snapshot.getQueueUsage() <= HEALTHY_QUEUE_USAGE
                    && snapshot.getActiveUsage() <= HEALTHY_ACTIVE_USAGE;

            if (unhealthy && serviceProvider.isAvailable()) {
                serviceProvider.markUnavailable();
                System.out.println("服务端健康检查判定为过载，已摘除实例。queueUsage="
                        + snapshot.getQueueUsage() + ", activeUsage=" + snapshot.getActiveUsage());
                return;
            }
            if (healthy && !serviceProvider.isAvailable()) {
                serviceProvider.markAvailable();
                System.out.println("服务端健康检查判定已恢复，实例重新注册。queueUsage="
                        + snapshot.getQueueUsage() + ", activeUsage=" + snapshot.getActiveUsage());
            }
        } catch (Exception e) {
            System.out.println("服务端健康检查执行失败: " + e.getMessage());
        }
    }
}
