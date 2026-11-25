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
    /** 本次管理操作的操作人。没有用户编号时视为系统操作。 */
    public record Actor(Long id, String name) {
        public static Actor system() { return new Actor(null,"系统"); }
    }
    /** 一次检测的服务、序号和开始时刻，用于判断写回时是否仍是最新一次。 */
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
    /** 按键值交替参数组装保持插入顺序的 Map。 */
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
    /** 为当前进程已配置、库中还没有的服务补一条管理记录，不改已有展示信息。 */
    public void ensureConfigured() {
        for(String key:registry.keys()) services.ensure(key,registry.configured(key).descriptor().name());
    }
    private McpServiceBean byKey(String key) {
        return services.selectOne(Wrappers.<McpServiceBean>lambdaQuery().eq(McpServiceBean::getProviderKey,key));
    }
    /** 用同一次发现登记目录并返回这份工具快照。失败也先记下检测结果，再抛出不含远端原文的错误。 */
    public List<McpToolDefinition> startup(McpServer server) {
        services.ensure(server.id(),registry.configured(server.id()).descriptor().name());
        // 开始和结束分两段事务：先提交 CHECKING，远端发现放在事务外，返回后再写结果。
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
    /** 锁定服务，把检测序号加一，状态改为 CHECKING。 */
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
    /** 对仍在代码配置中的服务做一次远端发现，并写回检测和目录结果。 */
    public Map<String,Object> verify(long id,Actor actor) {
        McpServiceBean s=service(id);
        McpServer server=configured(s).server();
        // 与启动相同：先提交检测开始，事务外拉目录，再单独写回。
        Check check=self().begin(id);
        List<McpToolDefinition> definitions;
        try { definitions=validated(server.listTools()); }
        catch(RuntimeException e) { return self().finish(check,null,e,actor,false); }
        return self().finish(check,definitions,null,actor,false);
    }
    /** 仅当本次检测序号仍是最新时写回结果。成功才更新目录，且不改人工开关。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> finish(Check check,List<McpToolDefinition> defs,RuntimeException failure,
            Actor actor,boolean startup) {
        McpServiceBean s=services.lock(check.id());
        boolean latest=Objects.equals(s.getCheckRevision(),check.revision());
        String action=startup?"CATALOG_SYNCED":"CONNECTION_VERIFIED";
        int latency=(int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-check.startedNanos())/1_000_000);
        // 已有更新的检测占用序号时，只记一条被取代的操作，不改目录和开关。
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
        // 失败只更新检测状态、耗时和错误摘要。成功才按工具键对齐目录。
        if(failure==null) {
            Map<String,McpToolBean> existing=new HashMap<>();
            allTools(s.getId()).forEach(t->existing.put(t.getToolKey(),t));
            // 只有满天星且尚未建立基线的启动发现，才把本次新工具默认开放；其余新工具默认关闭。已有工具的开关不在这里改。
            boolean adopt=startup && Objects.equals(s.getCatalogInitialized(),CATALOG_PENDING)
                    && McpProvider.MANTIANXING.code().equals(s.getProviderKey());
            // 新工具插入，已有工具只刷新发现字段；这次目录里没有、且仍标记存在的改为不存在。
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
        // 检测写回不改 control_version 和 updated_by。目录变更和这条操作记录在同一事务里提交。
        services.update(update);
        audit(s.getId(),null,action,actor,failure==null?"SUCCESS":"FAILED",
                map("checkRevision",check.revision(),"added",added,"removed",removed,"changed",changed,"errorCode",code));
        return map("success",failure==null,"superseded",false,"code",code,
                "message",failure==null?"连接验证成功；新增或变化的工具需重启后加载":message,
                "added",added,"removed",removed,"changed",changed,"detail",detail(s.getId()));
    }
    // 空列表可以成立。任一工具不合法或整份目录超限时整次失败，避免用残缺结果覆盖上次成功目录。
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
    // 只有已归类的发现错误码原样保留，其余记为 DISCOVERY_FAILED。摘要是固定文案，不保存异常原文。
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
    /** 按关键字、传输方式、健康和启停筛选服务。启停计数不随当前启停标签变化。 */
    public Map<String,Object> list(String q,String state,String transport,String health,int page,int size) {
        checkPage(page,size);
        if(!Set.of("all","enabled","disabled").contains(state)) throw error(400,"INVALID_STATE","状态筛选无效");
        ensureConfigured();
        String query=Objects.toString(q,"").strip().toLowerCase(Locale.ROOT);
        // 关键字匹配展示名、服务键、负责人和说明。传输方式和健康来自组装后的视图，所以先组装再筛。
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
        // 启停计数按搜索、传输方式和健康筛完的集合计算，当前启停标签只切列表。
        long enabled=selected.stream().filter(v->Boolean.TRUE.equals(v.get("enabled"))).count();
        List<Map<String,Object>> filtered=selected.stream().filter(v->"all".equals(state)
                || Boolean.TRUE.equals(v.get("enabled"))=="enabled".equals(state)).toList();
        var result=page(filtered,page,size);
        result.put("counts",map("all",selected.size(),"enabled",enabled,"disabled",selected.size()-enabled));
        return result;
    }
    /** 返回单个服务的展示信息、检测结果和工具计数。 */
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
        // 已发现、已开放都只统计仍在目录中的工具；已开放还要求工具开关为开，与服务总开关无关。已加载再要求当前进程注册过该工具。
        long open=present.stream().filter(t->Objects.equals(t.getEnabled(),ENABLED)).count();
        long loaded=present.stream().filter(t->registry.loaded(s.getProviderKey(),t.getToolKey())!=null).count();
        // CHECKING 超过 50 秒仍未写回时，响应里改显示为 INTERRUPTED，不改库里的状态。
        String health=s.getLastCheckState();
        if("CHECKING".equals(health) && s.getLastCheckStartedAt()!=null && s.getLastCheckStartedAt().isBefore(now().minusSeconds(50)))
            health="INTERRUPTED";
        // 信息更新时间取最近一次人工保存或服务开关记录，没有则用创建时间，不用检测时间。
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
    /** 按关键字和开闭状态分页返回该服务下的工具。 */
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
    /** 返回单个工具的发现快照、开关和当前进程是否已加载。 */
    public Map<String,Object> toolDetail(long id,long toolId) { return toolView(service(id),tool(id,toolId),true); }
    private Map<String,Object> toolView(McpServiceBean s,McpToolBean t,boolean schema) {
        var loaded=registry.loaded(s.getProviderKey(),t.getToolKey());
        // 不可调用原因按配置已移除、服务停用、工具关闭、目录中已消失、当前进程未加载的顺序取第一个。
        String code=registry.configured(s.getProviderKey())==null?"MCP_CONFIG_REMOVED"
                :Objects.equals(s.getEnabled(),DISABLED)?McpCallCode.MCP_SERVICE_DISABLED.name():
                Objects.equals(t.getEnabled(),DISABLED)?McpCallCode.MCP_TOOL_DISABLED.name():Objects.equals(t.getPresent(),ABSENT)?McpCallCode.MCP_TOOL_UNAVAILABLE.name():
                loaded==null?McpCallCode.MCP_TOOL_NOT_LOADED.name():null;
        // 当前进程已加载且摘要与库中定义不同，表示远端定义已变，要重启后才会按新结构调用。
        var v=map("id",t.getId().toString(),"providerKey",s.getProviderKey(),"toolKey",t.getToolKey(),
                "description",t.getDescription(),"write",Objects.equals(t.getWriteFlag(),WRITE),"enabled",Objects.equals(t.getEnabled(),ENABLED),
                "serviceEnabled",Objects.equals(s.getEnabled(),ENABLED),"present",Objects.equals(t.getPresent(),PRESENT),"controlVersion",t.getControlVersion(),
                "loadedInCurrentInstance",loaded!=null,"registeredName",loaded==null?null:loaded.registeredName(),
                "definitionChanged",loaded!=null && !Objects.equals(loaded.hash(),t.getDefinitionHash()),
                "invocationAllowed",code==null,"unavailableCode",code,"lastSeenAt",time(t.getLastSeenAt()));
        if(schema) v.put("inputSchema",parse(t.getInputSchemaJson()));
        return v;
    }
    /** 按乐观锁保存展示名称、说明和负责人，并记一条管理操作。 */
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
    /** 按乐观锁修改服务总开关，不改各工具自己的开关。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> status(long id,boolean enabled,long version,Actor actor) {
        McpServiceBean s=service(id);
        // 启用前要求代码配置还在。版本不一致直接冲突；开关值没变则不写库、不记操作。
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
    /** 按乐观锁修改工具开关。已从最近成功目录消失的工具不能再开放。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> toolStatus(long id,long toolId,boolean enabled,long version,Actor actor) {
        McpServiceBean s=service(id); McpToolBean t=tool(id,toolId);
        // 重新开放时要求配置还在，且工具仍在目录中。关闭不做这两项检查。
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
    /** 乐观锁要求恰好更新一行，否则视为已被其他操作修改。 */
    private static void conflict(int updated) {
        if(updated!=1) throw error(409,"MCP_VERSION_CONFLICT","信息已被其他操作修改，请刷新后重试");
    }
    private void audit(long id,Long toolId,String action,Actor actor,String result,Map<String,Object> detail) {
        McpOperationBean op=new McpOperationBean();
        op.setServiceId(id); op.setToolId(toolId); op.setAction(action); op.setActorId(actor.id()); op.setActorName(actor.name());
        op.setOrigin(actor.id()==null?"SYSTEM":"ADMIN"); op.setResult(result); op.setDetailJson(json(detail));
        operations.insert(op);
    }
    /** 按创建时间和主键倒序分页返回该服务的管理操作。 */
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
    // 新行写入开关和首次发现时间。刷新行只带发现字段，批量更新也只写这些列，避免盖掉人工开关和乐观锁版本。
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
