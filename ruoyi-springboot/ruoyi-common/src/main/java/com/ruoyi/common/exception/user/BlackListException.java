package com.ruoyi.common.exception.user;

/**
 * 黑名单IP异常类
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class BlackListException extends UserException {
    private static final long serialVersionUID = 1L;

    public BlackListException() {
        super("login.blocked", null);
    }
}
