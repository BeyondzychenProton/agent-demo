package com.beyondzy.demo.session;

import io.agentscope.core.session.AtomicSessionStorage;

/** 为 AgentScope 原子会话存储提供应用层接口。对应表族：agent_session_object。 */
public interface SessionObjectService {
    /** 读取指定对象的当前版本与载荷。 */
    AtomicSessionStorage.Value read(long userId, String objectPath);

    /** 版本匹配时写入新载荷；返回是否 CAS 成功。 */
    boolean compareAndSet(long userId, String objectPath, long expectedVersion, byte[] payload);
}
