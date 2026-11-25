package com.beyondzy.demo.domain.auth;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 登录会话持久化模型。对应表：auth_session。 */
@Schema(description = "登录会话持久化模型。对应表：auth_session。")
@TableName("auth_session")
@Data
@EqualsAndHashCode(callSuper = true)
public class AuthSessionBean extends BaseAuditBean {
    @Schema(description = "登录会话主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "关联 app_user.id")
    private Long userId;
    @Schema(description = "明文 token 的 SHA-256 十六进制")
    @ToString.Exclude
    private String tokenHash;
    @Schema(description = "绝对过期时间")
    private LocalDateTime expiresAt;
    @Schema(description = "上次滑动续期时间")
    private LocalDateTime renewedAt;
}
