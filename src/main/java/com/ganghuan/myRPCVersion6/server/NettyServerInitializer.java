package com.ganghuan.myRPCVersion6.server;

import com.ganghuan.myRPCVersion6.codec.MyDecode;
import com.ganghuan.myRPCVersion6.codec.MyEncode;
import com.ganghuan.myRPCVersion6.codec.Serializer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import lombok.AllArgsConstructor;

/**
 * 初始化，主要负责序列化的编码解码， 需要解决netty的粘包问题
 */
@AllArgsConstructor
public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {
    private static final Serializer SERIALIZER =
            Serializer.getSerializerByName(System.getProperty("myrpc.serializer", "json"));

    private ServiceProvider serviceProvider;
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        // 使用自定义的编解码器
        pipeline.addLast(new MyDecode());
        // 编码器根据配置选择序列化器，默认 json，可切换为 jdk/protobuf/hessian
        pipeline.addLast(new MyEncode(SERIALIZER));
        pipeline.addLast(new NettyRPCServerHandler(serviceProvider));
    }
}
