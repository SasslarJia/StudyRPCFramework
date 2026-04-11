package com.ganghuan.myRPCVersion6.server;


import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 实现RPCServer接口，负责监听与发送数据
 */
public class NettyRPCServer implements RPCServer {
    private ServiceProvider serviceProvider;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workGroup;
    private Channel serverChannel;
    private ServerHealthChecker healthChecker;

    public NettyRPCServer(ServiceProvider serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public void start(int port) {
        // netty 服务线程组boss负责建立连接， work负责具体的请求
        bossGroup = new NioEventLoopGroup();
        workGroup = new NioEventLoopGroup();
        healthChecker = new ServerHealthChecker(serviceProvider, DynamicThreadPoolManager.getInstance());
        System.out.println("Netty服务端启动了...端口号为" + port );
        try {
            // 启动netty服务器
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            // 初始化
            serverBootstrap.group(bossGroup,workGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new NettyServerInitializer(serviceProvider));
            // 同步阻塞
            ChannelFuture channelFuture = serverBootstrap.bind(port).sync();
            serverChannel = channelFuture.channel();
            healthChecker.start();
            // 死循环监听
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (healthChecker != null) {
                healthChecker.stop();
            }
            try {
                serviceProvider.markUnavailable();
            } catch (Exception e) {
                System.out.println("服务下线注销失败: " + e.getMessage());
            }
            DynamicThreadPoolManager.getInstance().shutdown();
            bossGroup.shutdownGracefully();
            workGroup.shutdownGracefully();
        }
    }

    @Override
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
    }
}
