package com.ruoyi.generator.service;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.generator.domain.GenTableColumn;
import com.ruoyi.generator.mapper.GenTableColumnMapper;

/**
 * 业务字段 服务层实现
 * 
 * <p>
 * 本类为薄委托层：除删除需将逗号分隔字符串转 Long[] 外，其余方法直接转发 GenTableColumnMapper。
 * </p>
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class GenTableColumnServiceImpl implements IGenTableColumnService {
	private final GenTableColumnMapper genTableColumnMapper;

	public GenTableColumnServiceImpl(GenTableColumnMapper genTableColumnMapper) {
		this.genTableColumnMapper = genTableColumnMapper;
	}

	/**
	 * 查询业务字段列表
	 * 
	 * @param tableId 生成表ID
	 * @return 业务字段集合
	 */
	@Override
	public List<GenTableColumn> selectGenTableColumnListByTableId(Long tableId) {
		return genTableColumnMapper.selectGenTableColumnListByTableId(tableId);
	}

	/**
	 * 新增业务字段
	 * 
	 * @param genTableColumn 业务字段信息
	 * @return 结果
	 */
	@Override
	public int insertGenTableColumn(GenTableColumn genTableColumn) {
		return genTableColumnMapper.insertGenTableColumn(genTableColumn);
	}

	/**
	 * 修改业务字段
	 * 
	 * @param genTableColumn 业务字段信息
	 * @return 结果
	 */
	@Override
	public int updateGenTableColumn(GenTableColumn genTableColumn) {
		return genTableColumnMapper.updateGenTableColumn(genTableColumn);
	}

	/**
	 * 按生成表ID（table_id）批量删除其列配置
	 * 
	 * <p>
	 * 入参是「生成表ID(table_id)」的逗号分隔串（非列主键 column_id）：底层 mapper 以
	 * {@code where table_id in (...)} 删除这些表下的全部列，供删表时级联清理；空串/null 由 mapper
	 * 的 1=0 守卫兜底为 0 行（不报错也不全表删）。
	 * </p>
	 * 
	 * @param tableIds 需要清理列配置的生成表ID，逗号分隔字符串
	 * @return 结果
	 */
	@Override
	public int deleteGenTableColumnByTableIds(String tableIds) {
		// 前端传逗号分隔的生成表ID串，转 Long[] 后交由 mapper 按 table_id 批量删除
		return genTableColumnMapper.deleteGenTableColumnByTableIds(Convert.toLongArray(tableIds));
	}
}
