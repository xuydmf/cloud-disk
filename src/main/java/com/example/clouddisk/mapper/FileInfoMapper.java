package com.example.clouddisk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.clouddisk.entity.FileInfo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件信息 Mapper。
 *
 * <p>普通增删改查走 BaseMapper 即可；但回收站相关的操作必须<b>绕过逻辑删除的自动过滤</b>
 * —— MyBatis-Plus 会在自动生成的 SQL 上追加 {@code AND deleted=0}，
 * 导致回收站里一条也查不到、也删不掉。所以这些方法用注解 SQL 手写。
 */
@Mapper
public interface FileInfoMapper extends BaseMapper<FileInfo> {

    // ==================== 回收站查询（不看 deleted=0） ====================

    /** 回收站分页：按删除时间倒序 */
    @Select("<script>"
            + "SELECT * FROM file_info WHERE deleted = 1"
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND file_name LIKE CONCAT('%', #{keyword}, '%')"
            + "</if>"
            + " ORDER BY deleted_time DESC, id DESC"
            + " LIMIT #{offset}, #{size}"
            + "</script>")
    List<FileInfo> selectRecyclePage(@Param("keyword") String keyword,
                                     @Param("offset") long offset,
                                     @Param("size") long size);

    /** 回收站总数（与分页条件保持一致） */
    @Select("<script>"
            + "SELECT COUNT(*) FROM file_info WHERE deleted = 1"
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND file_name LIKE CONCAT('%', #{keyword}, '%')"
            + "</if>"
            + "</script>")
    long countRecycle(@Param("keyword") String keyword);

    /** 回收站总条数（页面头部统计） */
    @Select("SELECT COUNT(*) FROM file_info WHERE deleted = 1")
    long countAllRecycle();

    /** 回收站占用字节（页面头部统计） */
    @Select("SELECT IFNULL(SUM(file_size), 0) FROM file_info WHERE deleted = 1")
    long sumRecycleSize();

    /** 按 id 查回收站内的记录（含物理路径，恢复 / 彻底删除时要用） */
    @Select("<script>"
            + "SELECT * FROM file_info WHERE deleted = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<FileInfo> selectDeletedByIds(@Param("ids") List<Long> ids);

    // ==================== 重复文件校验 ====================

    /**
     * 按内容指纹找一份「还在库里」的文件。
     *
     * <p>md5 相同还不够，必须连同大小一起比对：历史上 md5 列允许为 null（计算失败时），
     * 只比 md5 会把一堆 null 判成互相重复。
     *
     * <p>只查 deleted = 0：回收站里的文件不算「已存在」，用户删掉再传一遍是合理诉求。
     */
    @Select("SELECT * FROM file_info WHERE deleted = 0 AND md5 = #{md5} AND file_size = #{size} "
            + "ORDER BY id ASC LIMIT 1")
    FileInfo selectLiveByMd5(@Param("md5") String md5, @Param("size") long size);

    /**
     * 视频专用兜底：按「文件名 + 大小」找一份还在库里的。
     *
     * <p>为什么视频不能只靠 md5：iOS 每次把相册视频导出给网页时都会重写容器元数据
     * （创建时间、UUID 原子等），内容是同一段、字节却不同，md5 必然每次都变。
     * 实测同一段 62MB 的 IMG_7450.mov 连传 6 次，md5 六个值全不相同，
     * 于是库里躺了 6 份、白占 365MB。
     *
     * <p>相机视频的文件名（IMG_XXXX.mov）不会重复，同名同大小基本可以断定是同一份。
     */
    @Select("SELECT * FROM file_info WHERE deleted = 0 AND file_name = #{name} AND file_size = #{size} "
            + "ORDER BY id ASC LIMIT 1")
    FileInfo selectLiveByNameAndSize(@Param("name") String name, @Param("size") long size);

    /**
     * 按「拍摄日期」聚合：相册顶部那条日期条用它。
     *
     * <p>没有拍摄时间的记录（老数据、取不到的格式）回退到上传日期，
     * 所以同一份数据在界面上永远有日期可归，不会出现「无日期」这一组。
     *
     * @param unGrouped true 表示只看「未分组」，此时忽略 groupId
     */
    @Select("<script>"
            + "SELECT DATE_FORMAT(IFNULL(captured_time, create_time), '%Y-%m-%d') AS day,"
            + " COUNT(*) AS cnt, IFNULL(SUM(file_size), 0) AS bytes"
            + " FROM file_info WHERE deleted = 0"
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND file_name LIKE CONCAT('%', #{keyword}, '%')</if>"
            + "<if test='type != null and type != \"\"'> AND file_type = #{type}</if>"
            + "<if test='unGrouped'> AND group_id IS NULL</if>"
            + "<if test='!unGrouped and groupId != null'> AND group_id = #{groupId}</if>"
            + " GROUP BY day ORDER BY day DESC"
            + "</script>")
    List<Map<String, Object>> selectDateStats(@Param("keyword") String keyword,
                                              @Param("type") String type,
                                              @Param("groupId") Long groupId,
                                              @Param("unGrouped") boolean unGrouped);

    // ==================== 状态流转 ====================

    /** 移入回收站：置 deleted=1 并记录删除时间 */
    @Update("UPDATE file_info SET deleted = 1, deleted_time = #{now} WHERE id = #{id} AND deleted = 0")
    int moveToRecycle(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 批量移入回收站 */
    @Update("<script>"
            + "UPDATE file_info SET deleted = 1, deleted_time = #{now} WHERE deleted = 0 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int moveToRecycleBatch(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /** 从回收站恢复：清掉删除标记与删除时间 */
    @Update("UPDATE file_info SET deleted = 0, deleted_time = NULL WHERE id = #{id} AND deleted = 1")
    int restore(@Param("id") Long id);

    /** 批量恢复 */
    @Update("<script>"
            + "UPDATE file_info SET deleted = 0, deleted_time = NULL WHERE deleted = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int restoreBatch(@Param("ids") List<Long> ids);

    // ==================== 物理删除（真删，不留痕） ====================

    /** 物理删除单条：直接 DELETE，绕开逻辑删除改写 */
    @Delete("DELETE FROM file_info WHERE id = #{id}")
    int physicalDelete(@Param("id") Long id);

    /** 物理批量删除 */
    @Delete("<script>"
            + "DELETE FROM file_info WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteBatch(@Param("ids") List<Long> ids);

    /** 清空回收站 */
    @Delete("DELETE FROM file_info WHERE deleted = 1")
    int clearRecycleBin();

    // ==================== 过期清理 ====================

    /** 找出删除时间早于 deadline 的记录（回收站里躺超期的） */
    @Select("SELECT * FROM file_info WHERE deleted = 1 AND deleted_time IS NOT NULL "
            + "AND deleted_time < #{deadline} ORDER BY deleted_time ASC LIMIT #{limit}")
    List<FileInfo> selectExpired(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    /** 按 deadline 物理删除超期记录，返回实际删除条数 */
    @Delete("DELETE FROM file_info WHERE deleted = 1 AND deleted_time IS NOT NULL AND deleted_time < #{deadline}")
    int deleteExpired(@Param("deadline") LocalDateTime deadline);
}
