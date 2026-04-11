package com.ganghuan.myRPCVersion6.server;



import com.ganghuan.myRPCVersion6.register.ServiceRegister;
import com.ganghuan.myRPCVersion6.register.ZkServiceRegister;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存放服务接口名与服务端对应的实现类
 * 服务启动时要暴露其相关的实现类
 * 根据request中的interface调用服务端中相关实现类
 *
 * 后面在这里新增了在zookeeper中注册的功能
 */
public class ServiceProvider {
    /**
     * 一个实现类可能实现多个服务接口，
     */
    private final Map<String, Object> interfaceProvider;

    private final ServiceRegister serviceRegister;
    private final String host;
    private final int port;
    private final Set<String> registeredServices = ConcurrentHashMap.newKeySet();
    private volatile boolean available = true;

    public ServiceProvider(String host, int port){
        // 需要传入服务端自身的服务的网络地址
        this.host = host;
        this.port = port;
        this.interfaceProvider = new HashMap<>();
        this.serviceRegister = new ZkServiceRegister();
    }

    public void provideServiceInterface(Object service){
        Class<?>[] interfaces = service.getClass().getInterfaces();

        for(Class clazz : interfaces){
            // 本机的映射表
            interfaceProvider.put(clazz.getName(),service);
            // 在注册中心注册服务
            registeredServices.add(clazz.getName());
            if (available) {
                serviceRegister.register(clazz.getName(), new InetSocketAddress(host, port));
            }
        }

    }

    public Object getService(String interfaceName){
        return interfaceProvider.get(interfaceName);
    }

    public synchronized void markUnavailable() {
        if (!available) {
            return;
        }
        InetSocketAddress address = new InetSocketAddress(host, port);
        for (String serviceName : registeredServices) {
            serviceRegister.deregister(serviceName, address);
        }
        available = false;
    }

    public synchronized void markAvailable() {
        if (available) {
            return;
        }
        InetSocketAddress address = new InetSocketAddress(host, port);
        for (String serviceName : registeredServices) {
            serviceRegister.register(serviceName, address);
        }
        available = true;
    }

    public boolean isAvailable() {
        return available;
    }

    public Set<String> getRegisteredServices() {
        return Collections.unmodifiableSet(registeredServices);
    }
}
