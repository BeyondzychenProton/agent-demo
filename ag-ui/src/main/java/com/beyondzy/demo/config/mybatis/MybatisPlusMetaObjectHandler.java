package com.beyondzy.demo.config.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.beyondzy.demo.support.DbTimes;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/** 插入时填写 gmtCreate 与 gmtModified，更新时只填写 gmtModified。 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {
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
