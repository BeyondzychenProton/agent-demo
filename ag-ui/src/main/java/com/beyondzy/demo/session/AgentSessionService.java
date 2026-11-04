package com.beyondzy.demo.session;

import io.agentscope.core.session.SessionKey;
import java.util.List;

/** 用户会话目录应用服务。对应表族：agent_session。 */
public interface AgentSessionService {
    /** 幂等登记会话到用户目录。 */
    void register(long userId, String agentId, String sessionId);

    /** 列出指定用户的全部会话键。 */
    List<SessionKey> list(long userId, String userIdText);
}
