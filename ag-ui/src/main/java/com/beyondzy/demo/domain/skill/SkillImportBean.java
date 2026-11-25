package com.beyondzy.demo.domain.skill;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 一次技能包上传。原件和检查报告在确认前就落在这张表，草稿写入失败不能把它清掉。 */
@Schema(description = "一次技能包上传。原件和检查报告在确认前就落在这张表，草稿写入失败不能把它清掉。")
@TableName("ai_skill_import")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillImportBean extends BaseAuditBean {
    public static final int SOURCE_MISSING = 0;
    public static final int SOURCE_AVAILABLE = 1;

    @Schema(description = "导入批次主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "操作人范围内的上传幂等请求键")
    @TableField("request_key")
    private String requestKey;
    @Schema(description = "发起导入的 app_user.id")
    @TableField("created_by")
    private Long createdBy;
    @Schema(description = "UPLOADED 已上传 / CHECKED 检查完成 / CONFIRMED 已确认 / FAILED 失败 / CANCELLED 取消 / EXPIRED 过期")
    private String state;
    @Schema(description = "原始上传文件名，仅用于展示，不作为存储路径")
    private String filename;
    @Schema(description = "原始上传字节的 SHA-256，统一小写十六进制")
    @TableField("package_sha256")
    private String packageSha256;
    @Schema(description = "原始上传字节；当前业务上限 2MB；清理后为空")
    @TableField("package_body")
    private byte[] packageBody;
    @Schema(description = "策略处理后的目标技能名，确认前校验格式和同名冲突")
    @TableField("target_name")
    private String targetName;
    @Schema(description = "入口处理策略代码，具体取值由服务枚举统一定义")
    @TableField("entry_policy")
    private String entryPolicy;
    @Schema(description = "原文或平台入口等内容处理策略代码，由服务枚举定义")
    @TableField("content_policy")
    private String contentPolicy;
    @Schema(description = "检查报告快照，含规则标识、级别、结果及说明")
    @TableField("check_report_json")
    private String checkReportJson;
    @Schema(description = "生成检查报告的检查器版本，用于解释历史结果")
    @TableField("checker_version")
    private String checkerVersion;
    @Schema(description = "确认导入的 app_user.id，未确认时为空")
    @TableField("confirmed_by")
    private Long confirmedBy;
    @Schema(description = "确认导入时间，未确认时为空")
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;
    @Schema(description = "确认成功生成的 ai_skill_version.id")
    @TableField("result_version_id")
    private Long resultVersionId;
    @Schema(description = "结果快照，含处理方式、保留文件统计、警告及后续操作信息")
    @TableField("result_json")
    private String resultJson;
    @Schema(description = "最近一次失败的业务错误码，成功后清空")
    @TableField("error_code")
    private String errorCode;
    @Schema(description = "未完成会话过期时间，为空表示未设置")
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @Schema(description = "乐观锁版本号，更新时条件比较并递增")
    private Long revision;
    @Schema(description = "列表查询算出来的，不是表字段。1 表示原件还在。")
    @TableField(exist = false)
    private Integer sourceAvailable;
}
