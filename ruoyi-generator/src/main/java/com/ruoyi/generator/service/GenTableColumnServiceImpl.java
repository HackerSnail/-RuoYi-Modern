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
 * @author ruoyi
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
	 * 删除业务字段对象
	 * 
	 * @param ids 需要删除的数据ID，逗号分隔字符串
	 * @return 结果
	 */
	@Override
	public int deleteGenTableColumnByIds(String ids) {
		// 前端传逗号分隔字符串，转 Long[] 后交由 mapper 批量删除
		return genTableColumnMapper.deleteGenTableColumnByIds(Convert.toLongArray(ids));
	}
}
