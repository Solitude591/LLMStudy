package com.llmstudy.rag.auth.model;

/**
 * 账号启用状态。
 *
 * <p>禁用账号不能登录；已经登录的账号在加载当前身份或角色时也会再次校验状态。</p>
 */
public enum UserStatus {
    /** 账号正常，可以登录并访问系统。 */
    ENABLED,
    /** 账号已停用，现有登录态在下一次身份校验时失效。 */
    DISABLED
}
