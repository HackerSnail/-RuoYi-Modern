package velocity

import "testing"

// 16-B①：解释器级自洽单测。用手写小模板对照「按 Velocity 语义推理出的期望值」，
// 不接入真实 .vm、不产 oracle（逐字节金标准留待 16-B②）。覆盖：
//   - 引用替换 / 未定义原样输出 / #{...} MyBatis 字面透传
//   - 块指令独占整行的空白吞噬 vs 行内指令不吃换行
//   - #if/#elseif/#else/#end 分支、#foreach、$foreach.hasNext、#break、#set
//   - 算术 + %、串拼接、String/List/Map 方法 shim

func mustRender(t *testing.T, tpl string, ctx map[string]interface{}) string {
	t.Helper()
	elems, err := ParseTemplate(tpl)
	if err != nil {
		t.Fatalf("ParseTemplate 失败: %v\ntpl=%q", err, tpl)
	}
	out, err := Render(elems, ctx)
	if err != nil {
		t.Fatalf("Render 失败: %v\ntpl=%q", err, tpl)
	}
	return out
}

func TestInterp(t *testing.T) {
	cases := []struct {
		name string
		tpl  string
		ctx  map[string]interface{}
		want string
	}{
		// ---- 引用 ----
		{"simple_sub", "Hello $name!", map[string]interface{}{"name": "World"}, "Hello World!"},
		{"undef_dollar", "$foo", map[string]interface{}{}, "$foo"},
		{"undef_formal", "${bar}", map[string]interface{}{}, "${bar}"},
		{"mybatis_literal", "SELECT #{id} FROM t", map[string]interface{}{}, "SELECT #{id} FROM t"},
		{"no_space_ref", "[$a$b]", map[string]interface{}{"a": "x", "b": "y"}, "[xy]"},

		// ---- #if 独占整行 ----
		{"if_true_standalone", "#if($flag)\nA\n#end\n", map[string]interface{}{"flag": true}, "A\n"},
		{"if_false_standalone", "#if($flag)\nA\n#end\n", map[string]interface{}{"flag": false}, ""},
		{"if_else_false", "#if($flag)\nA\n#else\nB\n#end\n", map[string]interface{}{"flag": false}, "B\n"},
		{"if_else_true", "#if($flag)\nA\n#else\nB\n#end\n", map[string]interface{}{"flag": true}, "A\n"},
		// 条件为假时，body（含内部换行）整段不输出；前后正文保留
		{"block_false_drops_body", "a\n#if($flag)\nfoo\nbar\n#end\nb\n", map[string]interface{}{"flag": false}, "a\nb\n"},
		{"block_true_keeps_body", "a\n#if($flag)\nfoo\nbar\n#end\nb\n", map[string]interface{}{"flag": true}, "a\nfoo\nbar\nb\n"},

		// ---- 行内指令不吃换行 ----
		{"inline_if", "$flag#if($flag)X#end\n", map[string]interface{}{"flag": true}, "trueX\n"},

		// ---- #elseif / #else ----
		{"elseif_b", "#if($n == 1)one#elseif($n == 2)two#else other#end", map[string]interface{}{"n": 2}, "two"},
		{"elseif_a", "#if($n == 1)one#elseif($n == 2)two#else other#end", map[string]interface{}{"n": 1}, "one"},
		{"elseif_else", "#if($n == 1)one#elseif($n == 2)two#else other#end", map[string]interface{}{"n": 3}, " other"},

		// ---- #set + 算术 / 取模 / 拼接 ----
		{"set_arith", "#set($n = 1 + 2)\n$n", map[string]interface{}{}, "3"},
		{"set_mod", "#set($m = 7 % 2)[$m]", map[string]interface{}{}, "[1]"},
		{"set_concat", "#set($s = \"a\" + \"b\")$s", map[string]interface{}{}, "ab"},

		// ---- String 方法 shim ----
		{"str_substring", "$s.substring(0, 3)", map[string]interface{}{"s": "abcdef"}, "abc"},
		{"str_toupper", "$s.toUpperCase()", map[string]interface{}{"s": "abc"}, "ABC"},
		{"str_indexof", "$s.indexOf('c')", map[string]interface{}{"s": "abcd"}, "2"},
		{"str_length_method", "$s.length()", map[string]interface{}{"s": "hello"}, "5"},
		{"str_length_prop", "$s.length", map[string]interface{}{"s": "hello"}, "5"},
		{"str_trim", "$s.trim()", map[string]interface{}{"s": "  hi  "}, "hi"},

		// ---- List / Map 方法 shim ----
		{"list_size", "$xs.size()", map[string]interface{}{"xs": []interface{}{1, 2, 3}}, "3"},
		{"list_get", "$xs.get(1)", map[string]interface{}{"xs": []interface{}{"a", "b"}}, "b"},
		{"map_get", "$m.get('k')", map[string]interface{}{"m": map[string]interface{}{"k": "v"}}, "v"},

		// ---- #foreach ----
		{"foreach_basic", "#foreach($x in $xs)[$x]#end", map[string]interface{}{"xs": []interface{}{"a", "b", "c"}}, "[a][b][c]"},
		{"foreach_hasnext", "#foreach($x in $xs)$x#if($foreach.hasNext),#end#end", map[string]interface{}{"xs": []interface{}{"a", "b"}}, "a,b"},
		{"foreach_standalone", "#foreach($x in $xs)\n$x\n#end\n", map[string]interface{}{"xs": []interface{}{"a", "b"}}, "a\nb\n"},
		{"foreach_empty", "#foreach($x in $xs)[$x]#end", map[string]interface{}{"xs": []interface{}{}}, ""},
		{"foreach_strslice", "#foreach($x in $xs)$x#end", map[string]interface{}{"xs": []string{"p", "q"}}, "pq"},

		// ---- #break ----
		{"brk", "#foreach($x in $xs)[$x]#if($x == 2)#break#end#end", map[string]interface{}{"xs": []interface{}{1, 2, 3}}, "[1][2]"},

		// ---- 组合：hasNext + 条件为假分支 ----
		{"nested_if_in_foreach", "#foreach($x in $xs)<$x#if($x == 2)!#end>#end", map[string]interface{}{"xs": []interface{}{1, 2}}, "<1><2!>"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := mustRender(t, c.tpl, c.ctx)
			if got != c.want {
				t.Errorf("tpl=%q\n got=%q\nwant=%q", c.tpl, got, c.want)
			}
		})
	}
}
