// Package genvm 原样内嵌 ruoyi-generator 的 21 个 Velocity 代码生成模板（批次 16-B②）。
//
// 口径：逐字节对齐 Java——本目录 vm/ 树是 ruoyi-generator/src/main/resources/vm 的字节级拷贝，
// 模板源不改动；渲染时由 service.velocity_utils 按 "vm/java/domain.java.vm" 等相对路径从此 FS 读取，
// 与 Java 侧 VelocityEngine 的资源路径命名完全一致，保证「模板字节 + 空白吞噬」两端同输入。
// 若上游 Java 模板更新，需同步重拷本树（见移植说明）。
package genvm

import "embed"

// FS 内嵌全部 .vm 模板（保留 vm/ 前缀与目录层级）。
//
//go:embed all:vm
var FS embed.FS
