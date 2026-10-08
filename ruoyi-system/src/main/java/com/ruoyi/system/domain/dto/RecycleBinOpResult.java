package com.ruoyi.system.domain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 回收站批量操作（恢复/彻底删除）结果
 * <p>
 * 批处理采用"每项独立事务 + 部分成功"语义：整体请求恒 200，成败逐项体现在 items，
 * 由前端据 status/reason 渲染。计数与 items 由 Service 通过 addSuccess/addFailure 累加。
 *
 * @author ruoyi
 */
public class RecycleBinOpResult {
    /** 单项处理状态 */
    public enum Status {
        /** 成功 */
        SUCCESS,
        /** 失败 */
        FAILED
    }

    /** 单个目标的处理结果 */
    public static class Item {
        /** 目标主键 id */
        private Long id;
        /** 目标展示名（如用户名/角色名/部门名），前端提示用 */
        private String name;
        /** 处理状态 */
        private Status status;
        /** 失败原因（成功时为 null），可读文案：冲突/悬空/缺确认/越权/护栏拒绝 */
        private String reason;

        public Item() {
        }

        public Item(Long id, String name, Status status, String reason) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.reason = reason;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /** 本次处理总数 */
    private int total;
    /** 成功项数 */
    private int successCount;
    /** 失败项数 */
    private int failCount;
    /** 逐项结果 */
    private List<Item> items = new ArrayList<>();

    /**
     * 追加一个成功项并同步计数
     *
     * @param id   目标主键
     * @param name 目标展示名
     */
    public void addSuccess(Long id, String name) {
        items.add(new Item(id, name, Status.SUCCESS, null));
        successCount++;
        total++;
    }

    /**
     * 追加一个失败项并同步计数
     *
     * @param id     目标主键
     * @param name   目标展示名（未知时可传 null）
     * @param reason 失败原因文案
     */
    public void addFailure(Long id, String name, String reason) {
        items.add(new Item(id, name, Status.FAILED, reason));
        failCount++;
        total++;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }
}
