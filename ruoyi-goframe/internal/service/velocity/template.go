package velocity

import (
	"fmt"
	"strings"
)

// ---- AST 元素 ----

type elem interface{}

type textElem struct{ s string }

// printElem：正文中的 $引用（求值后输出；未定义/null→原样输出 raw）。
type printElem struct {
	e   *refNode
	raw string
}

type setElem struct {
	name string
	expr exprNode
}

type breakElem struct{}

type ifBranch struct {
	cond exprNode
	body []elem
}

type ifElem struct {
	branches []ifBranch
	elseBody []elem
	hasElse  bool
}

type foreachElem struct {
	varName string
	coll    exprNode
	body    []elem
}

// ---- 帧栈解析器 ----

type frameKind int

const (
	frameRoot frameKind = iota
	frameIf
	frameForeach
)

type frame struct {
	kind     frameKind
	elems    []elem // 当前活跃分支/循环体的 elems
	branches []ifBranch
	activeIf *ifBranch // #if/#elseif 当前分支（其 body 即 frame.elems）
	inElse bool
	elseBody []elem
	// foreach
	fVar string
	fCol exprNode
}

var blockKeywords = []string{"if", "elseif", "else", "end", "foreach", "set", "break"}

type parser struct {
	src     []rune
	pos     int
	pending strings.Builder
	stack   []*frame
}

func (p *parser) top() *frame { return p.stack[len(p.stack)-1] }

// ParseTemplate 解析模板为顶层 elem 序列。解析期完成 Velocity「块指令独占整行→吞该行缩进与尾换行」
// 的空白吞噬（不回写该缩进、跳过该换行）；行内指令不吃换行。
func ParseTemplate(tpl string) ([]elem, error) {
	p := &parser{src: []rune(tpl)}
	root := &frame{kind: frameRoot}
	p.stack = []*frame{root}

	for p.pos < len(p.src) {
		c := p.src[p.pos]
		switch {
		case c == '#' && p.atDirective():
			start := p.pos
			dw := p.directiveWord()
			if err := p.handleDirective(dw, start); err != nil {
				return nil, err
			}
		case c == '$' && p.atReference():
			sc := &exprScanner{r: p.src, i: p.pos}
			node, err := sc.parseReference()
			if err != nil {
				return nil, err
			}
			rn, ok := node.(*refNode)
			if !ok {
				return nil, fmt.Errorf("velocity: 正文引用解析异常")
			}
			p.flush()
			p.top().elems = append(p.top().elems, printElem{e: rn, raw: rn.rawText()})
			p.pos = sc.i
		default:
			p.pending.WriteRune(c)
			p.pos++
		}
	}
	p.flush()
	if len(p.stack) != 1 {
		return nil, fmt.Errorf("velocity: 模板末尾仍有未闭合块（栈深 %d）", len(p.stack))
	}
	return root.elems, nil
}

// handleDirective：dw 指令词，start 其在 src 的起点（'#' 位置）。
func (p *parser) handleDirective(dw string, start int) error {
	// 解析头，取得 headerEnd（指令自身语法结束点：')' 之后或词之后）与结构信息。
	var (
		headerEnd int
		condExpr  exprNode
		fVar      string
		setName   string
		setExpr   exprNode
	)
	switch dw {
	case "#if", "#elseif", "#foreach", "#set":
		inner, closeEnd, err := p.parenContent(dw)
		if err != nil {
			return err
		}
		headerEnd = closeEnd
		switch dw {
		case "#if", "#elseif":
			condExpr, err = parseExpr(inner)
			if err != nil {
				return fmt.Errorf("velocity: %s 条件解析失败: %w", dw, err)
			}
		case "#foreach":
			fVar, condExpr, err = p.parseForeachInner(inner)
			if err != nil {
				return err
			}
		case "#set":
			setName, setExpr, err = p.parseSetInner(inner)
			if err != nil {
				return err
			}
		}
	default: // #else #end #break
		headerEnd = start + len([]rune(dw))
	}

	standalone := p.standaloneBefore(start) && p.blankAfterLine(headerEnd)
	if standalone {
		p.trimIndent()
	}
	p.flush() // 把该行此前正文（或指令行缩进已 trim）落到当前帧

	// 结构处理
	switch dw {
	case "#if":
		f := &frame{kind: frameIf, activeIf: &ifBranch{cond: condExpr}}
		p.stack = append(p.stack, f)
	case "#elseif":
		f := p.top()
		if f.kind != frameIf {
			return fmt.Errorf("velocity: #elseif 不在 #if 块内")
		}
		f.branches = append(f.branches, ifBranch{cond: f.activeIf.cond, body: f.elems})
		f.elems = nil
		f.activeIf = &ifBranch{cond: condExpr}
		f.inElse = false
	case "#else":
		f := p.top()
		if f.kind != frameIf {
			return fmt.Errorf("velocity: #else 不在 #if 块内")
		}
		f.branches = append(f.branches, ifBranch{cond: f.activeIf.cond, body: f.elems})
		f.elems = nil
		f.activeIf = nil
		f.inElse = true
	case "#foreach":
		p.stack = append(p.stack, &frame{kind: frameForeach, fVar: fVar, fCol: condExpr})
	case "#set":
		p.top().elems = append(p.top().elems, setElem{name: setName, expr: setExpr})
	case "#break":
		p.top().elems = append(p.top().elems, breakElem{})
	case "#end":
		if len(p.stack) == 1 {
			return fmt.Errorf("velocity: 多余的 #end")
		}
		f := p.top()
		p.stack = p.stack[:len(p.stack)-1]
		parent := p.top()
		switch f.kind {
		case frameIf:
			var el ifElem
			if f.inElse {
				el = ifElem{branches: f.branches, elseBody: f.elems, hasElse: true}
			} else {
				br := append(f.branches, ifBranch{cond: f.activeIf.cond, body: f.elems})
				el = ifElem{branches: br}
			}
			parent.elems = append(parent.elems, el)
		case frameForeach:
			parent.elems = append(parent.elems, foreachElem{varName: f.fVar, coll: f.fCol, body: f.elems})
		default:
			return fmt.Errorf("velocity: #end 意外闭合根帧")
		}
	default:
		return fmt.Errorf("velocity: 未处理指令 %s", dw)
	}

	if standalone {
		p.pos = headerEnd
		p.skipLineEnd()
	} else {
		p.pos = headerEnd
	}
	return nil
}

// ---- 指令头内部解析 ----

func (p *parser) parseForeachInner(inner string) (string, exprNode, error) {
	ir := []rune(strings.TrimSpace(inner))
	i := 0
	if i < len(ir) && ir[i] == '$' {
		i++
	}
	j := i
	for j < len(ir) && isIdentRune(ir[j]) {
		j++
	}
	varName := string(ir[i:j])
	rest := strings.TrimSpace(string(ir[j:]))
	if strings.HasPrefix(rest, "in") {
		rest = strings.TrimSpace(rest[2:])
	}
	if varName == "" || rest == "" {
		return "", nil, fmt.Errorf("velocity: #foreach 头非法: %q", inner)
	}
	e, err := parseExpr(rest)
	if err != nil {
		return "", nil, fmt.Errorf("velocity: #foreach 集合解析失败: %w", err)
	}
	return varName, e, nil
}

func (p *parser) parseSetInner(inner string) (string, exprNode, error) {
	ir := []rune(strings.TrimSpace(inner))
	i := 0
	if i < len(ir) && ir[i] == '$' {
		i++
	}
	if i < len(ir) && ir[i] == '{' {
		i++
	}
	j := i
	for j < len(ir) && isIdentRune(ir[j]) {
		j++
	}
	name := string(ir[i:j])
	k := j
	if k < len(ir) && ir[k] == '}' {
		k++
	}
	for k < len(ir) && (ir[k] == ' ' || ir[k] == '\t') {
		k++
	}
	if k >= len(ir) || ir[k] != '=' {
		return "", nil, fmt.Errorf("velocity: #set 缺少 =: %q", inner)
	}
	exprStr := strings.TrimSpace(string(ir[k+1:]))
	e, err := parseExpr(exprStr)
	if err != nil {
		return "", nil, fmt.Errorf("velocity: #set 值解析失败: %w", err)
	}
	return name, e, nil
}

// parenContent：p.pos 在 '#kw' 起点，返回其后括号内文本与右括号之后的位置（quote 感知配平）。
func (p *parser) parenContent(kw string) (string, int, error) {
	s := p.pos + len([]rune(kw))
	for s < len(p.src) && (p.src[s] == ' ' || p.src[s] == '\t') {
		s++
	}
	if s >= len(p.src) || p.src[s] != '(' {
		return "", 0, fmt.Errorf("velocity: %s 期望左括号", kw)
	}
	depth := 0
	var inQuote rune
	for i := s; i < len(p.src); i++ {
		c := p.src[i]
		if inQuote != 0 {
			if c == '\\' && inQuote != '`' {
				i++
				continue
			}
			if c == inQuote {
				inQuote = 0
			}
			continue
		}
		switch c {
		case '\'', '"', '`':
			inQuote = c
		case '(':
			depth++
		case ')':
			depth--
			if depth == 0 {
				return string(p.src[s+1 : i]), i + 1, nil
			}
		}
	}
	return "", 0, fmt.Errorf("velocity: %s 括号未配平", kw)
}

// ---- pending / 空白处理 ----

func (p *parser) flush() {
	if p.pending.Len() > 0 {
		p.top().elems = append(p.top().elems, textElem{s: p.pending.String()})
		p.pending.Reset()
	}
}

// trimIndent：丢弃 pending 中「最后一个换行之后仅空白」的缩进（该行为纯指令行）。
func (p *parser) trimIndent() {
	s := p.pending.String()
	if idx := strings.LastIndexByte(s, '\n'); idx >= 0 {
		if strings.TrimLeft(s[idx+1:], " \t\r") == "" {
			p.pending.Reset()
			p.pending.WriteString(s[:idx+1])
		}
	} else if strings.TrimLeft(s, " \t\r") == "" {
		p.pending.Reset()
	}
}

func (p *parser) standaloneBefore(directivePos int) bool {
	for i := directivePos - 1; i >= 0; i-- {
		c := p.src[i]
		if c == '\n' {
			return true
		}
		if c != ' ' && c != '\t' && c != '\r' {
			return false
		}
	}
	return true
}

// blankAfterLine：headerEnd 到下一换行（或 EOF）是否仅空白。
func (p *parser) blankAfterLine(headerEnd int) bool {
	for i := headerEnd; i < len(p.src); i++ {
		c := p.src[i]
		if c == '\n' {
			return true
		}
		if c != ' ' && c != '\t' && c != '\r' {
			return false
		}
	}
	return true
}

// skipLineEnd：从当前 pos 跳过行尾空白与最多一个换行。
func (p *parser) skipLineEnd() {
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if c == ' ' || c == '\t' || c == '\r' {
			p.pos++
			continue
		}
		if c == '\n' {
			p.pos++
			return
		}
		return
	}
}

// ---- 指令/引用识别 ----

func (p *parser) atDirective() bool {
	return p.directiveWord() != ""
}

func (p *parser) directiveWord() string {
	for _, kw := range blockKeywords {
		if p.hasWordAt(p.pos, "#"+kw) {
			return "#" + kw
		}
	}
	return ""
}

func (p *parser) hasWordAt(pos int, w string) bool {
	r := []rune(w)
	if pos+len(r) > len(p.src) {
		return false
	}
	for k := 0; k < len(r); k++ {
		if p.src[pos+k] != r[k] {
			return false
		}
	}
	nxt := pos + len(r)
	if nxt < len(p.src) && isIdentRune(p.src[nxt]) {
		return false
	}
	return true
}

func (p *parser) atReference() bool {
	nxt := p.pos + 1
	if nxt >= len(p.src) {
		return false
	}
	c := p.src[nxt]
	if c == '{' {
		return true
	}
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
}

func isIdentRune(c rune) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
}

// ---- 渲染 ----

// Render 以给定上下文渲染已解析模板。
func Render(elems []elem, ctx map[string]interface{}) (string, error) {
	root := newScope(nil)
	for k, v := range ctx {
		root.vars[k] = v
	}
	var out strings.Builder
	if _, err := renderElems(elems, root, &out); err != nil {
		return "", err
	}
	return out.String(), nil
}

// renderElems 渲染一段 elems；返回是否被 #break 中断。
func renderElems(elems []elem, sc *scope, out *strings.Builder) (bool, error) {
	for _, el := range elems {
		switch e := el.(type) {
		case textElem:
			out.WriteString(e.s)
		case printElem:
			v, err := e.e.eval(sc)
			if err != nil {
				return false, err
			}
			if uv, ok := v.(undefinedRef); ok {
				out.WriteString(uv.raw)
			} else if v == nil {
				out.WriteString(e.raw)
			} else {
				out.WriteString(toDisplay(v))
			}
		case setElem:
			v, err := e.expr.eval(sc)
			if err != nil {
				return false, err
			}
			if uv, ok := v.(undefinedRef); ok {
				sc.set(e.name, nilString(uv.raw))
			} else {
				sc.set(e.name, v)
			}
		case ifElem:
			done := false
			for _, br := range e.branches {
				cv, err := br.cond.eval(sc)
				if err != nil {
					return false, err
				}
				if truthy(cv) {
					brk, err := renderElems(br.body, sc, out)
					if err != nil {
						return false, err
					}
					if brk {
						return true, nil
					}
					done = true
					break
				}
			}
			if !done && e.hasElse {
				brk, err := renderElems(e.elseBody, sc, out)
				if err != nil {
					return false, err
				}
				if brk {
					return true, nil
				}
			}
		case foreachElem:
			coll, err := e.coll.eval(sc)
			if err != nil {
				return false, err
			}
			items := toItems(coll)
			total := len(items)
			for idx, it := range items {
				loopSc := newScope(sc)
				loopSc.set(e.varName, it)
				loopSc.set("foreach", &loopState{count: idx + 1, total: total})
				b, err := renderElems(e.body, loopSc, out)
				if err != nil {
					return false, err
				}
				if b {
					return true, nil
				}
			}
		case breakElem:
			return true, nil
		default:
			return false, fmt.Errorf("velocity: 未知元素 %T", el)
		}
	}
	return false, nil
}

// nilString：#set 到未解析引用时的兜底字面（print 时原样输出）。
type nilString string

func toItems(coll interface{}) []interface{} {
	switch c := coll.(type) {
	case nil:
		return nil
	case []interface{}:
		return c
	case []string:
		out := make([]interface{}, len(c))
		for i, v := range c {
			out[i] = v
		}
		return out
	case undefinedRef:
		return nil
	}
	return nil
}
