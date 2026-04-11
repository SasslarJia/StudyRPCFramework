package com.ganghuan.myRPCVersion6.server;

import com.ganghuan.myRPCVersion6.common.RPCRequest;
import com.ganghuan.myRPCVersion6.common.RPCResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.AllArgsConstructor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;

/**
 * 因为是服务器端，我们知道接受到请求格式是RPCRequest
 * Object类型也行，强制转型就行
 */
@AllArgsConstructor
public class NettyRPCServerHandler extends SimpleChannelInboundHandler<RPCRequest> {
    private static final DynamicThreadPoolManager THREAD_POOL_MANAGER = DynamicThreadPoolManager.getInstance();

    private ServiceProvider serviceProvider;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RPCRequest msg) throws Exception {
        // 提交到业务线程池处理，不阻塞 Netty 的 IO 线程 (NioEventLoop)
        try {
            THREAD_POOL_MANAGER.execute(() -> {
                RPCResponse response = getResponse(msg);
                ctx.writeAndFlush(response);
            });
        } catch (RejectedExecutionException e) {
            ctx.writeAndFlush(RPCResponse.fail(msg.getRequestId()));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    RPCResponse getResponse(RPCRequest request) {
        // 得到服务名
        String interfaceName = request.getInterfaceName();
        // 得到服务端相应服务实现类
        Object service = serviceProvider.getService(interfaceName);
        // 反射调用方法
        Method method = null;
        try {
            method = service.getClass().getMethod(request.getMethodName(), request.getParamsTypes());
            Object invoke = method.invoke(service, request.getParams());
            return RPCResponse.success(invoke, request.getRequestId());
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            System.out.println("方法执行错误");
            return RPCResponse.fail(request.getRequestId());
        }
    }
}
