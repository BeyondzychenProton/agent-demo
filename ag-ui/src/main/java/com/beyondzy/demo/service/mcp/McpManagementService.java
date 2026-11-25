package com.beyondzy.demo.service.mcp;

import com.baomidou.mybatisplus.core.batch.MybatisBatch;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.MybatisBatchUtils;
import com.beyondzy.demo.domain.mcp.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.beyondzy.demo.contract.PageResult;
import com.beyondzy.demo.contract.Pages;
import com.beyondzy.demo.enums.mcp.McpCallCode;
import com.beyondzy.demo.enums.mcp.McpProvider;
import com.beyondzy.demo.exception.mcp.*;
import com.beyondzy.demo.mapper.mcp.*;
import com.beyondzy.demo.mcp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MCP 管理只处理配置服务、工具快照和开关，不访问 Skill 或员工关系。 */
@Service
public class McpManagementService {
    public record Actor(Long id, String name) {
        public static Actor system() { return new Actor(null,"系统"); }
    }
    public record Check(long id, long revision, long startedNanos) {}
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int PRESENT = 1;
    private static final int ABSENT = 0;
    private static final int ENABLED = 1;
    private static final int DISABLED = 0;
    private static final int WRITE = 1;
    private static final int READ = 0;
    private static final int CATALOG_READY = 1;
    private static final int CATALOG_PENDING = 0;
    private final McpServiceMapper services;
    private final McpToolMapper tools;
    private final McpOperationMapper operations;
    private final McpRegistry registry;
    private final SqlSessionFactory sessions;
    private McpManagementService self;

    public McpManagementService(McpServiceMapper services, McpToolMapper tools,
            McpOperationMapper operations, McpRegistry registry, SqlSessionFactory sessions) {
        this.services=services; this.tools=tools; this.operations=operations; this.registry=registry;
        this.sessions=sessions;
    }

    /** 同类内部调用走代理，@Transactional 才会生效。 */
    @Autowired
    public void bindSelf(@Lazy McpManagementService self) {
        this.self = self;
    }

    private McpManagementService self() {
        return self == null ? this : self;
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
        return tools.selectList(Wrappers.<McpToolBean>lambdaQuery().eq(McpToolBean::getServiceId,id).orderByAsc(McpToolBean::getId));
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
        return services.selectOne(Wrappers.<McpServiceBean>lambdaQuery().eq(McpServiceBean::getProviderKey,key));
    }
    public List<McpToolDefinition> startup(McpServer server) {
        services.ensure(server.id(),registry.configured(server.id()).descriptor().name());
        Check check=self().begin(byKey(server.id()).getId());
        try {
            List<McpToolDefinition> defs=validated(server.listTools());
            self().finish(check,defs,null,Actor.system(),true);
            return defs;
        } catch(RuntimeException e) {
            self().finish(check,null,e,Actor.system(),true);
            // 启动装配会记录异常消息，不能把远端回显写进应用日志。
            String code=failureCode(e);
            throw new McpCallException(code,failureMessage(code));
        }
    }
    @Transactional(rollbackFor = Exception.class)
    public Check begin(long id) {
        McpServiceBean s=services.lock(id);
        if(s==null) throw error(404,"MCP_NOT_FOUND","MCP 服务不存在");
        long revision=s.getCheckRevision()+1;
        LocalDateTime currentTime=now();
        services.update(new LambdaUpdateWrapper<McpServiceBean>().eq(McpServiceBean::getId,id)
                .set(McpServiceBean::getCheckRevision,revision).set(McpServiceBean::getLastCheckState,"CHECKING")
                .set(McpServiceBean::getLastCheckStartedAt,currentTime).set(McpServiceBean::getGmtModified,currentTime));
        return new Check(id,revision,System.nanoTime());
    }
    public Map<String,Object> verify(long id,Actor actor) {
        McpServiceBean s=service(id);
        McpServer server=configured(s).server();
        Check check=self().begin(id);
        List<McpToolDefinition> definitions;
        try { definitions=validated(server.listTools()); }
        catch(RuntimeException e) { return self().finish(check,null,e,actor,false); }
        return self().finish(check,definitions,null,actor,false);
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> finish(Check check,List<McpToolDefinition> defs,RuntimeException failure,
            Actor actor,boolean startup) {
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
        LocalDateTime currentTime=now();
        LambdaUpdateWrapper<McpServiceBean> update=new LambdaUpdateWrapper<McpServiceBean>().eq(McpServiceBean::getId,s.getId())
                .set(McpServiceBean::getLastCheckState,failure==null?"HEALTHY":"FAILED")
                .set(McpServiceBean::getLastCheckedAt,currentTime).set(McpServiceBean::getLastLatencyMs,latency)
                .set(McpServiceBean::getLastErrorCode,code).set(McpServiceBean::getLastErrorMessage,message)
                .set(McpServiceBean::getGmtModified,currentTime);
        int added=0,removed=0,changed=0;
        if(failure==null) {
            Map<String,McpToolBean> existing=new HashMap<>();
            allTools(s.getId()).forEach(t->existing.put(t.getToolKey(),t));
            boolean adopt=startup && Objects.equals(s.getCatalogInitialized(),CATALOG_PENDING)
                    && McpProvider.MANTIANXING.code().equals(s.getProviderKey());
            Set<String> seen=new HashSet<>();
            List<McpToolBean> inserted=new ArrayList<>();
            List<McpToolBean> refreshed=new ArrayList<>();
            List<Long> absentIds=new ArrayList<>();
            for(McpToolDefinition d:defs) {
                seen.add(d.name());
                McpToolBean t=existing.get(d.name());
                String hash=McpDefinitions.hash(d);
                if(t==null) {
                    inserted.add(newTool(s.getId(),d,hash,adopt,currentTime));
                    added++;
                } else {
                    if(!Objects.equals(hash,t.getDefinitionHash())) changed++;
                    refreshed.add(refreshedTool(t.getId(),d,hash,currentTime));
                }
            }
            for(McpToolBean t:existing.values()) if(!seen.contains(t.getToolKey()) && Objects.equals(t.getPresent(),PRESENT)) {
                absentIds.add(t.getId());
                removed++;
            }
            insertTools(inserted);
            updateTools(refreshed);
            if(!absentIds.isEmpty()) tools.update(new LambdaUpdateWrapper<McpToolBean>().in(McpToolBean::getId,absentIds)
                    .set(McpToolBean::getPresent,ABSENT).set(McpToolBean::getGmtModified,currentTime));
            update.set(McpServiceBean::getCatalogInitialized,CATALOG_READY)
                    .set(McpServiceBean::getCatalogRevision,s.getCatalogRevision()+1)
                    .set(McpServiceBean::getLastSuccessAt,currentTime);
        }
        services.update(update);
        audit(s.getId(),null,action,actor,failure==null?"SUCCESS":"FAILED",
                map("checkRevision",check.revision(),"added",added,"removed",removed,"changed",changed,"errorCode",code));
        return map("success",failure==null,"superseded",false,"code",code,
                "message",failure==null?"连接验证成功；新增或变化的工具需重启后加载":message,
                "added",added,"removed",removed,"changed",changed,"detail",detail(s.getId()));
    }
    private static List<McpToolDefinition> validated(List<McpToolDefinition> defs) {
        if(defs==null || defs.size()>1000) throw new McpCallException(McpCallCode.INVALID_CATALOG,"工具目录无效");
        Set<String> names=new HashSet<>(); int bytes=0;
        for(McpToolDefinition d:defs) {
            if(d==null || d.name()==null || d.name().isBlank() || d.name().length()>255 || !names.add(d.name()))
                throw new McpCallException(McpCallCode.INVALID_CATALOG,"工具目录包含无效或重复标识");
            String serialized=json(McpDefinitions.schema(d));
            bytes+=serialized.length();
            if(bytes>4_000_000) throw new McpCallException(McpCallCode.INVALID_CATALOG,"工具目录超过限制");
            McpDefinitions.hash(d);
        }
        return List.copyOf(defs);
    }
    private static String failureCode(RuntimeException e) {
        if(e instanceof McpCallException m && Set.of(
                McpCallCode.UNAUTHENTICATED.name(), McpCallCode.SERVER_UNAVAILABLE.name(),
                McpCallCode.TRANSPORT_FAILURE.name(), McpCallCode.PROTOCOL.name(),
                McpCallCode.INVALID_CATALOG.name(), McpCallCode.DISCOVERY_TIMEOUT.name(),
                McpCallCode.DISCOVERY_INCOMPLETE.name()).contains(m.code())) return m.code();
        return "DISCOVERY_FAILED";
    }
    private static String failureMessage(String code) {
        return switch(code) {
            case String ignored when McpCallCode.UNAUTHENTICATED.name().equals(code) -> "发现身份或服务凭证校验失败，请检查代码配置";
            case String ignored when McpCallCode.TRANSPORT_FAILURE.name().equals(code) -> "连接失败或请求超时，上次成功目录已保留";
            case String ignored when McpCallCode.DISCOVERY_TIMEOUT.name().equals(code) -> "工具发现超过总时限，上次成功目录已保留";
            case String ignored when McpCallCode.DISCOVERY_INCOMPLETE.name().equals(code) -> "工具分页未完整读取，上次成功目录已保留";
            case String ignored when McpCallCode.INVALID_CATALOG.name().equals(code)
                    || McpCallCode.PROTOCOL.name().equals(code) -> "服务工具目录格式无效，上次成功目录已保留";
            default -> "连接验证失败，上次成功目录已保留，请检查服务配置";
        };
    }
    public Map<String,Object> list(String q,String state,String transport,String health,int page,int size) {
        checkPage(page,size);
        if(!Set.of("all","enabled","disabled").contains(state)) throw error(400,"INVALID_STATE","状态筛选无效");
        ensureConfigured();
        String query=Objects.toString(q,"").strip().toLowerCase(Locale.ROOT);
        List<McpServiceBean> matched=services.selectList(Wrappers.<McpServiceBean>lambdaQuery().orderByAsc(McpServiceBean::getId)).stream()
                .filter(s->(s.getDisplayName()+" "+s.getProviderKey()+" "+Objects.toString(s.getOwnerName(),"")+" "
                        +Objects.toString(s.getDescription(),"")).toLowerCase(Locale.ROOT).contains(query))
                .toList();
        Map<Long,List<McpToolBean>> toolsByService=toolsByService(matched);
        Map<Long,LocalDateTime> manualByService=manualChanges(matched);
        List<Map<String,Object>> selected=matched.stream()
                .map(s->serviceView(s,toolsByService.getOrDefault(s.getId(),List.of()),manualByService.get(s.getId())))
                .filter(v->transport==null || transport.isBlank() || transport.equals(v.get("transport")))
                .filter(v->health==null || health.isBlank() || health.equals(v.get("health"))).toList();
        long enabled=selected.stream().filter(v->Boolean.TRUE.equals(v.get("enabled"))).count();
        List<Map<String,Object>> filtered=selected.stream().filter(v->"all".equals(state)
                || Boolean.TRUE.equals(v.get("enabled"))=="enabled".equals(state)).toList();
        var result=page(filtered,page,size);
        result.put("counts",map("all",selected.size(),"enabled",enabled,"disabled",selected.size()-enabled));
        return result;
    }
    public Map<String,Object> detail(long id) { return serviceView(service(id)); }
    private Map<Long,List<McpToolBean>> toolsByService(List<McpServiceBean> rows) {
        if(rows.isEmpty()) return Map.of();
        List<Long> ids=rows.stream().map(McpServiceBean::getId).distinct().toList();
        return tools.selectList(Wrappers.<McpToolBean>lambdaQuery().in(McpToolBean::getServiceId,ids)
                .orderByAsc(McpToolBean::getId)).stream()
                .collect(Collectors.groupingBy(McpToolBean::getServiceId,LinkedHashMap::new,Collectors.toList()));
    }
    private Map<Long,LocalDateTime> manualChanges(List<McpServiceBean> rows) {
        if(rows.isEmpty()) return Map.of();
        List<Long> ids=rows.stream().map(McpServiceBean::getId).distinct().toList();
        Map<Long,LocalDateTime> result=new HashMap<>();
        for(McpServiceMapper.ManualChange row:services.lastManualChanges(ids)) {
            if(row.serviceId!=null) result.put(row.serviceId,row.changedAt);
        }
        return result;
    }
    private Map<String,Object> serviceView(McpServiceBean s) {
        return serviceView(s,allTools(s.getId()),services.lastManualChange(s.getId()));
    }
    private Map<String,Object> serviceView(McpServiceBean s,List<McpToolBean> serviceTools,LocalDateTime manualTime) {
        var c=registry.configured(s.getProviderKey());
        var d=c==null?null:c.descriptor();
        List<McpToolBean> present=serviceTools.stream().filter(t->Objects.equals(t.getPresent(),PRESENT)).toList();
        long open=present.stream().filter(t->Objects.equals(t.getEnabled(),ENABLED)).count();
        long loaded=present.stream().filter(t->registry.loaded(s.getProviderKey(),t.getToolKey())!=null).count();
        String health=s.getLastCheckState();
        if("CHECKING".equals(health) && s.getLastCheckStartedAt()!=null && s.getLastCheckStartedAt().isBefore(now().minusSeconds(50)))
            health="INTERRUPTED";
        return map("id",s.getId().toString(),"providerKey",s.getProviderKey(),"displayName",s.getDisplayName(),
                "description",s.getDescription(),"ownerName",s.getOwnerName(),"enabled",Objects.equals(s.getEnabled(),ENABLED),
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
                .filter(t->"all".equals(state) || Objects.equals(t.getEnabled(),ENABLED)=="open".equals(state))
                .map(t->toolView(s,t,false)).toList();
        return page(rows,page,size);
    }
    public Map<String,Object> toolDetail(long id,long toolId) { return toolView(service(id),tool(id,toolId),true); }
    private Map<String,Object> toolView(McpServiceBean s,McpToolBean t,boolean schema) {
        var loaded=registry.loaded(s.getProviderKey(),t.getToolKey());
        String code=registry.configured(s.getProviderKey())==null?"MCP_CONFIG_REMOVED"
                :Objects.equals(s.getEnabled(),DISABLED)?McpCallCode.MCP_SERVICE_DISABLED.name():
                Objects.equals(t.getEnabled(),DISABLED)?McpCallCode.MCP_TOOL_DISABLED.name():Objects.equals(t.getPresent(),ABSENT)?McpCallCode.MCP_TOOL_UNAVAILABLE.name():
                loaded==null?McpCallCode.MCP_TOOL_NOT_LOADED.name():null;
        var v=map("id",t.getId().toString(),"providerKey",s.getProviderKey(),"toolKey",t.getToolKey(),
                "description",t.getDescription(),"write",Objects.equals(t.getWriteFlag(),WRITE),"enabled",Objects.equals(t.getEnabled(),ENABLED),
                "serviceEnabled",Objects.equals(s.getEnabled(),ENABLED),"present",Objects.equals(t.getPresent(),PRESENT),"controlVersion",t.getControlVersion(),
                "loadedInCurrentInstance",loaded!=null,"registeredName",loaded==null?null:loaded.registeredName(),
                "definitionChanged",loaded!=null && !Objects.equals(loaded.hash(),t.getDefinitionHash()),
                "invocationAllowed",code==null,"unavailableCode",code,"lastSeenAt",time(t.getLastSeenAt()));
        if(schema) v.put("inputSchema",parse(t.getInputSchemaJson()));
        return v;
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> metadata(long id,String name,String description,String owner,long version,Actor actor) {
        if(name==null || name.isBlank() || name.strip().length()>128 || description!=null && description.length()>1000
                || owner!=null && owner.length()>128) throw error(400,"INVALID_METADATA","名称必填且不超过 128 字，说明最多 1000 字，负责人最多 128 字");
        McpServiceBean s=service(id);
        LocalDateTime currentTime=now();
        int count=services.update(new LambdaUpdateWrapper<McpServiceBean>().eq(McpServiceBean::getId,id)
                .eq(McpServiceBean::getControlVersion,version)
                .set(McpServiceBean::getDisplayName,name.strip()).set(McpServiceBean::getDescription,description)
                .set(McpServiceBean::getOwnerName,owner).set(McpServiceBean::getControlVersion,version+1)
                .set(McpServiceBean::getUpdatedBy,actor.id()).set(McpServiceBean::getGmtModified,currentTime));
        conflict(count);
        audit(id,null,"METADATA_UPDATED",actor,"SUCCESS",map("before",map("displayName",s.getDisplayName(),
                "description",s.getDescription(),"ownerName",s.getOwnerName()),"after",
                map("displayName",name.strip(),"description",description,"ownerName",owner)));
        return detail(id);
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> status(long id,boolean enabled,long version,Actor actor) {
        McpServiceBean s=service(id);
        if(enabled) configured(s);
        if(s.getControlVersion()!=version) conflict(0);
        if(Objects.equals(s.getEnabled(),ENABLED)==enabled) return detail(id);
        LocalDateTime currentTime=now();
        conflict(services.update(new LambdaUpdateWrapper<McpServiceBean>().eq(McpServiceBean::getId,id)
                .eq(McpServiceBean::getControlVersion,version)
                .set(McpServiceBean::getEnabled,flag(enabled)).set(McpServiceBean::getControlVersion,version+1)
                .set(McpServiceBean::getUpdatedBy,actor.id()).set(McpServiceBean::getGmtModified,currentTime)));
        audit(id,null,"SERVICE_STATUS_CHANGED",actor,"SUCCESS",map("before",Objects.equals(s.getEnabled(),ENABLED),"after",enabled));
        return detail(id);
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> toolStatus(long id,long toolId,boolean enabled,long version,Actor actor) {
        McpServiceBean s=service(id); McpToolBean t=tool(id,toolId);
        if(enabled) { configured(s); if(Objects.equals(t.getPresent(),ABSENT)) throw error(409,McpCallCode.MCP_TOOL_UNAVAILABLE.name(),"工具已从远端目录移除"); }
        if(t.getControlVersion()!=version) conflict(0);
        if(Objects.equals(t.getEnabled(),ENABLED)==enabled) return toolDetail(id,toolId);
        LocalDateTime currentTime=now();
        conflict(tools.update(new LambdaUpdateWrapper<McpToolBean>().eq(McpToolBean::getId,toolId)
                .eq(McpToolBean::getControlVersion,version)
                .set(McpToolBean::getEnabled,flag(enabled)).set(McpToolBean::getControlVersion,version+1)
                .set(McpToolBean::getUpdatedBy,actor.id()).set(McpToolBean::getGmtModified,currentTime)));
        audit(id,toolId,"TOOL_STATUS_CHANGED",actor,"SUCCESS",map("toolKey",t.getToolKey(),"before",Objects.equals(t.getEnabled(),ENABLED),"after",enabled));
        return toolDetail(id,toolId);
    }
    private static void conflict(int updated) {
        if(updated!=1) throw error(409,"MCP_VERSION_CONFLICT","信息已被其他操作修改，请刷新后重试");
    }
    private void audit(long id,Long toolId,String action,Actor actor,String result,Map<String,Object> detail) {
        McpOperationBean op=new McpOperationBean();
        op.setServiceId(id); op.setToolId(toolId); op.setAction(action); op.setActorId(actor.id()); op.setActorName(actor.name());
        op.setOrigin(actor.id()==null?"SYSTEM":"ADMIN"); op.setResult(result); op.setDetailJson(json(detail));
        operations.insert(op);
    }
    public Map<String,Object> history(long id,int page,int size) {
        service(id); checkPage(page,size);
        Page<McpOperationBean> query = new Page<>(page, size);
        operations.selectPage(query, Wrappers.<McpOperationBean>lambdaQuery().eq(McpOperationBean::getServiceId, id)
                .orderByDesc(McpOperationBean::getGmtCreate, McpOperationBean::getId));
        return map("items",query.getRecords().stream().map(o->map("id",o.getId().toString(),"toolId",o.getToolId()==null?null:o.getToolId().toString(),
                "action",o.getAction(),"actorName",o.getActorName(),"origin",o.getOrigin(),"result",o.getResult(),
                "detail",parse(o.getDetailJson()),"createdAt",time(o.getGmtCreate()))).toList(),"total",query.getTotal(),"page",page,"size",size);
    }
    private static Map<String,Object> page(List<Map<String,Object>> rows,int page,int size) {
        PageResult<Map<String,Object>> result = PageResult.slice(rows, page, size);
        return map("items",result.items(),"total",rows.size(),"page",result.page(),"size",result.size());
    }
    private static void checkPage(int page,int size) {
        if(!Pages.valid(page, size)) throw error(400,"INVALID_PAGE","分页参数无效");
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
    private static int flag(boolean enabled) { return enabled ? ENABLED : DISABLED; }
    private static int writeFlag(McpToolDefinition definition) {
        return Boolean.TRUE.equals(definition.write()) ? WRITE : READ;
    }
    private static McpToolBean newTool(long serviceId,McpToolDefinition definition,String hash,boolean adopt,
            LocalDateTime currentTime) {
        McpToolBean row=new McpToolBean();
        row.setServiceId(serviceId);
        row.setToolKey(definition.name());
        row.setDescription(definition.description());
        row.setInputSchemaJson(json(McpDefinitions.schema(definition)));
        row.setWriteFlag(writeFlag(definition));
        row.setDefinitionHash(hash);
        row.setEnabled(adopt ? ENABLED : DISABLED);
        row.setPresent(PRESENT);
        row.setControlVersion(0L);
        row.setFirstDiscoveredAt(currentTime);
        row.setLastSeenAt(currentTime);
        return row;
    }
    private static McpToolBean refreshedTool(Long id,McpToolDefinition definition,String hash,LocalDateTime currentTime) {
        McpToolBean row=new McpToolBean();
        row.setId(id);
        row.setDescription(definition.description());
        row.setInputSchemaJson(json(McpDefinitions.schema(definition)));
        row.setWriteFlag(writeFlag(definition));
        row.setDefinitionHash(hash);
        row.setPresent(PRESENT);
        row.setLastSeenAt(currentTime);
        row.setGmtModified(currentTime);
        return row;
    }
    private void insertTools(List<McpToolBean> rows) {
        if(rows.isEmpty()) return;
        MybatisBatch.Method<McpToolBean> method=new MybatisBatch.Method<>(McpToolMapper.class);
        MybatisBatchUtils.execute(sessions,rows,method.insert());
    }
    private void updateTools(List<McpToolBean> rows) {
        if(rows.isEmpty()) return;
        MybatisBatch.Method<McpToolBean> method=new MybatisBatch.Method<>(McpToolMapper.class);
        MybatisBatchUtils.execute(sessions,rows,method.update(row->new LambdaUpdateWrapper<McpToolBean>()
                .eq(McpToolBean::getId,row.getId())
                .set(McpToolBean::getDescription,row.getDescription())
                .set(McpToolBean::getInputSchemaJson,row.getInputSchemaJson())
                .set(McpToolBean::getWriteFlag,row.getWriteFlag())
                .set(McpToolBean::getDefinitionHash,row.getDefinitionHash())
                .set(McpToolBean::getPresent,row.getPresent())
                .set(McpToolBean::getLastSeenAt,row.getLastSeenAt())
                .set(McpToolBean::getGmtModified,row.getGmtModified())));
    }
}
