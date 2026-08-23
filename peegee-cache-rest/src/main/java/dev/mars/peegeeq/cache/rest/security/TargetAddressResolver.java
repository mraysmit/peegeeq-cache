package dev.mars.peegeeq.cache.rest.security;

import java.net.InetAddress;
import java.util.List;

@FunctionalInterface
public interface TargetAddressResolver {
    List<InetAddress> resolve(String hostname) throws Exception;
}
