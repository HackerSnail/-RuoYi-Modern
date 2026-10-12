package velocity

import (
	"fmt"
	"strconv"
	"strings"
)

// ---- 作用域（foreach 循环变量 + #set 写入的当前层）----

type scope struct {
	vars   map[string]interface{}
	parent *scope
}

func newScope(parent *scope) *scope {
	return &scope{vars: map[string]interface{}{}, parent: parent}
}

func (s *scope) get(name string) (interface{}, bool) {
	for cur := s; cur != nil; cur = cur.parent {
		if v, ok := cur.vars[name]; ok {
			return v, ok
		}
	}
	return nil, false
}

// set 在当前层写入（仅供 foreach 循环变量/$foreach 状态等迭代级绑定使用）。
func (s *scope) set(name string, v interface{}) {
	s.vars[name] = v
}

// setGlobal 对齐 Velocity 的扁平上下文语义：#set 写入顶层根作用域，
// 因而循环体内的计数变量（如 #set($i = $i + 1）能跨迭代累加、并在循环结束后保留。
// 循环变量本身（$column）则由子作用域迭代级隔离，与此区分。
func (s *scope) setGlobal(name string, v interface{}) {
	root := s
	for root.parent != nil {
		root = root.parent
	}
	root.vars[name] = v
}

// loopState 承载 $foreach.* 循环变量。count 1 基，index 0 基。
type loopState struct {
	count int // 1 基当前序号
	total int // 集合总数
}

// ---- 表达式 AST ----

type exprNode interface {
	eval(sc *scope) (interface{}, error)
}

type literalNode struct{ v interface{} }

func (l *literalNode) eval(*scope) (interface{}, error) { return l.v, nil }

type identNode struct{ name string }

func (id *identNode) eval(sc *scope) (interface{}, error) {
	v, _ := sc.get(id.name)
	return v, nil
}

// accessor：属性访问或方法调用
type accessor struct {
	name string
	args []exprNode // nil 表示属性访问；非 nil（含空切片）表示方法调用
}

type refNode struct {
	root    string     // 引用名（$x 的 x）
	formal  bool       // ${...} 形式
	chain   []accessor // 后续 .name / .name(args)
	nullish bool       // $ 引用未解析时的原样输出文本（Velocity：$foo 未定义则输出 "$foo"）
}

func (r *refNode) eval(sc *scope) (interface{}, error) {
	// $foreach 特殊对象
	if r.root == "foreach" {
		return r.applyChain(sc, foreachValue(sc), r.chain)
	}
	v, ok := sc.get(r.root)
	if !ok {
		// 未定义引用：Velocity 原样输出引用文本，交由渲染层用 raw 处理；求值层返回特殊标记
		return undefinedRef{raw: r.rawText()}, nil
	}
	return r.applyChain(sc, v, r.chain)
}

func (r *refNode) applyChain(sc *scope, v interface{}, chain []accessor) (interface{}, error) {
	cur := v
	for _, a := range chain {
		if a.args == nil {
			// 属性访问（无括号）：走 Java getter 语义映射；未命中即视为 null（原样输出）。
			nv, ok := getProperty(cur, a.name)
			if !ok {
				return undefinedRef{raw: r.rawText()}, nil
			}
			cur = nv
			continue
		}
		// 方法调用（含显式 ()，即便无实参）：先求各实参
		argVals := make([]interface{}, len(a.args))
		for i, ax := range a.args {
			av, err := ax.eval(sc)
			if err != nil {
				return nil, err
			}
			argVals[i] = av
		}
		nv, err := callMethod(cur, a.name, argVals)
		if err != nil {
			return nil, err
		}
		cur = nv
	}
	return cur, nil
}

// rawText 还原引用的原始字面（$foo.bar / ${foo}），供未解析时原样输出。
func (r *refNode) rawText() string {
	var sb strings.Builder
	if r.formal {
		sb.WriteString("${")
		sb.WriteString(r.root)
		for _, a := range r.chain {
			sb.WriteString(".")
			sb.WriteString(a.name)
			if len(a.args) != 0 {
				sb.WriteString("()")
			}
		}
		sb.WriteString("}")
	} else {
		sb.WriteString("$")
		sb.WriteString(r.root)
		for _, a := range r.chain {
			sb.WriteString(".")
			sb.WriteString(a.name)
			if len(a.args) != 0 {
				sb.WriteString("()")
			}
		}
	}
	return sb.String()
}

// undefinedRef 表示引用未能解析，渲染层据此原样输出 raw。
type undefinedRef struct{ raw string }

// foreachValue 从作用域链取当前 $foreach 循环状态。
func foreachValue(sc *scope) interface{} {
	v, ok := sc.get("foreach")
	if !ok {
		return undefinedRef{raw: "$foreach"}
	}
	return v
}

type unaryNode struct {
	op string
	x  exprNode
}

func (u *unaryNode) eval(sc *scope) (interface{}, error) {
	v, err := u.x.eval(sc)
	if err != nil {
		return nil, err
	}
	switch u.op {
	case "!":
		return !truthy(v), nil
	case "-":
		n, ok := toNumber(v)
		if !ok {
			return nil, fmt.Errorf("velocity: 一元 - 仅作用于数值，实得 %v", v)
		}
		return -n, nil
	}
	return nil, fmt.Errorf("velocity: 未知一元运算符 %q", u.op)
}

type binaryNode struct {
	op string
	l  exprNode
	r  exprNode
}

func (b *binaryNode) eval(sc *scope) (interface{}, error) {
	switch b.op {
	case "&&":
		lv, err := b.l.eval(sc)
		if err != nil {
			return nil, err
		}
		if !truthy(lv) {
			return false, nil
		}
		rv, err := b.r.eval(sc)
		if err != nil {
			return nil, err
		}
		return truthy(rv), nil
	case "||":
		lv, err := b.l.eval(sc)
		if err != nil {
			return nil, err
		}
		if truthy(lv) {
			return true, nil
		}
		rv, err := b.r.eval(sc)
		if err != nil {
			return nil, err
		}
		return truthy(rv), nil
	}
	lv, err := b.l.eval(sc)
	if err != nil {
		return nil, err
	}
	rv, err := b.r.eval(sc)
	if err != nil {
		return nil, err
	}
	return applyBinary(b.op, lv, rv)
}

func applyBinary(op string, l, r interface{}) (interface{}, error) {
	switch op {
	case "+":
		// Velocity：任一为字符串则拼接；否则数值相加
		if ls, ok := l.(string); ok {
			return ls + toDisplay(r), nil
		}
		if rs, ok := r.(string); ok {
			return toDisplay(l) + rs, nil
		}
		lf, lok := toNumber(l)
		rf, rok := toNumber(r)
		if lok && rok {
			return lf + rf, nil
		}
		return nil, fmt.Errorf("velocity: + 操作数非数值/字符串 l=%v r=%v", l, r)
	case "-":
		lf, _ := toNumber(l)
		rf, _ := toNumber(r)
		return lf - rf, nil
	case "*":
		lf, _ := toNumber(l)
		rf, _ := toNumber(r)
		return lf * rf, nil
	case "/":
		lf, _ := toNumber(l)
		rf, _ := toNumber(r)
		if rf == 0 {
			return nil, fmt.Errorf("velocity: 除零")
		}
		return lf / rf, nil
	case "%":
		// 取模：模板中为整数场景（$i % 2）
		li, lok := toInt(l)
		ri, rok := toInt(r)
		if lok && rok && ri != 0 {
			return li % ri, nil
		}
		lf, _ := toNumber(l)
		rf, _ := toNumber(r)
		return float64(int(lf) % int(rf)), nil
	case "==":
		return valuesEqual(l, r), nil
	case "!=":
		return !valuesEqual(l, r), nil
	case "<":
		return cmpNum(l, r) < 0, nil
	case "<=":
		return cmpNum(l, r) <= 0, nil
	case ">":
		return cmpNum(l, r) > 0, nil
	case ">=":
		return cmpNum(l, r) >= 0, nil
	}
	return nil, fmt.Errorf("velocity: 未知二元运算符 %q", op)
}

func toInt(v interface{}) (int, bool) {
	switch t := v.(type) {
	case int:
		return t, true
	case int64:
		return int(t), true
	case float64:
		return int(t), true
	case string:
		n, err := strconv.Atoi(t)
		return n, err == nil
	}
	return 0, false
}

func cmpNum(l, r interface{}) int {
	lf, lok := toNumber(l)
	rf, rok := toNumber(r)
	if lok && rok {
		switch {
		case lf < rf:
			return -1
		case lf > rf:
			return 1
		default:
			return 0
		}
	}
	// 非数值：按字符串比较（Velocity 数字优先，退化到 compareTo）
	ls, rs := toDisplay(l), toDisplay(r)
	return strings.Compare(ls, rs)
}

// valuesEqual 复刻 Velocity == 语义：数值按数字比较；否则 String.equals；nil 与 "" 视为不等（null==""→false）。
func valuesEqual(l, r interface{}) bool {
	// nil 处理
	if l == nil || r == nil {
		return l == r
	}
	if lf, lok := toNumber(l); lok {
		if rf, rok := toNumber(r); rok {
			return lf == rf
		}
	}
	return toDisplay(l) == toDisplay(r)
}

// ---- 表达式词法/解析 ----

type exprScanner struct {
	r []rune
	i int
}

func (p *exprScanner) skipWS() {
	for p.i < len(p.r) && (p.r[p.i] == ' ' || p.r[p.i] == '\t' || p.r[p.i] == '\n' || p.r[p.i] == '\r') {
		p.i++
	}
}

func (p *exprScanner) peekIs(s string) bool {
	for k := 0; k < len(s); k++ {
		if p.i+k >= len(p.r) || p.r[p.i+k] != rune(s[k]) {
			return false
		}
	}
	return true
}

// parseExpr 解析整个表达式串（调用方保证 p 位于表达式起点）。
func parseExpr(s string) (exprNode, error) {
	p := &exprScanner{r: []rune(strings.TrimSpace(s))}
	n, err := p.parseOr()
	if err != nil {
		return nil, err
	}
	p.skipWS()
	if p.i != len(p.r) {
		return nil, fmt.Errorf("velocity: 表达式尾部有多余内容 @%d: %q", p.i, string(p.r[p.i:]))
	}
	return n, nil
}

func (p *exprScanner) parseOr() (exprNode, error) {
	l, err := p.parseAnd()
	if err != nil {
		return nil, err
	}
	for {
		p.skipWS()
		if p.peekIs("||") {
			p.i += 2
			r, err := p.parseAnd()
			if err != nil {
				return nil, err
			}
			l = &binaryNode{op: "||", l: l, r: r}
			continue
		}
		return l, nil
	}
}

func (p *exprScanner) parseAnd() (exprNode, error) {
	l, err := p.parseEquality()
	if err != nil {
		return nil, err
	}
	for {
		p.skipWS()
		if p.peekIs("&&") {
			p.i += 2
			r, err := p.parseEquality()
			if err != nil {
				return nil, err
			}
			l = &binaryNode{op: "&&", l: l, r: r}
			continue
		}
		return l, nil
	}
}

func (p *exprScanner) parseEquality() (exprNode, error) {
	l, err := p.parseCompare()
	if err != nil {
		return nil, err
	}
	for {
		p.skipWS()
		var op string
		switch {
		case p.peekIs("=="):
			op = "=="
		case p.peekIs("!="):
			op = "!="
		default:
			return l, nil
		}
		p.i += 2
		r, err := p.parseCompare()
		if err != nil {
			return nil, err
		}
		l = &binaryNode{op: op, l: l, r: r}
	}
}

func (p *exprScanner) parseCompare() (exprNode, error) {
	l, err := p.parseAdditive()
	if err != nil {
		return nil, err
	}
	for {
		p.skipWS()
		var op string
		switch {
		case p.peekIs("<="):
			op = "<="
		case p.peekIs(">="):
			op = ">="
		case p.i < len(p.r) && p.r[p.i] == '<':
			op = "<"
		case p.i < len(p.r) && p.r[p.i] == '>':
			op = ">"
		default:
			return l, nil
		}
		p.i += len([]rune(op))
		r, err := p.parseAdditive()
		if err != nil {
			return nil, err
		}
		l = &binaryNode{op: op, l: l, r: r}
	}
}

func (p *exprScanner) parseAdditive() (exprNode, error) {
	l, err := p.parseMultiplicative()
	if err != nil {
		return nil, err
	}
	for {
		p.skipWS()
		if p.i >= len(p.r) {
			return l, nil
		}
		c := p.r[p.i]
		if c != '+' && c != '-' {
			return l, nil
		}
		// 不把 `->`/后续非数值误判；仅 + - 运算符
		op := string(c)
		p.i++
		r, err := p.parseMultiplicative()
		if err != nil {
			return nil, err
		}
		l = &binaryNode{op: op, l: l, r: r}
	}
}

func (p *exprScanner) parseMultiplicative() (exprNode, error) {
	l, err := p.parseUnary()
	if err != nil {
		return nil, err
	}
	for {
		p.skipWS()
		if p.i >= len(p.r) {
			return l, nil
		}
		c := p.r[p.i]
		if c != '*' && c != '/' && c != '%' {
			return l, nil
		}
		op := string(c)
		p.i++
		r, err := p.parseUnary()
		if err != nil {
			return nil, err
		}
		l = &binaryNode{op: op, l: l, r: r}
	}
}

func (p *exprScanner) parseUnary() (exprNode, error) {
	p.skipWS()
	if p.i < len(p.r) && p.r[p.i] == '!' {
		// `!=` 不会出现在此处（已由二元消费）；此处 ! 为一元非
		p.i++
		x, err := p.parseUnary()
		if err != nil {
			return nil, err
		}
		return &unaryNode{op: "!", x: x}, nil
	}
	if p.i < len(p.r) && p.r[p.i] == '-' {
		p.i++
		x, err := p.parseUnary()
		if err != nil {
			return nil, err
		}
		return &unaryNode{op: "-", x: x}, nil
	}
	return p.parsePrimary()
}

func (p *exprScanner) parsePrimary() (exprNode, error) {
	p.skipWS()
	if p.i >= len(p.r) {
		return nil, fmt.Errorf("velocity: 表达式意外结束")
	}
	c := p.r[p.i]
	switch {
	case c == '(':
		p.i++
		x, err := p.parseOr()
		if err != nil {
			return nil, err
		}
		p.skipWS()
		if p.i >= len(p.r) || p.r[p.i] != ')' {
			return nil, fmt.Errorf("velocity: 期望 ) @%d", p.i)
		}
		p.i++
		return x, nil
	case c == '$':
		return p.parseReference()
	case c == '\'' || c == '"':
		return p.parseStringLit()
	case c >= '0' && c <= '9':
		return p.parseIntLit()
	default:
		// true / false
		if p.peekIsWord("true") {
			p.i += 4
			return &literalNode{v: true}, nil
		}
		if p.peekIsWord("false") {
			p.i += 5
			return &literalNode{v: false}, nil
		}
		return nil, fmt.Errorf("velocity: 无法解析的表达式起点 %q @%d", string(c), p.i)
	}
}

func (p *exprScanner) peekIsWord(w string) bool {
	if !p.peekIs(w) {
		return false
	}
	j := p.i + len(w)
	if j < len(p.r) {
		r := p.r[j]
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' {
			return false
		}
	}
	return true
}

func (p *exprScanner) parseStringLit() (exprNode, error) {
	q := p.r[p.i]
	p.i++
	var sb strings.Builder
	for p.i < len(p.r) && p.r[p.i] != q {
		if p.r[p.i] == '\\' && p.i+1 < len(p.r) {
			p.i++
			esc := p.r[p.i]
			switch esc {
			case 'n':
				sb.WriteRune('\n')
			case 't':
				sb.WriteRune('\t')
			case '\\':
				sb.WriteRune('\\')
			case '\'':
				sb.WriteRune('\'')
			case '"':
				sb.WriteRune('"')
			default:
				sb.WriteRune('\\')
				sb.WriteRune(esc)
			}
			p.i++
			continue
		}
		sb.WriteRune(p.r[p.i])
		p.i++
	}
	if p.i >= len(p.r) {
		return nil, fmt.Errorf("velocity: 字符串字面量未闭合")
	}
	p.i++ // 结束引号
	return &literalNode{v: sb.String()}, nil
}

func (p *exprScanner) parseIntLit() (exprNode, error) {
	start := p.i
	for p.i < len(p.r) && p.r[p.i] >= '0' && p.r[p.i] <= '9' {
		p.i++
	}
	n, err := strconv.Atoi(string(p.r[start:p.i]))
	if err != nil {
		return nil, fmt.Errorf("velocity: 整数字面量解析失败 %q", string(p.r[start:p.i]))
	}
	return &literalNode{v: n}, nil
}

// parseReference 解析 $x 或 ${x}，含 .prop / .method(args) 链。
func (p *exprScanner) parseReference() (exprNode, error) {
	// p.r[p.i] == '$'
	formal := false
	p.i++ // 吃掉 $
	if p.i < len(p.r) && p.r[p.i] == '{' {
		formal = true
		p.i++ // 吃掉 {
	}
	name := p.readIdent()
	if name == "" {
		return nil, fmt.Errorf("velocity: 引用缺少标识符 @%d", p.i)
	}
	r := &refNode{root: name, formal: formal, nullish: true}
	for p.i < len(p.r) && p.r[p.i] == '.' {
		p.i++ // 吃掉 .
		prop := p.readIdent()
		if prop == "" {
			return nil, fmt.Errorf("velocity: 引用链属性名为空 @%d", p.i)
		}
		// 前瞻：仅当（可含空白后）紧跟 '(' 才判定为方法调用并吞掉该空白；
		// 否则视为属性访问，绝不吞尾随空白（Velocity：`$foo != null` 中 ` != null` 是字面文本）。
		k := p.i
		for k < len(p.r) && (p.r[k] == ' ' || p.r[k] == '\t' || p.r[k] == '\n' || p.r[k] == '\r') {
			k++
		}
		if k < len(p.r) && p.r[k] == '(' {
			p.i = k // 提交方法调用：吃掉属性与 '(' 之间的空白
			args, err := p.parseArgs()
			if err != nil {
				return nil, err
			}
			r.chain = append(r.chain, accessor{name: prop, args: args})
		} else {
			r.chain = append(r.chain, accessor{name: prop})
		}
	}
	if formal {
		p.skipWS()
		if p.i >= len(p.r) || p.r[p.i] != '}' {
			return nil, fmt.Errorf("velocity: 期望 } 结束正式引用 @%d", p.i)
		}
		p.i++ // 吃掉 }
	}
	return r, nil
}

func (p *exprScanner) readIdent() string {
	start := p.i
	for p.i < len(p.r) {
		c := p.r[p.i]
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' {
			p.i++
		} else {
			break
		}
	}
	return string(p.r[start:p.i])
}

func (p *exprScanner) parseArgs() ([]exprNode, error) {
	// p.r[p.i] == '('
	p.i++
	args := []exprNode{}
	p.skipWS()
	if p.i < len(p.r) && p.r[p.i] == ')' {
		p.i++
		return args, nil
	}
	for {
		a, err := p.parseOr()
		if err != nil {
			return nil, err
		}
		args = append(args, a)
		p.skipWS()
		if p.i < len(p.r) && p.r[p.i] == ',' {
			p.i++
			p.skipWS()
			continue
		}
		if p.i < len(p.r) && p.r[p.i] == ')' {
			p.i++
			return args, nil
		}
		return nil, fmt.Errorf("velocity: 方法实参列表未正确结束 @%d", p.i)
	}
}
