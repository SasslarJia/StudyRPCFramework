package com.ganghuan.myRPCVersion6.client;

import com.ganghuan.myRPCVersion6.codec.MyDecode;
import com.ganghuan.myRPCVersion6.codec.MyEncode;
import com.ganghuan.myRPCVersion6.codec.Serializer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

/**
 * 同样的与服务端解码和编码格式
 */
public class NettyClientInitializer extends ChannelInitializer<SocketChannel> {
    private static final Serializer SERIALIZER =
            Serializer.getSerializerByName(System.getProperty("myrpc.serializer", "json"));

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        // 使用自定义的编解码器
        pipeline.addLast(new MyDecode());
        // 编码器根据配置选择序列化器，默认 json，可切换为 jdk/protobuf/hessian
        pipeline.addLast(new MyEncode(SERIALIZER));
        pipeline.addLast(new NettyClientHandler());
    }
}
