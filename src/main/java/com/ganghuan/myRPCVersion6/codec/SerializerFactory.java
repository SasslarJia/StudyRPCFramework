package com.ganghuan.myRPCVersion6.codec;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工厂模式 + SPI 机制实现可插拔的序列化器
 */
public class SerializerFactory {
    private static final String DEFAULT_SERIALIZER = "json";

    private static final Map<Integer, Serializer> SERIALIZER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Serializer> SERIALIZER_NAME_CACHE = new ConcurrentHashMap<>();

    static {
        // 使用 Java SPI 机制动态加载所有实现 Serializer 接口的类
        ServiceLoader<Serializer> serviceLoader = ServiceLoader.load(Serializer.class);
        for (Serializer serializer : serviceLoader) {
            register(serializer);
        }

        registerBuiltin(new ObjectSerializer());
        registerBuiltin(new JsonSerializer());
        registerBuiltin(new ProtobufSerializer());
        registerBuiltin(new HessianSerializer());
    }

    public static Serializer getSerializer(int code) {
        return SERIALIZER_CACHE.get(code);
    }

    public static Serializer getSerializer(String name) {
        String serializerName = name == null || name.isBlank() ? DEFAULT_SERIALIZER : name;
        Serializer serializer = SERIALIZER_NAME_CACHE.get(serializerName);
        if (serializer != null) {
            return serializer;
        }
        return SERIALIZER_NAME_CACHE.get(DEFAULT_SERIALIZER);
    }

    private static void registerBuiltin(Serializer serializer) {
        SERIALIZER_CACHE.putIfAbsent(serializer.getType(), serializer);
        SERIALIZER_NAME_CACHE.putIfAbsent(serializer.getName(), serializer);
        if (serializer instanceof ObjectSerializer) {
            SERIALIZER_NAME_CACHE.putIfAbsent("object", serializer);
        }
        if (serializer instanceof HessianSerializer) {
            SERIALIZER_NAME_CACHE.putIfAbsent("hession", serializer);
        }
    }

    private static void register(Serializer serializer) {
        SERIALIZER_CACHE.put(serializer.getType(), serializer);
        SERIALIZER_NAME_CACHE.put(serializer.getName(), serializer);
    }
}
