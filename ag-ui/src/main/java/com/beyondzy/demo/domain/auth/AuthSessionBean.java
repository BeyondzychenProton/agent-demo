package com.beyondzy.demo.domain.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 登录会话持久化模型。对应表：auth_session。 */
@Schema(description = "登录会话持久化模型。对应表：auth_session。")
@TableName("auth_session")
public class AuthSessionBean extends BaseAuditBean {
    @Schema(description = "登录会话主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "关联 app_user.id")
    private Long userId;
    @Schema(description = "明文 token 的 SHA-256 十六进制")
    private String tokenHash;
    @Schema(description = "绝对过期时间")
    private LocalDateTime expiresAt;
    @Schema(description = "上次滑动续期时间")
    private LocalDateTime renewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRenewedAt() { return renewedAt; }
    public void setRenewedAt(LocalDateTime renewedAt) { this.renewedAt = renewedAt; }
}
