package com.beyondzy.demo.domain.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.user.BaseAuditBean;

/** 登录会话持久化模型。对应表：auth_session。 */
@TableName("auth_session")
public class AuthSessionBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String tokenHash;
    private Long expiresAt;
    private Long renewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public Long getRenewedAt() { return renewedAt; }
    public void setRenewedAt(Long renewedAt) { this.renewedAt = renewedAt; }
}
