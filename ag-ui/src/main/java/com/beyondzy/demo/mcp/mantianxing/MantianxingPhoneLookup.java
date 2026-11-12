package com.beyondzy.demo.mcp.mantianxing;

/** 满天星用登录用户的手机号签名。找不到时返回 null，不能改用模型参数里的号码。 */
@FunctionalInterface
public interface MantianxingPhoneLookup {
    String phoneOf(String userId);
}
