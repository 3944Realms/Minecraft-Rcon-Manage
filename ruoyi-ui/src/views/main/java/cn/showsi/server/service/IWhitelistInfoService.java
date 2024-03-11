package cn.showsi.server.service;

import java.util.List;
import cn.showsi.server.domain.WhitelistInfo;

/**
 * 白名单Service接口
 * 
 * @author ruoyi
 * @date 2023-12-26
 */
public interface IWhitelistInfoService 
{
    /**
     * 查询白名单
     * 
     * @param id 白名单主键
     * @return 白名单
     */
    public WhitelistInfo selectWhitelistInfoById(Long id);

    /**
     * 查询白名单列表
     * 
     * @param whitelistInfo 白名单
     * @return 白名单集合
     */
    public List<WhitelistInfo> selectWhitelistInfoList(WhitelistInfo whitelistInfo);

    /**
     * 新增白名单
     * 
     * @param whitelistInfo 白名单
     * @return 结果
     */
    public int insertWhitelistInfo(WhitelistInfo whitelistInfo);

    /**
     * 修改白名单
     * 
     * @param whitelistInfo 白名单
     * @return 结果
     */
    public int updateWhitelistInfo(WhitelistInfo whitelistInfo);

    /**
     * 批量删除白名单
     * 
     * @param ids 需要删除的白名单主键集合
     * @return 结果
     */
    public int deleteWhitelistInfoByIds(Long[] ids);

    /**
     * 删除白名单信息
     * 
     * @param id 白名单主键
     * @return 结果
     */
    public int deleteWhitelistInfoById(Long id);
}
