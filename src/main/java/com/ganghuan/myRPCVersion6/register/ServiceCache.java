package com.ganghuan.myRPCVersion6.register;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端本地服务缓存：
 * 保存 serviceName -> addressList，避免每次调用都访问注册中心。
 */
public class ServiceCache {
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public List<String> getServiceAddresses(String serviceName) {
        return cache.getOrDefault(serviceName, Collections.emptyList());
    }

    public void updateServiceAddresses(String serviceName, List<String> addressList) {
        cache.put(serviceName, List.copyOf(addressList));
    }

    public void removeServiceAddresses(String serviceName) {
        cache.remove(serviceName);
    }
}
