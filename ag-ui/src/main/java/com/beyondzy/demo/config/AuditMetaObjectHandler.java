package com.beyondzy.demo.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/** MyBatis-Plus 审计字段自动填充处理器。 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {
    /** 新增记录时，统一设置创建时间和更新时间。 */
    @Override
    public void insertFill(MetaObject metaObject) {
        long now = System.currentTimeMillis();
        strictInsertFill(metaObject, "createdAt", Long.class, now);
        strictInsertFill(metaObject, "updatedAt", Long.class, now);
    }

    /** 更新记录时，只刷新最后更新时间，保留原创建时间。 */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", Long.class, System.currentTimeMillis());
    }
}
