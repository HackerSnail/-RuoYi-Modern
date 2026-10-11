// Package model —— 路由树出参对象，逐字段复刻 com.ruoyi.system.domain.vo.{RouterVo,MetaVo}。
// Java 侧 RouterVo 标注 @JsonInclude(NON_EMPTY)：空串/ false / nil 一律省略。
// Go 用 `,omitempty` 等价：字符串空、布尔 false、指针 nil、切片空 均被省略，与前端渲染约定一致。
package model

// RouterVo 单条前端路由。
type RouterVo struct {
	Name       string      `json:"name,omitempty"`
	Path       string      `json:"path,omitempty"`
	Hidden     bool        `json:"hidden,omitempty"` // Java 为 primitive boolean，false 属 NON_EMPTY 被省略
	Redirect   string      `json:"redirect,omitempty"`
	Component  string      `json:"component,omitempty"`
	Query      string      `json:"query,omitempty"`
	AlwaysShow *bool       `json:"alwaysShow,omitempty"` // Java Boolean，null 省略
	Meta       *MetaVo     `json:"meta,omitempty"`
	Children   []*RouterVo `json:"children,omitempty"`
}

// MetaVo 路由显示信息。
type MetaVo struct {
	Title   string `json:"title,omitempty"`
	Icon    string `json:"icon,omitempty"`
	NoCache bool   `json:"noCache,omitempty"` // Java primitive boolean，false 省略
	Link    string `json:"link,omitempty"`
}
