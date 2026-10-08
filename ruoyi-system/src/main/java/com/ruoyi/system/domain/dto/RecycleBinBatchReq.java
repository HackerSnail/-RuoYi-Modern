package com.ruoyi.system.domain.dto;

/**
 * 回收站批量操作请求体
 * <p>
 * restore 仅用 ids；purge 额外用 confirm 承载二次确认（非 true 则整请求拒绝）。
 *
 * @author ruoyi
 */
public class RecycleBinBatchReq {
    /** 目标主键数组 */
    private Long[] ids;

    /** 彻底删除二次确认标志（仅 purge 使用） */
    private Boolean confirm;

    public Long[] getIds() {
        return ids;
    }

    public void setIds(Long[] ids) {
        this.ids = ids;
    }

    public Boolean getConfirm() {
        return confirm;
    }

    public void setConfirm(Boolean confirm) {
        this.confirm = confirm;
    }
}
