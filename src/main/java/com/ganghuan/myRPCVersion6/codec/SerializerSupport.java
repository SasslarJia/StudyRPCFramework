package com.ganghuan.myRPCVersion6.codec;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ganghuan.myRPCVersion6.common.RPCRequest;
import com.ganghuan.myRPCVersion6.common.RPCResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * 非 Java 原生序列化器共享的辅助方法：
 * 负责 class name 解析、单个参数/返回值的 JSON 编解码。
 */
public final class SerializerSupport {
    private static final Map<String, Class<?>> PRIMITIVE_CLASS_MAP = new HashMap<>();

    static {
        PRIMITIVE_CLASS_MAP.put("boolean", boolean.class);
        PRIMITIVE_CLASS_MAP.put("byte", byte.class);
        PRIMITIVE_CLASS_MAP.put("char", char.class);
        PRIMITIVE_CLASS_MAP.put("short", short.class);
        PRIMITIVE_CLASS_MAP.put("int", int.class);
        PRIMITIVE_CLASS_MAP.put("long", long.class);
        PRIMITIVE_CLASS_MAP.put("float", float.class);
        PRIMITIVE_CLASS_MAP.put("double", double.class);
        PRIMITIVE_CLASS_MAP.put("void", void.class);
    }

    private SerializerSupport() {
    }

    public static byte[] encodeValue(Object value) {
        if (value == null) {
            return new byte[0];
        }
        return JSON.toJSONBytes(value);
    }

    public static Object decodeValue(byte[] bytes, Class<?> clazz) {
        if (bytes == null || bytes.length == 0 || clazz == null) {
            return null;
        }
        return JSON.parseObject(bytes, clazz);
    }

    public static Class<?> resolveClass(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        Class<?> primitiveClass = PRIMITIVE_CLASS_MAP.get(className);
        if (primitiveClass != null) {
            return primitiveClass;
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到类型: " + className, e);
        }
    }

    public static String getClassName(Class<?> clazz) {
        return clazz == null ? "" : clazz.getName();
    }

    public static Object[] normalizeRequestParams(RPCRequest request) {
        if (request.getParams() == null || request.getParamsTypes() == null) {
            return null;
        }
        Object[] normalized = new Object[request.getParams().length];
        for (int i = 0; i < request.getParams().length; i++) {
            Object param = request.getParams()[i];
            Class<?> paramType = request.getParamsTypes()[i];
            if (param == null) {
                normalized[i] = null;
            } else if (paramType.isAssignableFrom(param.getClass())) {
                normalized[i] = param;
            } else {
                normalized[i] = JSONObject.toJavaObject((JSONObject) param, paramType);
            }
        }
        return normalized;
    }

    public static Object normalizeResponseData(RPCResponse response) {
        if (response.getData() == null || response.getDataType() == null) {
            return response.getData();
        }
        if (response.getDataType().isAssignableFrom(response.getData().getClass())) {
            return response.getData();
        }
        return JSONObject.toJavaObject((JSONObject) response.getData(), response.getDataType());
    }
}
