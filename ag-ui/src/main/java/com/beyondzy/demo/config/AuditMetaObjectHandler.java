package com.beyondzy.demo.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/** 自动填写用户表审计时间。 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        long now = System.currentTimeMillis();
        strictInsertFill(metaObject, "createdAt", Long.class, now);
        strictInsertFill(metaObject, "updatedAt", Long.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", Long.class, System.currentTimeMillis());
    }
}
