package com.ganghuan.myRPCVersion6.client;

import com.ganghuan.myRPCVersion6.common.RPCRequest;
import com.ganghuan.myRPCVersion6.common.RPCResponse;
import com.ganghuan.myRPCVersion6.register.ServiceRegister;
import com.ganghuan.myRPCVersion6.register.ZkServiceRegister;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * 实现RPCClient接口
 * 基于 requestId + CompletableFuture 实现异步多路复用，单条长连接支持并发请求
 */
public class NettyRPCClient implements RPCClient {
    private static final Bootstrap bootstrap;
    private static final EventLoopGroup eventLoopGroup;
    // 每个服务地址对应一个 Channel（长连接复用）
    private static final ConcurrentHashMap<String, Channel> channelMap = new ConcurrentHashMap<>();
    private static final Timer timer = new HashedWheelTimer();

    private ServiceRegister serviceRegister;

    public NettyRPCClient() {
        this.serviceRegister = new ZkServiceRegister();
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
        InetSocketAddress address = serviceRegister.serviceDiscovery(request.getInterfaceName());
        String key = address.getHostName() + ":" + address.getPort();

        // 获取或建立长连接
        Channel channel = channelMap.compute(key, (k, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            try {
                return bootstrap.connect(address.getHostName(), address.getPort()).sync().channel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("连接服务端失败: " + k, e);
            }
        });

        // 生成 requestId，注册 CompletableFuture
        String requestId = UUID.randomUUID().toString();
        request.setRequestId(requestId);
        CompletableFuture<RPCResponse> future = new CompletableFuture<>();
        PendingRequests.put(requestId, future);

        channel.writeAndFlush(request);

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            PendingRequests.remove(requestId);
            throw new RuntimeException("RPC调用超时: " + requestId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            PendingRequests.remove(requestId);
            throw new RuntimeException("RPC调用被中断: " + requestId, e);
        } catch (Exception e) {
            PendingRequests.remove(requestId);
            throw new RuntimeException("RPC调用失败: " + requestId, e);
        }
    }
    @Override
    public CompletableFuture<RPCResponse> sendRequestAsync(RPCRequest request) {
        InetSocketAddress address = serviceRegister.serviceDiscovery(request.getInterfaceName());
        String key = address.getHostName() + ":" + address.getPort();

        // 获取或建立长连接
        Channel channel = channelMap.compute(key, (k, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            try {
                return bootstrap.connect(address.getHostName(), address.getPort()).sync().channel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("连接服务端失败: " + k, e);
            }
        });

        // 生成 requestId，注册 CompletableFuture
        String requestId = UUID.randomUUID().toString();
        request.setRequestId(requestId);
        CompletableFuture<RPCResponse> future = new CompletableFuture<>();
        PendingRequests.put(requestId, future);

        channel.writeAndFlush(request);

        // 使用 HashedWheelTimer 进行异步超时控制
        timer.newTimeout(timeout -> {
            if (!future.isDone()) {
                PendingRequests.remove(requestId);
                future.completeExceptionally(new TimeoutException("RPC调用超时: " + requestId));
            }
        }, 3, TimeUnit.SECONDS);

        return future;
    }
}
