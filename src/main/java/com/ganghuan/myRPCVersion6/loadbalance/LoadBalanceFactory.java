package com.ganghuan.myRPCVersion6.loadbalance;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负载均衡工厂：
 * 支持 SPI 扩展，并提供 round/random/hash 三种内置策略。
 */
public class LoadBalanceFactory {
    private static final String DEFAULT_STRATEGY = "round";
    private static final Map<String, LoadBalance> LOAD_BALANCE_CACHE = new ConcurrentHashMap<>();

    static {
        ServiceLoader<LoadBalance> serviceLoader = ServiceLoader.load(LoadBalance.class);
        for (LoadBalance loadBalance : serviceLoader) {
            LOAD_BALANCE_CACHE.put(loadBalance.name(), loadBalance);
        }
        registerBuiltin(new RoundLoadBalance());
        registerBuiltin(new RandomLoadBalance());
        registerBuiltin(new ConsistentHashLoadBalance());
    }

    private static void registerBuiltin(LoadBalance loadBalance) {
        LOAD_BALANCE_CACHE.putIfAbsent(loadBalance.name(), loadBalance);
    }

    public static LoadBalance getLoadBalance(String strategyName) {
        String strategy = strategyName == null || strategyName.isBlank() ? DEFAULT_STRATEGY : strategyName;
        LoadBalance loadBalance = LOAD_BALANCE_CACHE.get(strategy);
        if (loadBalance != null) {
            return loadBalance;
        }
        return LOAD_BALANCE_CACHE.get(DEFAULT_STRATEGY);
    }
}
