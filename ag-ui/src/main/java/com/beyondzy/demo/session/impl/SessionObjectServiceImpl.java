package com.beyondzy.demo.session.impl;

import com.beyondzy.demo.domain.session.SessionObjectBean;
import com.beyondzy.demo.mapper.session.SessionObjectMapper;
import com.beyondzy.demo.session.SessionObjectService;
import com.beyondzy.demo.support.DbTimes;
import io.agentscope.core.session.AtomicSessionStorage;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 会话对象服务实现：通过 Mapper 维持跨实例的版本比较并交换语义。 */
@Service
public class SessionObjectServiceImpl implements SessionObjectService {
    private final SessionObjectMapper mapper;

    public SessionObjectServiceImpl(SessionObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AtomicSessionStorage.Value read(long userId, String objectPath) {
        SessionObjectBean object = mapper.findObject(userId, objectPath);
        return object == null ? null : new AtomicSessionStorage.Value(object.getVersion(), object.getPayload());
    }

    @Override
    public boolean compareAndSet(long userId, String objectPath, long expectedVersion, byte[] payload) {
        LocalDateTime now = DbTimes.now();
        if (expectedVersion == 0) {
            try {
                return mapper.insertFirstVersion(userId, objectPath, payload, now) == 1;
            } catch (DuplicateKeyException conflict) {
                // 另一实例已创建该对象，首版本写入失败即代表 CAS 冲突。
                return false;
            }
        }
        return mapper.updateIfVersionMatches(userId, objectPath, expectedVersion, payload, now) == 1;
    }
}
