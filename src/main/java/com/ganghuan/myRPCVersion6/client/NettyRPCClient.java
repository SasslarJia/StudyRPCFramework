package com.ganghuan.myRPCVersion6.client;

import com.ganghuan.myRPCVersion6.common.RPCRequest;
import com.ganghuan.myRPCVersion6.common.RPCResponse;
import com.ganghuan.myRPCVersion6.loadbalance.LoadBalance;
import com.ganghuan.myRPCVersion6.loadbalance.LoadBalanceFactory;
import com.ganghuan.myRPCVersion6.register.ServiceRegister;
import com.ganghuan.myRPCVersion6.register.ZkServiceRegister;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * 实现RPCClient接口
 * 基于 requestId + CompletableFuture 实现异步多路复用，单条长连接支持并发请求
 */
public class NettyRPCClient implements RPCClient {
    private static final int REQUEST_TIMEOUT_SECONDS = 3;
    private static final int MAX_RETRY_TIMES = 2;
    private static final int FAILURE_THRESHOLD = 3;
    private static final int HALF_OPEN_SUCCESS_THRESHOLD = 2;
    private static final long CIRCUIT_OPEN_MILLIS = 30_000L;

    private static final Bootstrap bootstrap;
    private static final EventLoopGroup eventLoopGroup;
    // 每个服务地址对应一个 Channel（长连接复用）
    private static final ConcurrentHashMap<String, Channel> channelMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, InstanceFaultState> faultStateMap = new ConcurrentHashMap<>();

    private final ServiceRegister serviceRegister;
    private final LoadBalance loadBalance;

    public NettyRPCClient() {
        this.serviceRegister = new ZkServiceRegister();
        this.loadBalance = LoadBalanceFactory.getLoadBalance(System.getProperty("myrpc.loadbalance", "round"));
    }

    // netty客户端初始化，重复使用
    static {
        eventLoopGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
                .handler(new NettyClientInitializer());
    }

    @Override
    public RPCResponse sendRequest(RPCRequest request) {
        List<InetSocketAddress> addressList = serviceRegister.getServiceAddresses(request.getInterfaceName());
        if (addressList.isEmpty()) {
            throw new RuntimeException("没有可用服务实例: " + request.getInterfaceName());
        }

        int maxAttempts = request.isRetryable() ? Math.min(addressList.size(), MAX_RETRY_TIMES + 1) : 1;
        Set<String> triedInstances = new HashSet<>();
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            InetSocketAddress address = selectAddress(addressList, request, triedInstances);
            if (address == null) {
                break;
            }
            String key = buildAddressKey(address);
            triedInstances.add(key);
            try {
                RPCResponse response = invokeOnce(request, address, key);
                onInstanceSuccess(key);
                return response;
            } catch (RetryableRpcException e) {
                onInstanceFailure(key);
                lastException = e;
                if (!request.isRetryable() || attempt == maxAttempts) {
                    break;
                }
                System.out.println("实例调用失败，准备重试其它实例: " + key + "，原因: " + e.getMessage());
            } catch (NonRetryableRpcException e) {
                onInstanceFailure(key);
                throw e;
            }
        }

        throw new RuntimeException("RPC调用失败: " + request.getInterfaceName(), lastException);
    }

    @Override
    public CompletableFuture<RPCResponse> sendRequestAsync(RPCRequest request) {
        List<InetSocketAddress> addressList = serviceRegister.getServiceAddresses(request.getInterfaceName());
        if (addressList.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("没有可用服务实例: " + request.getInterfaceName()));
        }
        int maxAttempts = request.isRetryable() ? Math.min(addressList.size(), MAX_RETRY_TIMES + 1) : 1;
        return sendRequestAsyncInternal(copyRequest(request), addressList, new HashSet<>(), maxAttempts);
    }

    private RPCResponse invokeOnce(RPCRequest request, InetSocketAddress address, String key) {
        try {
            return invokeOnceAsync(request, address, key).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RetryableRpcException) {
                throw (RetryableRpcException) cause;
            }
            if (cause instanceof NonRetryableRpcException) {
                throw (NonRetryableRpcException) cause;
            }
            throw new RetryableRpcException("RPC调用失败: " + request.getRequestId(), cause);
        }
    }

    private CompletableFuture<RPCResponse> invokeOnceAsync(RPCRequest request, InetSocketAddress address, String key) {
        Channel channel = getOrCreateChannel(address, key);
        String requestId = UUID.randomUUID().toString();
        request.setRequestId(requestId);
        CompletableFuture<RPCResponse> future = new CompletableFuture<>();
        PendingRequests.put(requestId, future);

        ChannelFuture writeFuture = channel.writeAndFlush(request);
        writeFuture.addListener(result -> {
            if (!result.isSuccess()) {
                PendingRequests.remove(requestId);
                removeChannel(key, channel);
                future.completeExceptionally(new RetryableRpcException("发送请求失败: " + key, result.cause()));
            }
        });

        channel.eventLoop().schedule(() -> {
            if (PendingRequests.remove(requestId) != null) {
                removeChannel(key, channel);
                future.completeExceptionally(new RetryableRpcException(
                        "RPC调用超时: " + requestId,
                        new TimeoutException("等待响应超时")
                ));
            }
        }, REQUEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        return future.thenApply(response -> {
            if (response.getCode() != 200) {
                throw new NonRetryableRpcException("服务端返回失败: " + response.getMessage());
            }
            return response;
        });
    }

    private Channel getOrCreateChannel(InetSocketAddress address, String key) {
        return channelMap.compute(key, (k, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            try {
                return bootstrap.connect(address.getHostName(), address.getPort()).sync().channel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RetryableRpcException("连接服务端失败: " + k, e);
            }
        });
    }

    private InetSocketAddress selectAddress(List<InetSocketAddress> addressList, RPCRequest request, Set<String> triedInstances) {
        List<InetSocketAddress> availableAddresses = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (InetSocketAddress address : addressList) {
            String key = buildAddressKey(address);
            if (triedInstances.contains(key)) {
                continue;
            }
            InstanceFaultState state = faultStateMap.computeIfAbsent(key, ignored -> new InstanceFaultState());
            if (state.allowRequest(now)) {
                availableAddresses.add(address);
            }
        }
        if (availableAddresses.isEmpty()) {
            return null;
        }
        return loadBalance.select(availableAddresses, request);
    }

    private void onInstanceSuccess(String key) {
        faultStateMap.computeIfAbsent(key, ignored -> new InstanceFaultState()).onSuccess();
    }

    private void onInstanceFailure(String key) {
        faultStateMap.computeIfAbsent(key, ignored -> new InstanceFaultState()).onFailure();
    }

    private void removeChannel(String key, Channel channel) {
        channelMap.remove(key, channel);
        if (channel != null) {
            channel.close();
        }
    }

    private String buildAddressKey(InetSocketAddress address) {
        return address.getHostName() + ":" + address.getPort();
    }

    private RPCRequest copyRequest(RPCRequest request) {
        return RPCRequest.builder()
                .requestId(request.getRequestId())
                .retryable(request.isRetryable())
                .interfaceName(request.getInterfaceName())
                .methodName(request.getMethodName())
                .params(request.getParams())
                .paramsTypes(request.getParamsTypes())
                .build();
    }

    private CompletableFuture<RPCResponse> sendRequestAsyncInternal(RPCRequest request,
                                                                    List<InetSocketAddress> addressList,
                                                                    Set<String> triedInstances,
                                                                    int remainingAttempts) {
        InetSocketAddress address = selectAddress(addressList, request, triedInstances);
        if (address == null) {
            return CompletableFuture.failedFuture(new RuntimeException("没有可用于重试的服务实例: " + request.getInterfaceName()));
        }
        String key = buildAddressKey(address);
        triedInstances.add(key);

        return invokeOnceAsync(request, address, key)
                .whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        onInstanceSuccess(key);
                    } else {
                        onInstanceFailure(key);
                    }
                })
                .handle((response, throwable) -> {
                    if (throwable == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    Throwable cause = unwrapCompletionThrowable(throwable);
                    if (!(cause instanceof RetryableRpcException) || !request.isRetryable() || remainingAttempts <= 1) {
                        return CompletableFuture.<RPCResponse>failedFuture(asRuntimeException(cause));
                    }
                    System.out.println("实例调用失败，准备异步重试其它实例: " + key + "，原因: " + cause.getMessage());
                    return sendRequestAsyncInternal(copyRequest(request), addressList, triedInstances, remainingAttempts - 1);
                })
                .thenCompose(future -> future);
    }

    private Throwable unwrapCompletionThrowable(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private RuntimeException asRuntimeException(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            return (RuntimeException) throwable;
        }
        return new RuntimeException(throwable);
    }

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 实例级本地故障状态：
     * 失败达到阈值后临时摘除，冷却期后放少量探测请求进入半开状态。
     */
    private static class InstanceFaultState {
        private CircuitState circuitState = CircuitState.CLOSED;
        private int consecutiveFailures;
        private int halfOpenSuccessCount;
        private boolean halfOpenProbeInFlight;
        private long isolatedUntil;

        synchronized boolean allowRequest(long now) {
            if (circuitState == CircuitState.CLOSED) {
                return true;
            }
            if (circuitState == CircuitState.OPEN) {
                if (now < isolatedUntil) {
                    return false;
                }
                circuitState = CircuitState.HALF_OPEN;
                halfOpenProbeInFlight = true;
                halfOpenSuccessCount = 0;
                return true;
            }
            if (!halfOpenProbeInFlight) {
                halfOpenProbeInFlight = true;
                return true;
            }
            return false;
        }

        synchronized void onSuccess() {
            consecutiveFailures = 0;
            if (circuitState == CircuitState.HALF_OPEN) {
                halfOpenProbeInFlight = false;
                halfOpenSuccessCount++;
                if (halfOpenSuccessCount >= HALF_OPEN_SUCCESS_THRESHOLD) {
                    circuitState = CircuitState.CLOSED;
                    halfOpenSuccessCount = 0;
                    isolatedUntil = 0L;
                }
                return;
            }
            circuitState = CircuitState.CLOSED;
            isolatedUntil = 0L;
        }

        synchronized void onFailure() {
            consecutiveFailures++;
            if (circuitState == CircuitState.HALF_OPEN || consecutiveFailures >= FAILURE_THRESHOLD) {
                openCircuit();
                return;
            }
            halfOpenProbeInFlight = false;
        }

        private void openCircuit() {
            circuitState = CircuitState.OPEN;
            isolatedUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MILLIS;
            consecutiveFailures = 0;
            halfOpenSuccessCount = 0;
            halfOpenProbeInFlight = false;
        }
    }

    private static class RetryableRpcException extends RuntimeException {
        RetryableRpcException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class NonRetryableRpcException extends RuntimeException {
        NonRetryableRpcException(String message) {
            super(message);
        }

        NonRetryableRpcException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
