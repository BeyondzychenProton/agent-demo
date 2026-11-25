package com.beyondzy.demo.service.mcp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.beyondzy.demo.domain.mcp.*;
import com.beyondzy.demo.exception.mcp.*;
import com.beyondzy.demo.mapper.mcp.*;
import com.beyondzy.demo.mcp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MCP 管理只处理配置服务、工具快照和开关，不访问 Skill 或员工关系。 */
@Service
public class McpManagementService {
    public record Actor(Long id, String name) {
        public static Actor system() { return new Actor(null,"系统"); }
    }
    private record Check(long id, long revision, long startedNanos) {}
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON=new ObjectMapper();
    private final McpServiceMapper services;
    private final McpToolMapper tools;
    private final McpOperationMapper operations;
    private final McpRegistry registry;
    private final TransactionTemplate tx;

    public McpManagementService(McpServiceMapper services, McpToolMapper tools,
            McpOperationMapper operations, McpRegistry registry, PlatformTransactionManager manager) {
        this.services=services; this.tools=tools; this.operations=operations; this.registry=registry;
        tx=new TransactionTemplate(manager);
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZONE); }
    private static String time(LocalDateTime t) { return t==null?null:t.atZone(ZONE).toOffsetDateTime().toString(); }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    private static McpManagementException error(int status,String code,String message) {
        return new McpManagementException(status,code,message);
    }
    private McpServiceBean service(long id) {
        McpServiceBean s=services.selectById(id);
        if(s==null) throw error(404,"MCP_NOT_FOUND","MCP 服务不存在");
        return s;
    }
    private McpToolBean tool(long id,long toolId) {
        McpToolBean t=tools.selectById(toolId);
        if(t==null || !Objects.equals(t.getServiceId(),id)) throw error(404,"MCP_TOOL_NOT_FOUND","MCP 工具不存在");
        return t;
    }
    private List<McpToolBean> allTools(long id) {
        return tools.selectList(new QueryWrapper<McpToolBean>().eq("service_id",id).orderByAsc("id"));
    }
    private McpRegistry.Configured configured(McpServiceBean s) {
        var c=registry.configured(s.getProviderKey());
        if(c==null) throw error(409,"MCP_CONFIG_REMOVED","服务配置已移除，无法执行此操作");
        return c;
    }
    public void ensureConfigured() {
        for(String key:registry.keys()) services.ensure(key,registry.configured(key).descriptor().name());
    }
    private McpServiceBean byKey(String key) {
        return services.selectOne(new QueryWrapper<McpServiceBean>().eq("provider_key",key));
    }
    public List<McpToolDefinition> startup(McpServer server) {
        services.ensure(server.id(),registry.configured(server.id()).descriptor().name());
        Check check=begin(byKey(server.id()).getId());
        try {
            List<McpToolDefinition> defs=validated(server.listTools());
            finish(check,defs,null,Actor.system(),true);
            return defs;
        } catch(RuntimeException e) {
            finish(check,null,e,Actor.system(),true);
            // 启动装配会记录异常消息，不能把远端回显写进应用日志。
            String code=failureCode(e);
            throw new McpCallException(code,failureMessage(code));
        }
    }
    private Check begin(long id) {
        return tx.execute(status->{
            McpServiceBean s=services.lock(id);
            if(s==null) throw error(404,"MCP_NOT_FOUND","MCP 服务不存在");
            long revision=s.getCheckRevision()+1;
            services.update(null,new UpdateWrapper<McpServiceBean>().eq("id",id)
                    .set("check_revision",revision).set("last_check_state","CHECKING")
                    .set("last_check_started_at",now()).set("gmt_modified",now()));
            return new Check(id,revision,System.nanoTime());
        });
    }
    public Map<String,Object> verify(long id,Actor actor) {
        McpServiceBean s=service(id);
        McpServer server=configured(s).server();
        Check check=begin(id);
        List<McpToolDefinition> definitions;
        try { definitions=validated(server.listTools()); }
        catch(RuntimeException e) { return finish(check,null,e,actor,false); }
        return finish(check,definitions,null,actor,false);
    }
    private Map<String,Object> finish(Check check,List<McpToolDefinition> defs,RuntimeException failure,
            Actor actor,boolean startup) {
        return tx.execute(status->{
            McpServiceBean s=services.lock(check.id());
            boolean latest=Objects.equals(s.getCheckRevision(),check.revision());
            String action=startup?"CATALOG_SYNCED":"CONNECTION_VERIFIED";
            int latency=(int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-check.startedNanos())/1_000_000);
            if(!latest) {
                audit(s.getId(),null,action,actor,"SUPERSEDED",map("checkRevision",check.revision()));
                return map("success",false,"superseded",true,"message","结果已被更新的检测取代");
            }
            String code=failure==null?null:failureCode(failure);
            String message=failure==null?null:failureMessage(code);
            UpdateWrapper<McpServiceBean> update=new UpdateWrapper<McpServiceBean>().eq("id",s.getId())
                    .set("last_check_state",failure==null?"HEALTHY":"FAILED")
                    .set("last_checked_at",now()).set("last_latency_ms",latency)
                    .set("last_error_code",code).set("last_error_message",message).set("gmt_modified",now());
            int added=0,removed=0,changed=0;
            if(failure==null) {
                Map<String,McpToolBean> existing=new HashMap<>();
                allTools(s.getId()).forEach(t->existing.put(t.getToolKey(),t));
                boolean adopt=startup && s.getCatalogInitialized()==0 && "mantianxing".equals(s.getProviderKey());
                Set<String> seen=new HashSet<>();
                for(McpToolDefinition d:defs) {
                    seen.add(d.name());
                    McpToolBean t=existing.get(d.name());
                    String hash=McpDefinitions.hash(d);
                    if(t==null) {
                        t=new McpToolBean();
                        t.setServiceId(s.getId()); t.setToolKey(d.name()); t.setDescription(d.description());
                        t.setInputSchemaJson(json(McpDefinitions.schema(d))); t.setWriteFlag(d.write()?1:0);
                        t.setDefinitionHash(hash); t.setEnabled(adopt?1:0); t.setPresent(1);
                        t.setControlVersion(0L); t.setFirstDiscoveredAt(now()); t.setLastSeenAt(now());
                        t.setGmtCreate(now()); t.setGmtModified(now()); tools.insert(t); added++;
                    } else {
                        if(!Objects.equals(hash,t.getDefinitionHash())) changed++;
                        tools.update(null,new UpdateWrapper<McpToolBean>().eq("id",t.getId())
                                .set("description",d.description()).set("input_schema_json",json(McpDefinitions.schema(d)))
                                .set("write_flag",d.write()?1:0).set("definition_hash",hash).set("present",1)
                                .set("last_seen_at",now()).set("gmt_modified",now()));
                    }
                }
                for(McpToolBean t:existing.values()) if(!seen.contains(t.getToolKey()) && t.getPresent()==1) {
                    tools.update(null,new UpdateWrapper<McpToolBean>().eq("id",t.getId()).set("present",0).set("gmt_modified",now()));
                    removed++;
                }
                update.set("catalog_initialized",1).set("catalog_revision",s.getCatalogRevision()+1).set("last_success_at",now());
            }
            services.update(null,update);
            audit(s.getId(),null,action,actor,failure==null?"SUCCESS":"FAILED",
                    map("checkRevision",check.revision(),"added",added,"removed",removed,"changed",changed,"errorCode",code));
            return map("success",failure==null,"superseded",false,"code",code,
                    "message",failure==null?"连接验证成功；新增或变化的工具需重启后加载":message,
                    "added",added,"removed",removed,"changed",changed,"detail",detail(s.getId()));
        });
    }
    private static List<McpToolDefinition> validated(List<McpToolDefinition> defs) {
        if(defs==null || defs.size()>1000) throw new McpCallException("INVALID_CATALOG","工具目录无效");
        Set<String> names=new HashSet<>(); int bytes=0;
        for(McpToolDefinition d:defs) {
            if(d==null || d.name()==null || d.name().isBlank() || d.name().length()>255 || !names.add(d.name()))
                throw new McpCallException("INVALID_CATALOG","工具目录包含无效或重复标识");
            String serialized=json(McpDefinitions.schema(d));
            bytes+=serialized.length();
            if(bytes>4_000_000) throw new McpCallException("INVALID_CATALOG","工具目录超过限制");
            McpDefinitions.hash(d);
        }
        return List.copyOf(defs);
    }
    private static String failureCode(RuntimeException e) {
        if(e instanceof McpCallException m && Set.of("UNAUTHENTICATED","SERVER_UNAVAILABLE","TRANSPORT_FAILURE",
                "PROTOCOL","INVALID_CATALOG","DISCOVERY_TIMEOUT","DISCOVERY_INCOMPLETE").contains(m.code())) return m.code();
        return "DISCOVERY_FAILED";
    }
    private static String failureMessage(String code) {
        return switch(code) {
            case "UNAUTHENTICATED" -> "发现身份或服务凭证校验失败，请检查代码配置";
            case "TRANSPORT_FAILURE" -> "连接失败或请求超时，上次成功目录已保留";
            case "DISCOVERY_TIMEOUT" -> "工具发现超过总时限，上次成功目录已保留";
            case "DISCOVERY_INCOMPLETE" -> "工具分页未完整读取，上次成功目录已保留";
            case "INVALID_CATALOG","PROTOCOL" -> "服务工具目录格式无效，上次成功目录已保留";
            default -> "连接验证失败，上次成功目录已保留，请检查服务配置";
        };
    }
    public Map<String,Object> list(String q,String state,String transport,String health,int page,int size) {
        checkPage(page,size);
        if(!Set.of("all","enabled","disabled").contains(state)) throw error(400,"INVALID_STATE","状态筛选无效");
        ensureConfigured();
        String query=Objects.toString(q,"").strip().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> selected=services.selectList(new QueryWrapper<McpServiceBean>().orderByAsc("id")).stream()
                .filter(s->(s.getDisplayName()+" "+s.getProviderKey()+" "+Objects.toString(s.getOwnerName(),"")+" "
                        +Objects.toString(s.getDescription(),"")).toLowerCase(Locale.ROOT).contains(query))
                .map(this::serviceView).filter(v->transport==null || transport.isBlank() || transport.equals(v.get("transport")))
                .filter(v->health==null || health.isBlank() || health.equals(v.get("health"))).toList();
        long enabled=selected.stream().filter(v->Boolean.TRUE.equals(v.get("enabled"))).count();
        List<Map<String,Object>> filtered=selected.stream().filter(v->"all".equals(state)
                || Boolean.TRUE.equals(v.get("enabled"))=="enabled".equals(state)).toList();
        var result=page(filtered,page,size);
        result.put("counts",map("all",selected.size(),"enabled",enabled,"disabled",selected.size()-enabled));
        return result;
    }
    public Map<String,Object> detail(long id) { return serviceView(service(id)); }
    private Map<String,Object> serviceView(McpServiceBean s) {
        var c=registry.configured(s.getProviderKey());
        var d=c==null?null:c.descriptor();
        List<McpToolBean> present=allTools(s.getId()).stream().filter(t->t.getPresent()==1).toList();
        long open=present.stream().filter(t->t.getEnabled()==1).count();
        long loaded=present.stream().filter(t->registry.loaded(s.getProviderKey(),t.getToolKey())!=null).count();
        LocalDateTime manualTime=services.lastManualChange(s.getId());
        String health=s.getLastCheckState();
        if("CHECKING".equals(health) && s.getLastCheckStartedAt()!=null && s.getLastCheckStartedAt().isBefore(now().minusSeconds(50)))
            health="INTERRUPTED";
        return map("id",s.getId().toString(),"providerKey",s.getProviderKey(),"displayName",s.getDisplayName(),
                "description",s.getDescription(),"ownerName",s.getOwnerName(),"enabled",s.getEnabled()==1,
                "controlVersion",s.getControlVersion(),"configured",c!=null,"endpoint",d==null?null:d.endpoint(),
                "updatedAt",time(manualTime==null?s.getGmtCreate():manualTime),
                "transport",d==null?null:d.transport(),"authType",d==null?null:d.authType(),
                "environment",d==null?null:d.environment(),"health",health,
                "lastCheckedAt",time(s.getLastCheckedAt()),"lastCheckStartedAt",time(s.getLastCheckStartedAt()),
                "lastSuccessAt",time(s.getLastSuccessAt()),"lastLatencyMs",s.getLastLatencyMs(),
                "lastErrorCode",s.getLastErrorCode(),"lastErrorMessage",s.getLastErrorMessage(),
                "historicalSnapshot",!"HEALTHY".equals(health) && s.getLastSuccessAt()!=null,
                "discoveredCount",present.size(),"openCount",open,"loadedCount",loaded,"instance",registry.instance());
    }
    public Map<String,Object> toolPage(long id,String q,String state,int page,int size) {
        checkPage(page,size);
        if(!Set.of("all","open","closed").contains(state)) throw error(400,"INVALID_STATE","工具状态筛选无效");
        McpServiceBean s=service(id); String query=Objects.toString(q,"").toLowerCase(Locale.ROOT);
        var rows=allTools(id).stream().filter(t->(t.getToolKey()+" "+Objects.toString(t.getDescription(),""))
                .toLowerCase(Locale.ROOT).contains(query))
                .filter(t->"all".equals(state) || (t.getEnabled()==1)=="open".equals(state))
                .map(t->toolView(s,t,false)).toList();
        return page(rows,page,size);
    }
    public Map<String,Object> toolDetail(long id,long toolId) { return toolView(service(id),tool(id,toolId),true); }
    private Map<String,Object> toolView(McpServiceBean s,McpToolBean t,boolean schema) {
        var loaded=registry.loaded(s.getProviderKey(),t.getToolKey());
        String code=registry.configured(s.getProviderKey())==null?"MCP_CONFIG_REMOVED":s.getEnabled()==0?"MCP_SERVICE_DISABLED":
                t.getEnabled()==0?"MCP_TOOL_DISABLED":t.getPresent()==0?"MCP_TOOL_UNAVAILABLE":
                loaded==null?"MCP_TOOL_NOT_LOADED":null;
        var v=map("id",t.getId().toString(),"providerKey",s.getProviderKey(),"toolKey",t.getToolKey(),
                "description",t.getDescription(),"write",t.getWriteFlag()==1,"enabled",t.getEnabled()==1,
                "serviceEnabled",s.getEnabled()==1,"present",t.getPresent()==1,"controlVersion",t.getControlVersion(),
                "loadedInCurrentInstance",loaded!=null,"registeredName",loaded==null?null:loaded.registeredName(),
                "definitionChanged",loaded!=null && !Objects.equals(loaded.hash(),t.getDefinitionHash()),
                "invocationAllowed",code==null,"unavailableCode",code,"lastSeenAt",time(t.getLastSeenAt()));
        if(schema) v.put("inputSchema",parse(t.getInputSchemaJson()));
        return v;
    }
    public Map<String,Object> metadata(long id,String name,String description,String owner,long version,Actor actor) {
        if(name==null || name.isBlank() || name.strip().length()>128 || description!=null && description.length()>1000
                || owner!=null && owner.length()>128) throw error(400,"INVALID_METADATA","名称必填且不超过 128 字，说明最多 1000 字，负责人最多 128 字");
        return tx.execute(status->{
            McpServiceBean s=service(id);
            int count=services.update(null,new UpdateWrapper<McpServiceBean>().eq("id",id).eq("control_version",version)
                    .set("display_name",name.strip()).set("description",description).set("owner_name",owner)
                    .set("control_version",version+1).set("updated_by",actor.id()).set("gmt_modified",now()));
            conflict(count);
            audit(id,null,"METADATA_UPDATED",actor,"SUCCESS",map("before",map("displayName",s.getDisplayName(),
                    "description",s.getDescription(),"ownerName",s.getOwnerName()),"after",
                    map("displayName",name.strip(),"description",description,"ownerName",owner)));
            return detail(id);
        });
    }
    public Map<String,Object> status(long id,boolean enabled,long version,Actor actor) {
        return tx.execute(status->{
            McpServiceBean s=service(id);
            if(enabled) configured(s);
            if(s.getControlVersion()!=version) conflict(0);
            if((s.getEnabled()==1)==enabled) return detail(id);
            conflict(services.update(null,new UpdateWrapper<McpServiceBean>().eq("id",id).eq("control_version",version)
                    .set("enabled",enabled?1:0).set("control_version",version+1).set("updated_by",actor.id()).set("gmt_modified",now())));
            audit(id,null,"SERVICE_STATUS_CHANGED",actor,"SUCCESS",map("before",s.getEnabled()==1,"after",enabled));
            return detail(id);
        });
    }
    public Map<String,Object> toolStatus(long id,long toolId,boolean enabled,long version,Actor actor) {
        return tx.execute(status->{
            McpServiceBean s=service(id); McpToolBean t=tool(id,toolId);
            if(enabled) { configured(s); if(t.getPresent()==0) throw error(409,"MCP_TOOL_UNAVAILABLE","工具已从远端目录移除"); }
            if(t.getControlVersion()!=version) conflict(0);
            if((t.getEnabled()==1)==enabled) return toolDetail(id,toolId);
            conflict(tools.update(null,new UpdateWrapper<McpToolBean>().eq("id",toolId).eq("control_version",version)
                    .set("enabled",enabled?1:0).set("control_version",version+1).set("updated_by",actor.id()).set("gmt_modified",now())));
            audit(id,toolId,"TOOL_STATUS_CHANGED",actor,"SUCCESS",map("toolKey",t.getToolKey(),"before",t.getEnabled()==1,"after",enabled));
            return toolDetail(id,toolId);
        });
    }
    private static void conflict(int updated) {
        if(updated!=1) throw error(409,"MCP_VERSION_CONFLICT","信息已被其他操作修改，请刷新后重试");
    }
    private void audit(long id,Long toolId,String action,Actor actor,String result,Map<String,Object> detail) {
        McpOperationBean op=new McpOperationBean();
        op.setServiceId(id); op.setToolId(toolId); op.setAction(action); op.setActorId(actor.id()); op.setActorName(actor.name());
        op.setOrigin(actor.id()==null?"SYSTEM":"ADMIN"); op.setResult(result); op.setDetailJson(json(detail));
        op.setGmtCreate(now()); op.setGmtModified(now()); operations.insert(op);
    }
    public Map<String,Object> history(long id,int page,int size) {
        service(id); checkPage(page,size);
        var q=new QueryWrapper<McpOperationBean>().eq("service_id",id);
        long total=operations.selectCount(q);
        var list=operations.selectList(q.orderByDesc("gmt_create","id").last("LIMIT "+size+" OFFSET "+((long)(page-1)*size)));
        return map("items",list.stream().map(o->map("id",o.getId().toString(),"toolId",o.getToolId()==null?null:o.getToolId().toString(),
                "action",o.getAction(),"actorName",o.getActorName(),"origin",o.getOrigin(),"result",o.getResult(),
                "detail",parse(o.getDetailJson()),"createdAt",time(o.getGmtCreate()))).toList(),"total",total,"page",page,"size",size);
    }
    private static Map<String,Object> page(List<Map<String,Object>> rows,int page,int size) {
        long start=(long)(page-1)*size;
        return map("items",start>=rows.size()?List.of():rows.subList((int)start,(int)Math.min(rows.size(),start+size)),
                "total",rows.size(),"page",page,"size",size);
    }
    private static void checkPage(int page,int size) {
        if(page<1 || size<1 || size>100) throw error(400,"INVALID_PAGE","分页参数无效");
    }
    private static String json(Object v) {
        try { return JSON.writeValueAsString(v); }
        catch(Exception e) { throw error(400,"INVALID_JSON","数据无法序列化"); }
    }
    private static Object parse(String v) {
        if(v==null) return null;
        try {
            Object decoded=JSON.readValue(v,Object.class);
            // H2 的 JSON 字符串驱动与 MySQL 返回形式不同；只解包 JSON 文本。
            return decoded instanceof String text?JSON.readValue(text,Object.class):decoded;
        } catch(Exception e) { throw error(503,"MCP_SNAPSHOT_INVALID","保存的 MCP 目录无法解析"); }
    }
}
