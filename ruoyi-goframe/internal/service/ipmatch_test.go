package service

import "testing"

// TestIsMatchedIp 逐条锁定 Java IpUtils.isMatchedIp 语义：精确/通配/网段三谓词 + 分号多条目 + 空短路。
func TestIsMatchedIp(t *testing.T) {
	cases := []struct {
		name   string
		filter string
		ip     string
		want   bool
	}{
		{"精确命中", "127.0.0.1", "127.0.0.1", true},
		{"精确不命中", "127.0.0.1", "127.0.0.2", false},
		{"尾段通配命中", "127.0.0.*", "127.0.0.7", true},
		{"尾段通配跨段不命中", "127.0.0.*", "127.0.1.7", false},
		{"双段通配命中", "10.20.*.*", "10.20.5.6", true},
		{"全通配命中", "*.*.*.*", "8.8.8.8", true},
		{"网段内命中", "192.168.1.1-192.168.1.10", "192.168.1.5", true},
		{"网段外不命中", "192.168.1.1-192.168.1.10", "192.168.1.20", false},
		{"反序网段仍命中", "192.168.1.10-192.168.1.1", "192.168.1.5", true},
		{"分号多条目命中第二条", "127.0.0.1;10.0.0.*", "10.0.0.9", true},
		{"分号多条目均不命中", "127.0.0.1;10.0.0.*", "172.16.0.1", false},
		{"空过滤串短路", "", "1.2.3.4", false},
		{"空IP短路", "127.0.0.1", "", false},
		{"非法IP安全回落", "192.168.1.1-192.168.1.10", "unknown", false},
	}
	for _, c := range cases {
		if got := IsMatchedIp(c.filter, c.ip); got != c.want {
			t.Errorf("%s: IsMatchedIp(%q,%q)=%v, 期望%v", c.name, c.filter, c.ip, got, c.want)
		}
	}
}

// TestIsIPPredicates 直接锁三条正则谓词，防止通配/网段形式被误改。
func TestIsIPPredicates(t *testing.T) {
	if !isIPAddr("1.2.3.4") || isIPAddr("1.2.3") || isIPAddr("256.0.0.1") {
		t.Errorf("isIPAddr 判定与 IPv4 全匹配契约不符")
	}
	if !isIPWildCard("127.0.0.*") || !isIPWildCard("10.*.*.*") || isIPWildCard("127.0.0.1") {
		t.Errorf("isIPWildCard 判定与通配形式契约不符")
	}
	if !isIPSegment("10.0.0.1-10.0.0.9") || isIPSegment("10.0.0.1") {
		t.Errorf("isIPSegment 判定与网段形式契约不符")
	}
}
