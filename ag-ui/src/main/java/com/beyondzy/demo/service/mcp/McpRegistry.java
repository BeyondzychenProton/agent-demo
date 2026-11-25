package com.beyondzy.demo.service.mcp;

import com.beyondzy.demo.mcp.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 配置源和当前进程快照，无数据库开关缓存。 */
@Component
public class McpRegistry {
    public record Descriptor(String name, String endpoint, String transport, String authType, String environment) {}
    public record Configured(McpServer server, Descriptor descriptor) {}
    public record Loaded(String registeredName, String hash) {}
    private final Map<String,Configured> configured = new ConcurrentHashMap<>();
    private final Map<String,Map<String,Loaded>> loaded = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    public void register(McpServer server, Descriptor d) {
        URI u=URI.create(d.endpoint());
        String endpoint;
        try { endpoint=new URI(u.getScheme(),null,u.getHost(),u.getPort(),u.getPath(),null,null).toString(); }
        catch (Exception e) { endpoint="已配置"; }
        configured.put(server.id(),new Configured(server,new Descriptor(d.name(),endpoint,d.transport(),d.authType(),d.environment())));
    }
    public Configured configured(String key) { return configured.get(key); }
    public Set<String> keys() { return Set.copyOf(configured.keySet()); }
    public Loaded loaded(String provider, String tool) { return loaded.getOrDefault(provider,Map.of()).get(tool); }
    public void mounted(String provider, List<McpToolDefinition> defs, List<McpBoundTool> tools) {
        Map<String,String> hashes=new HashMap<>();
        defs.forEach(d->hashes.put(d.name(),McpDefinitions.hash(d)));
        Map<String,Loaded> state=new HashMap<>();
        tools.forEach(t->state.put(t.sourceName(),new Loaded(t.getName(),hashes.get(t.sourceName()))));
        loaded.put(provider,Map.copyOf(state));
    }
    public Map<String,Object> instance() { return Map.of("id",instanceId,"startedAt",startedAt.toString()); }
}
