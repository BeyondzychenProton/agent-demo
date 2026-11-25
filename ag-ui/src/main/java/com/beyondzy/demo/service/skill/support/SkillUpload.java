package com.beyondzy.demo.service.skill.support;

/** 一次上传中的单个文件。filename 是相对路径，不是调用方指定的 source。 */
public record SkillUpload(String filename, byte[] body) {}
