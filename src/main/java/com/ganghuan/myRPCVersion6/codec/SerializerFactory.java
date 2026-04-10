package com.ganghuan.myRPCVersion6.codec;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工厂模式 + SPI 机制实现可插拔的序列化器
 */
public class SerializerFactory {

    private static final Map<Integer, Serializer> SERIALIZER_CACHE = new ConcurrentHashMap<>();

    static {
        // 使用 Java SPI 机制动态加载所有实现 Serializer 接口的类
        ServiceLoader<Serializer> serviceLoader = ServiceLoader.load(Serializer.class);
        for (Serializer serializer : serviceLoader) {
            SERIALIZER_CACHE.put(serializer.getType(), serializer);
        }
        
        // 默认保底注册（防止用户没配 SPI）
        if (SERIALIZER_CACHE.isEmpty()) {
            SERIALIZER_CACHE.put(0, new ObjectSerializer());
            SERIALIZER_CACHE.put(1, new JsonSerializer());
        }
    }

    public static Serializer getSerializer(int code) {
        return SERIALIZER_CACHE.get(code);
    }
}
