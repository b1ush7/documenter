package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.FileAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileAssetMapper extends BaseMapper<FileAsset> {

    /**
     * 原子分配下一个版本号（TODO 3.3 / 3.4 并发冲突防护）。
     *
     * <p>为什么用条件更新而不是「先查 latest_version 再 +1 后写回」：
     * 后者在两个编辑请求并发时会读到相同的旧值，产生重复版本号或互相覆盖。
     * 这里把「校验期望版本」和「递增」放进同一条 UPDATE，
     * 由数据库保证只有一个请求能成功。
     *
     * @param fileId        文件 ID
     * @param expectVersion 期望的当前版本；传 null 表示不做乐观校验
     * @return 影响行数；0 表示校验失败或文件不存在
     */
    @Update("""
            <script>
            UPDATE file_asset
               SET latest_version = latest_version + 1
             WHERE id = #{fileId}
               AND status = 'READY'
            <if test="expectVersion != null">
               AND latest_version = #{expectVersion}
            </if>
            </script>
            """)
    int advanceVersion(@Param("fileId") Long fileId, @Param("expectVersion") Integer expectVersion);

    /**
     * 读取当前最新版本号。
     *
     * <p>单独查询用于两类场景：乐观校验失败时告诉用户「实际版本是多少」，
     * 以及版本号分配成功后回读刚分配到的号。
     */
    @Select("SELECT latest_version FROM file_asset WHERE id = #{fileId}")
    Integer selectLatestVersion(@Param("fileId") Long fileId);

    /** 用户未删除文件的总字节数，用于存储用量展示（TODO 3.3）。 */
    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM file_asset WHERE user_id = #{userId} AND status <> 'DELETED'")
    Long sumActiveSizeByUser(@Param("userId") Long userId);
}
