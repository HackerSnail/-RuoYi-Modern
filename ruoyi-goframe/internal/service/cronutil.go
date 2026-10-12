// Package service —— cronutil：自写 Quartz/gcron 兼容子集的 cron 解析 + 合法性校验 + 下次触发推算。
//
// 为什么需要：GoFrame 原生 gcron 的 schedule.Next 与 newSchedule 均非导出，无法据此计算 sys_job 列表/详情
// 需要回显的 nextValidTime，也无法在保存期独立校验 cron。故这里独立实现一份，但其解析口径必须与 gcron 运行期
// 完全同源（否则「保存期放行、运行期报错」或「nextValidTime 与实际触发时刻不符」）：
//   - 6 段：秒 分 时 日 月 周（gcron regexForCron 恰 6 段，不支持 Quartz 的第 7 段 year）；
//   - `?` 仅在日/周段合法，等价 `*`（匹配全部）；
//   - 日与周为 AND（都命中才触发，复刻 gcron_schedule_next.go：checkMeetWeek && checkMeetDay）；
//   - 周数字沿用 gcron 约定 0=SUN..6=SAT（注意：与 Quartz 1=SUN..7=SAT 不同；本 fork 真实数据用 `?`/名字，无数字周）；
//   - 支持 `* , - /` 与月/周英文名；不支持 Quartz 专有 `L W #`（gcron 亦不能运行）。
package service

import (
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/gogf/gf/v2/os/gtime"
)

// cronFieldDef 一段的时间类型标识，供英文名→数字解析区分。
type cronItemType int

const (
	cronItemSecond cronItemType = iota
	cronItemMinute
	cronItemHour
	cronItemDay
	cronItemMonth
	cronItemWeek
)

// CronSpec 解析后的调度规格：各命中集合。
type CronSpec struct {
	secondSet map[int]struct{}
	minuteSet map[int]struct{}
	hourSet   map[int]struct{}
	daySet    map[int]struct{}
	monthSet  map[int]struct{}
	weekSet   map[int]struct{}
}

var (
	cronMonthShort = map[string]int{"jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
		"jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12}
	cronMonthFull = map[string]int{"january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
		"july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12}
	cronWeekShort = map[string]int{"sun": 0, "mon": 1, "tue": 2, "wed": 3, "thu": 4, "fri": 5, "sat": 6}
	cronWeekFull  = map[string]int{"sunday": 0, "monday": 1, "tuesday": 2, "wednesday": 3, "thursday": 4, "friday": 5, "saturday": 6}
)

// CronParse 解析 6 段 cron，非法返回 error。口径逐条对齐 gcron newSchedule/parsePatternItem。
func CronParse(pattern string) (*CronSpec, error) {
	parts := strings.Fields(pattern)
	if len(parts) != 6 {
		return nil, errf(`invalid cron pattern: "%s"(需 6 段：秒 分 时 日 月 周)`, pattern)
	}
	spec := &CronSpec{}
	var err error
	if spec.secondSet, err = cronParseItem(parts[0], 0, 59, false, cronItemSecond); err != nil {
		return nil, err
	}
	if spec.minuteSet, err = cronParseItem(parts[1], 0, 59, false, cronItemMinute); err != nil {
		return nil, err
	}
	if spec.hourSet, err = cronParseItem(parts[2], 0, 23, false, cronItemHour); err != nil {
		return nil, err
	}
	if spec.daySet, err = cronParseItem(parts[3], 1, 31, true, cronItemDay); err != nil {
		return nil, err
	}
	if spec.monthSet, err = cronParseItem(parts[4], 1, 12, false, cronItemMonth); err != nil {
		return nil, err
	}
	if spec.weekSet, err = cronParseItem(parts[5], 0, 6, true, cronItemWeek); err != nil {
		return nil, err
	}
	return spec, nil
}

// CronIsValid cron 是否合法（供 validateJob）。
func CronIsValid(pattern string) bool {
	_, err := CronParse(pattern)
	return err == nil
}

// CronNext 从 from（不含）起推算下一格触发时刻；非法或 5 年内不可达回 nil。
func CronNext(pattern string, from *gtime.Time) *gtime.Time {
	spec, err := CronParse(pattern)
	if err != nil || from == nil {
		return nil
	}
	base := from.Time
	t := base.Truncate(time.Second).Add(time.Second)
	yearLimit := t.Year() + 5
	for {
		if t.Year() > yearLimit {
			return nil
		}
		if _, ok := spec.monthSet[int(t.Month())]; !ok {
			t = time.Date(t.Year(), t.Month(), 1, 0, 0, 0, 0, t.Location()).AddDate(0, 1, 0)
			continue
		}
		if _, ok := spec.daySet[t.Day()]; !ok {
			t = time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, t.Location()).AddDate(0, 0, 1)
			continue
		}
		if _, ok := spec.weekSet[int(t.Weekday())]; !ok {
			t = time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, t.Location()).AddDate(0, 0, 1)
			continue
		}
		if _, ok := spec.hourSet[t.Hour()]; !ok {
			t = time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), 0, 0, 0, t.Location()).Add(time.Hour)
			continue
		}
		if _, ok := spec.minuteSet[t.Minute()]; !ok {
			t = t.Truncate(time.Minute).Add(time.Minute)
			continue
		}
		if _, ok := spec.secondSet[t.Second()]; !ok {
			t = t.Add(time.Second)
			continue
		}
		return gtime.New(t)
	}
}

// cronParseItem 解析单段为命中集合，逐条复刻 gcron parsePatternItem。
// allowQuestionMark：true 时该段允许 `?`（等价 `*`）；否则 `?` 报错。
func cronParseItem(item string, min, max int, allowQuestionMark bool, it cronItemType) (map[int]struct{}, error) {
	set := make(map[int]struct{}, max-min+1)
	if item == "*" || (allowQuestionMark && item == "?") {
		for i := min; i <= max; i++ {
			set[i] = struct{}{}
		}
		return set, nil
	}
	if item == "" {
		return nil, errf("invalid cron pattern item: \"\"")
	}
	for _, elem := range strings.Split(item, ",") {
		interval := 1
		slash := strings.Split(elem, "/")
		if len(slash) == 2 {
			n, err := strconv.Atoi(slash[1])
			if err != nil || n <= 0 {
				return nil, errf(`invalid cron pattern item: "%s"`, elem)
			}
			interval = n
		} else if len(slash) > 2 {
			return nil, errf(`invalid cron pattern item: "%s"`, elem)
		}
		rangeMin, rangeMax := min, max
		rng := strings.Split(slash[0], "-")
		if len(rng) > 2 {
			return nil, errf(`invalid cron pattern item: "%s"`, elem)
		}
		if rng[0] != "*" {
			v, err := cronNameToInt(rng[0], it)
			if err != nil {
				return nil, err
			}
			rangeMin = v
			if len(slash) == 1 {
				rangeMax = v
			}
		}
		if len(rng) == 2 {
			v, err := cronNameToInt(rng[1], it)
			if err != nil {
				return nil, err
			}
			rangeMax = v
		}
		if rangeMin < min || rangeMax > max || rangeMin > rangeMax {
			return nil, errf(`invalid cron pattern item: "%s"(越界)`, elem)
		}
		for i := rangeMin; i <= rangeMax; i += interval {
			set[i] = struct{}{}
		}
	}
	return set, nil
}

// cronNameToInt 段内单值解析：纯数字直取；否则按段类型查月/周英文名（其余段不接受名字）。
func cronNameToInt(v string, it cronItemType) (int, error) {
	if n, err := strconv.Atoi(v); err == nil {
		return n, nil
	}
	low := strings.ToLower(v)
	switch it {
	case cronItemWeek:
		if n, ok := cronWeekShort[low]; ok {
			return n, nil
		}
		if n, ok := cronWeekFull[low]; ok {
			return n, nil
		}
	case cronItemMonth:
		if n, ok := cronMonthShort[low]; ok {
			return n, nil
		}
		if n, ok := cronMonthFull[low]; ok {
			return n, nil
		}
	}
	return 0, errf(`invalid cron pattern value: "%s"`, v)
}

// errf 内部小工具：统一构造 cronErr。
func errf(format string, a ...interface{}) error { return &cronErr{msg: fmt.Sprintf(format, a...)} }

type cronErr struct{ msg string }

func (e *cronErr) Error() string { return e.msg }
