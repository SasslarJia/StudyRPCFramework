package com.ganghuan.myRPCVersion6.client;

import com.ganghuan.myRPCVersion6.common.RPCResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class NettyClientHandler extends SimpleChannelInboundHandler<RPCResponse> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RPCResponse msg) throws Exception {
        // IO线程收到响应：通过 requestId 找到对应的 future，complete 它
        // 不关闭 Channel，保持长连接可供后续请求复用
        PendingRequests.complete(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
