package com.beyondzy.demo.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.beyondzy.demo.support.DbTimes;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/** 自动填写用户表审计时间。 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = DbTimes.now();
        strictInsertFill(metaObject, "gmtCreate", LocalDateTime.class, now);
        strictInsertFill(metaObject, "gmtModified", LocalDateTime.class, now);
        if (metaObject.hasSetter("isDeleted")) {
            strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "gmtModified", LocalDateTime.class, DbTimes.now());
    }
}
