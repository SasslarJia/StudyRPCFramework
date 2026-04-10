package com.ganghuan.myRPCVersion6.client;

import com.ganghuan.myRPCVersion6.common.RPCResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局挂起请求表：维护 requestId -> CompletableFuture 的映射
 * 发送请求时注册，IO线程收到响应后通过 requestId 精确 complete 对应 future
 */
public class PendingRequests {

    private static final ConcurrentHashMap<String, CompletableFuture<RPCResponse>>
            map = new ConcurrentHashMap<>();

    public static void put(String requestId, CompletableFuture<RPCResponse> future) {
        map.put(requestId, future);
    }

    /** IO线程收到 response 时调用，complete 对应 future 并从 map 中移除 */
    public static void complete(RPCResponse response) {
        CompletableFuture<RPCResponse> future = map.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
        }
    }

    /** 超时或取消时主动清理，防止内存泄漏 */
    public static void remove(String requestId) {
        map.remove(requestId);
    }
}
